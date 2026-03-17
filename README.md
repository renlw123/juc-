# Java JUC 源码学习笔记

## 一、JUC 概览
### 1.1 JUC 包的由来与作用

**JDK 1.5 之前**：Java 多线程编程主要依靠 `synchronized`、`wait()`、`notify()` 等原语，功能相对基础

### 1.2 JUC 的核心组件

![](pngs\juc概览.png)

### 1.3 并发理论基础回顾（happens-before、内存模型）

#### 1.3.1 happens-before

​	Happens-Before 是 Java 内存模型中**定义操作之间内存可见性的偏序关系**。它是判断数据是否存在竞争、线程是否安全的主要依据。

简单来说，**如果操作 A Happens-Before 操作 B，那么 A 的结果对 B 可见，且 A 的执行顺序排在 B 之前**。

#### 1.3.2 内存模型

​	Java内存模型（Java Memory Model，JMM）是 Java 并发编程的基础规范，它定义了**多线程程序中共享变量的访问规则**，以及**线程之间如何通过内存进行通信**。

​	简单来说，JMM 解决了一个核心问题：**在多线程环境下，一个线程对共享变量的修改，何时对另一个线程可见**。

## 二、atomic 原子类源码分析
### 2.1 基础原子类（AtomicInteger / AtomicLong / AtomicBoolean）
#### 2.1.1 源码结构：Unsafe 与 CAS 原理

Unsafe 是 Java 提供的一个**提供底层硬件级别原子操作的类**，它位于 `sun.misc` 包下。这个类"不安全"是因为它允许 Java 程序直接操作内存，绕过了 Java 语言的安全检查。

```java
public class AtomicInteger extends Number implements java.io.Serializable {
    // 获取 Unsafe 实例
    private static final Unsafe U = Unsafe.getUnsafe();
    
    // 获取 value 字段的内存偏移量
    private static final long VALUE = U.objectFieldOffset(AtomicInteger.class, "value");
    
    // volatile 修饰的值，保证可见性
    private volatile int value;
    
    public final int incrementAndGet() {
        // 调用 Unsafe 的 CAS 方法实现原子自增
        return U.getAndAddInt(this, VALUE, 1) + 1;
    }
}
```

CAS 操作包含三个操作数：要更新的变量、更新前的预期值、要设置的新值

```java
@IntrinsicCandidate
public final native int compareAndExchangeInt(Object o, long offset, int expected, int x);
```

#### 2.1.2 核心方法源码解读（getAndAddInt / compareAndSwapInt）

```java
/**
 * 原子性地获取当前值并加上指定增量
 * 
 * @param o      要操作的对象（如 AtomicInteger 实例）
 * @param offset 对象中value字段的内存偏移量
 * @param delta  要增加的数值
 * @return 增加前的原值
 * 
 * ⚠️ 注意：此方法内部包含【自旋操作】
 *    - 使用 do-while 循环不断尝试，直到CAS成功
 *    - 这是一种【乐观锁】的实现方式
 *    - 在高竞争场景下可能循环多次，消耗CPU
 */
public final int getAndAddInt(Object o, long offset, int delta) {
    int v;
    do {
        // 1. 获取当前值（volatile读，保证可见性）
        v = getIntVolatile(o, offset);
        
        // 2. 尝试 CAS 更新
        //    compareAndSwapInt 本身只尝试【一次】
        //    但外层的 do-while 形成了【自旋】
        //    失败就继续循环，直到成功为止
    } while (!compareAndSwapInt(o, offset, v, v + delta));
    
    return v;  // 返回旧值
}

/**
 * 比较并交换（Compare And Swap）
 * 这是一个 native 方法，由 JVM 实现，最终调用 CPU 原子指令
 * 
 * @param o        对象
 * @param offset   字段偏移量
 * @param expected 期望值
 * @param x        新值
 * @return true 表示更新成功，false 表示失败（值已被其他线程修改）
 * 
 * ⚠️ 重要：这是一个【一次性操作】，内部【没有自旋】
 *    - 执行一次 CPU 原子指令（如 x86 的 lock cmpxchg）
 *    - 立即返回结果，不会重试
 *    - 成功或失败都只尝试这一次
 *    - 是否重试由上层代码决定
 */
public final native boolean compareAndSwapInt(
    Object o,        // 对象
    long offset,     // 字段偏移量
    int expected,    // 期望值
    int x            // 新值
);
```

#### 2.1.3 流程示例：多线程下的自增

```java
import java.util.concurrent.atomic.AtomicInteger;

public class MultiThreadIncrementDemo {
    
    // 共享的原子变量，初始值为0
    private static AtomicInteger counter = new AtomicInteger(0);
    
    public static void main(String[] args) {
        // 创建3个线程，同时执行自增操作
        for (int i = 1; i <= 3; i++) {
            Thread t = new Thread(new IncrementTask(), "线程T" + i);
            t.start();
        }
    }
    
    static class IncrementTask implements Runnable {
        @Override
        public void run() {
            // 每个线程执行一次自增操作
            int result = counter.incrementAndGet();
            System.out.println(Thread.currentThread().getName() + 
                             " 执行自增，结果为: " + result);
        }
    }
}
```

### 2.2 引用类型原子类（AtomicReference / AtomicStampedReference）
#### 2.2.1 ABA 问题与解决方案

 **每次成功的CAS（值真正改变时）都会创建新的Pair对象并替换旧的**
 **CAS比较的是对象的内存地址，这正是ABA检测的关键**
 **如果值没有改变，不会创建新对象，直接返回true**
 **旧对象不再被引用后会被垃圾回收**

#### 2.2.2 AtomicStampedReference 源码分析（版本号机制）

```java
package java.util.concurrent.atomic;

public class AtomicStampedReference<V> {

    /**
     * 内部静态类，用于封装引用和戳记
     * 使用不可变对象模式，所有字段都是final的
     */
    private static class Pair<T> {
        final T reference;  // 对象引用，final保证不可变性
        final int stamp;    // 整数戳记，final保证不可变性

        /**
         * 私有构造函数，只能通过of方法创建
         * @param reference 引用对象
         * @param stamp 戳记值
         */
        private Pair(T reference, int stamp) {
            this.reference = reference;
            this.stamp = stamp;
        }

        /**
         * 静态工厂方法，创建新的Pair实例
         * @param reference 引用对象
         * @param stamp 戳记值
         * @return 新的Pair对象
         */
        static <T> Pair<T> of(T reference, int stamp) {
            return new Pair<T>(reference, stamp);
        }
    }

    // volatile保证pair变量的可见性，所有线程都能看到最新值
    private volatile Pair<V> pair;

    /**
     * 构造函数，使用给定的初始值创建AtomicStampedReference
     *
     * @param initialRef 初始引用
     * @param initialStamp 初始戳记
     */
    public AtomicStampedReference(V initialRef, int initialStamp) {
        // 使用Pair.of创建不可变对象
        pair = Pair.of(initialRef, initialStamp);
    }

    /**
     * 返回当前的引用值
     * 由于pair是volatile的，这个读取操作是线程安全的
     *
     * @return 当前的引用值
     */
    public V getReference() {
        return pair.reference;  // 直接返回当前pair的引用
    }

    /**
     * 返回当前的戳记值
     *
     * @return 当前的戳记值
     */
    public int getStamp() {
        return pair.stamp;  // 直接返回当前pair的戳记
    }

    /**
     * 同时返回当前的引用和戳记值
     * 典型用法：int[1] holder; ref = v.get(holder);
     *
     * @param stampHolder 至少大小为1的数组，返回时stampHolder[0]将保存戳记值
     * @return 当前的引用值
     */
    public V get(int[] stampHolder) {
        Pair<V> pair = this.pair;  // 一次性读取pair，保证一致性
        stampHolder[0] = pair.stamp;  // 将戳记存入数组
        return pair.reference;  // 返回引用
    }

    /**
     * 弱比较并设置，可能虚假失败，不提供排序保证
     * 很少作为compareAndSet的替代方案
     *
     * @param expectedReference 期望的引用值
     * @param newReference 新的引用值
     * @param expectedStamp 期望的戳记值
     * @param newStamp 新的戳记值
     * @return 是否成功
     */
    public boolean weakCompareAndSet(V   expectedReference,
                                     V   newReference,
                                     int expectedStamp,
                                     int newStamp) {
        // 直接调用compareAndSet，当前实现中没有区别
        return compareAndSet(expectedReference, newReference,
                expectedStamp, newStamp);
    }

    /**
     * 原子性地设置引用和戳记值
     * 只有当当前引用==期望引用且当前戳记==期望戳记时才更新
     *
     * @param expectedReference 期望的引用值
     * @param newReference 新的引用值
     * @param expectedStamp 期望的戳记值
     * @param newStamp 新的戳记值
     * @return 是否成功
     */
    public boolean compareAndSet(V   expectedReference,
                                 V   newReference,
                                 int expectedStamp,
                                 int newStamp) {
        Pair<V> current = pair;  // 获取当前pair
        return
                // 第一步：检查引用是否匹配（使用==比较，而不是equals）
                expectedReference == current.reference &&
                        // 第二步：检查戳记是否匹配
                        expectedStamp == current.stamp &&
                        // 第三步：如果新值和当前值完全相同，直接返回true
                        ((newReference == current.reference &&
                                newStamp == current.stamp) ||
                                // 否则，尝试CAS更新pair
                                casPair(current, Pair.of(newReference, newStamp)));
    }

    /**
     * 无条件设置引用和戳记值
     * 注意：这个方法不是原子的，可能与其他操作产生竞态条件
     *
     * @param newReference 新的引用值
     * @param newStamp 新的戳记值
     */
    public void set(V newReference, int newStamp) {
        Pair<V> current = pair;  // 获取当前pair
        // 只有当值真正改变时才更新（优化，避免不必要的对象创建）
        if (newReference != current.reference || newStamp != current.stamp)
            this.pair = Pair.of(newReference, newStamp);  // 创建新Pair并赋值
    }

    /**
     * 原子性地设置戳记值，但要求引用必须匹配期望值
     * 这个方法可能虚假失败，但最终会成功
     *
     * @param expectedReference 期望的引用值
     * @param newStamp 新的戳记值
     * @return 是否成功
     */
    public boolean attemptStamp(V expectedReference, int newStamp) {
        Pair<V> current = pair;  // 获取当前pair
        return
                // 检查引用是否匹配
                expectedReference == current.reference &&
                        // 如果戳记已经是要设置的值，直接成功；否则尝试CAS更新
                        (newStamp == current.stamp ||
                                casPair(current, Pair.of(expectedReference, newStamp)));
    }

    // Unsafe 底层操作机制 ------------------------------------------------

    // 获取Unsafe实例，用于执行底层CAS操作
    private static final sun.misc.Unsafe UNSAFE = sun.misc.Unsafe.getUnsafe();
    // 计算pair字段的内存偏移量
    private static final long pairOffset =
            objectFieldOffset(UNSAFE, "pair", AtomicStampedReference.class);

    /**
     * CAS更新pair字段
     * @param cmp 期望的旧值
     * @param val 要设置的新值
     * @return 是否成功
     */
    private boolean casPair(Pair<V> cmp, Pair<V> val) {
        // 使用Unsafe的compareAndSwapObject执行原子CAS操作,注意这里比较的是pair的内存地址
        return UNSAFE.compareAndSwapObject(this, pairOffset, cmp, val);
    }

    /**
     * 获取字段的内存偏移量
     * @param UNSAFE Unsafe实例
     * @param field 字段名
     * @param klazz 类对象
     * @return 字段偏移量
     */
    static long objectFieldOffset(sun.misc.Unsafe UNSAFE,
                                  String field, Class<?> klazz) {
        try {
            // 通过反射获取字段的偏移量
            return UNSAFE.objectFieldOffset(klazz.getDeclaredField(field));
        } catch (NoSuchFieldException e) {
            // 将异常转换为对应的Error
            NoSuchFieldError error = new NoSuchFieldError(field);
            error.initCause(e);
            throw error;
        }
    }
}
```



### 2.3 原子数组（AtomicIntegerArray / AtomicLongArray）
#### 2.3.1 内部实现：对数组元素的 CAS 操作

```java
package java.util.concurrent.atomic;

import java.util.function.IntUnaryOperator;
import java.util.function.IntBinaryOperator;

import sun.misc.Unsafe;

/**
 * 一个可以原子方式更新元素的 {@code int} 数组。
 * 
 * 理解：array没有使用volatile修饰，并不能保证读取数组的可见性，所以读取数组的
 * 元素的一些方法的可见性都是通过Unsafe类的cas方法来保证的，不像AtomicInteger
 *      public final int get() {
 *         return value;
 *     }
 * 可以直接拿去value的值，因为AtomicInteger的value是通过volatile修饰的
 */
public class AtomicIntegerArray implements java.io.Serializable {
    private static final long serialVersionUID = 2862133569453604235L;

    // 获取Unsafe实例，用于底层原子操作
    private static final Unsafe unsafe = Unsafe.getUnsafe();
    // 获取int数组第一个元素的起始偏移量
    private static final int base = unsafe.arrayBaseOffset(int[].class);
    // 用于计算元素在内存中的偏移量
    private static final int shift;
    // 存储数据的数组，注意：这里不是volatile修饰的
    private final int[] array;

    static {
        // 获取数组中一个元素占用的字节数
        int scale = unsafe.arrayIndexScale(int[].class);
        // 确保scale是2的幂次方（这是位运算寻址的要求）
        if ((scale & (scale - 1)) != 0) throw new Error("数据类型大小不是2的幂次方");
        // 计算移位位数，用于快速计算元素偏移量
        shift = 31 - Integer.numberOfLeadingZeros(scale);
    }

    /**
     * 检查索引是否越界，并返回该索引对应的内存偏移量
     *
     * @param i 数组索引
     * @return 内存偏移量
     * @throws IndexOutOfBoundsException 如果索引越界
     */
    private long checkedByteOffset(int i) {
        if (i < 0 || i >= array.length) throw new IndexOutOfBoundsException("索引 " + i);

        return byteOffset(i);
    }

    /**
     * 计算给定索引对应的内存偏移量
     * 公式: base + i * scale，通过位运算优化
     *
     * @param i 数组索引
     * @return 内存偏移量
     */
    private static long byteOffset(int i) {
        return ((long) i << shift) + base;
    }

    /**
     * 创建一个指定长度的 AtomicIntegerArray，所有元素初始值为0。
     *
     * @param length 数组长度
     */
    public AtomicIntegerArray(int length) {
        array = new int[length];
    }

    /**
     * 创建一个与给定数组长度相同、元素全部复制的 AtomicIntegerArray。
     *
     * @param array 要复制元素的源数组
     * @throws NullPointerException 如果源数组为null
     */
    public AtomicIntegerArray(int[] array) {
        // final字段保证了可见性
        // volatile只能保证数组引用的可见性，不能保证数组元素的可见性，所以这类没有使用volatile修饰数组
        // 而是使用原子类的set/get
        this.array = array.clone();
    }

    /**
     * 返回数组的长度。
     *
     * @return 数组长度
     */
    public final int length() {
        return array.length;
    }

    /**
     * 获取位置 {@code i} 的当前值。
     *
     * @param i 索引
     * @return 当前值
     */
    public final int get(int i) {
        return getRaw(checkedByteOffset(i));
    }

    /**
     * 使用volatile语义从指定内存偏移量读取值
     *
     * @param offset 内存偏移量
     * @return 当前值
     */
    private int getRaw(long offset) {
        return unsafe.getIntVolatile(array, offset);
    }

    /**
     * 将位置 {@code i} 的元素设置为指定值。
     *
     * @param i        索引
     * @param newValue 新值
     */
    public final void set(int i, int newValue) {
        unsafe.putIntVolatile(array, checkedByteOffset(i), newValue);
    }

    /**
     * 最终将位置 {@code i} 的元素设置为指定值。
     * (非volatile顺序的写入，性能更高但可见性保证较弱)
     *
     * @param i        索引
     * @param newValue 新值
     * @since 1.6
     */
    public final void lazySet(int i, int newValue) {
        unsafe.putOrderedInt(array, checkedByteOffset(i), newValue);
    }

    /**
     * 原子地将位置 {@code i} 的元素设置为给定值，并返回旧值。
     *
     * @param i        索引
     * @param newValue 新值
     * @return 之前的值
     */
    public final int getAndSet(int i, int newValue) {
        return unsafe.getAndSetInt(array, checkedByteOffset(i), newValue);
    }

    /**
     * 如果位置 {@code i} 的当前值 {@code ==} 预期值，则原子地将其设置为给定的更新值。
     *
     * @param i      索引
     * @param expect 预期值
     * @param update 新值
     * @return 如果成功返回 {@code true}。返回false表示实际值不等于预期值。
     */
    public final boolean compareAndSet(int i, int expect, int update) {
        return compareAndSetRaw(checkedByteOffset(i), expect, update);
    }

    /**
     * 在指定内存偏移量执行CAS操作
     *
     * @param offset 内存偏移量
     * @param expect 预期值
     * @param update 新值
     * @return 是否成功
     */
    private boolean compareAndSetRaw(long offset, int expect, int update) {
        return unsafe.compareAndSwapInt(array, offset, expect, update);
    }

    /**
     * 如果位置 {@code i} 的当前值 {@code ==} 预期值，则原子地将其设置为给定的更新值。
     *
     * <p><a href="package-summary.html#weakCompareAndSet">可能会虚假失败且不提供排序保证</a>，
     * 因此很少作为 {@code compareAndSet} 的合适替代方案。
     *
     * @param i      索引
     * @param expect 预期值
     * @param update 新值
     * @return 如果成功返回 {@code true}
     */
    public final boolean weakCompareAndSet(int i, int expect, int update) {
        return compareAndSet(i, expect, update);
    }

    /**
     * 原子地将索引 {@code i} 的元素加1。
     *
     * @param i 索引
     * @return 之前的值
     */
    public final int getAndIncrement(int i) {
        return getAndAdd(i, 1);
    }

    /**
     * 原子地将索引 {@code i} 的元素减1。
     *
     * @param i 索引
     * @return 之前的值
     */
    public final int getAndDecrement(int i) {
        return getAndAdd(i, -1);
    }

    /**
     * 原子地将给定值与索引 {@code i} 的元素相加。
     *
     * @param i     索引
     * @param delta 要加的值
     * @return 之前的值
     */
    public final int getAndAdd(int i, int delta) {
        return unsafe.getAndAddInt(array, checkedByteOffset(i), delta);
    }

    /**
     * 原子地将索引 {@code i} 的元素加1。
     *
     * @param i 索引
     * @return 更新后的值
     */
    public final int incrementAndGet(int i) {
        return getAndAdd(i, 1) + 1;
    }

    /**
     * 原子地将索引 {@code i} 的元素减1。
     *
     * @param i 索引
     * @return 更新后的值
     */
    public final int decrementAndGet(int i) {
        return getAndAdd(i, -1) - 1;
    }

    /**
     * 原子地将给定值与索引 {@code i} 的元素相加。
     *
     * @param i     索引
     * @param delta 要加的值
     * @return 更新后的值
     */
    public final int addAndGet(int i, int delta) {
        return getAndAdd(i, delta) + delta;
    }


    /**
     * 原子地使用给定函数对索引 {@code i} 的元素进行更新，并返回之前的值。
     * 函数应该是无副作用的，因为当线程竞争导致更新失败时可能会重新应用。
     *
     * @param i              索引
     * @param updateFunction 无副作用的更新函数
     * @return 之前的值
     * @since 1.8
     */
    public final int getAndUpdate(int i, IntUnaryOperator updateFunction) {
        long offset = checkedByteOffset(i);
        int prev, next;
        do {
            prev = getRaw(offset);
            next = updateFunction.applyAsInt(prev);
        } while (!compareAndSetRaw(offset, prev, next));
        return prev;
    }

    /**
     * 原子地使用给定函数对索引 {@code i} 的元素进行更新，并返回更新后的值。
     * 函数应该是无副作用的，因为当线程竞争导致更新失败时可能会重新应用。
     *
     * @param i              索引
     * @param updateFunction 无副作用的更新函数
     * @return 更新后的值
     * @since 1.8
     */
    public final int updateAndGet(int i, IntUnaryOperator updateFunction) {
        long offset = checkedByteOffset(i);
        int prev, next;
        do {
            prev = getRaw(offset);
            next = updateFunction.applyAsInt(prev);
        } while (!compareAndSetRaw(offset, prev, next));
        return next;
    }

    /**
     * 原子地将给定函数应用于索引 {@code i} 的当前值和给定值，并返回之前的值。
     * 函数以当前值作为第一个参数，给定更新值作为第二个参数。
     * 函数应该是无副作用的，因为当线程竞争导致更新失败时可能会重新应用。
     *
     * @param i                   索引
     * @param x                   更新值
     * @param accumulatorFunction 无副作用的二元累积函数
     * @return 之前的值
     * @since 1.8
     */
    public final int getAndAccumulate(int i, int x, IntBinaryOperator accumulatorFunction) {
        long offset = checkedByteOffset(i);
        int prev, next;
        do {
            prev = getRaw(offset);
            next = accumulatorFunction.applyAsInt(prev, x);
        } while (!compareAndSetRaw(offset, prev, next));
        return prev;
    }

    /**
     * 原子地将给定函数应用于索引 {@code i} 的当前值和给定值，并返回更新后的值。
     * 函数以当前值作为第一个参数，给定更新值作为第二个参数。
     * 函数应该是无副作用的，因为当线程竞争导致更新失败时可能会重新应用。
     *
     * @param i                   索引
     * @param x                   更新值
     * @param accumulatorFunction 无副作用的二元累积函数
     * @return 更新后的值
     * @since 1.8
     */
    public final int accumulateAndGet(int i, int x, IntBinaryOperator accumulatorFunction) {
        long offset = checkedByteOffset(i);
        int prev, next;
        do {
            prev = getRaw(offset);
            next = accumulatorFunction.applyAsInt(prev, x);
        } while (!compareAndSetRaw(offset, prev, next));
        return next;
    }

    /**
     * 返回数组当前值的字符串表示形式。
     *
     * @return 数组当前值的字符串表示形式
     */
    public String toString() {
        int iMax = array.length - 1;
        if (iMax == -1) return "[]";

        StringBuilder b = new StringBuilder();
        b.append('[');
        for (int i = 0; ; i++) {
            // 使用volatile读获取当前值，保证获取到的是最新的值
            b.append(getRaw(byteOffset(i)));
            if (i == iMax) return b.append(']').toString();
            b.append(',').append(' ');
        }
    }

}
```



### 2.4 字段更新器（AtomicIntegerFieldUpdater）
#### 2.4.1 原理与使用限制

```java
public abstract class AtomicIntegerFieldUpdater<T> {


    @CallerSensitive  // 标记该方法对调用者敏感（用于权限检查）
    public static <U> AtomicIntegerFieldUpdater<U> newUpdater(Class<U> tclass,
                                                              String fieldName) {
        // 创建实现类，传入调用者类用于访问权限检查
        return new AtomicIntegerFieldUpdaterImpl<U>
                (tclass, fieldName, Reflection.getCallerClass());
    }

    /**
     * Protected do-nothing constructor for use by subclasses.
     * 受保护的空构造方法，供子类使用。
     */
    protected AtomicIntegerFieldUpdater() {
    }


    @SuppressWarnings("removal")
    AtomicIntegerFieldUpdaterImpl(final Class<T> tclass,
                                  final String fieldName,
                                  final Class<?> caller) {
        final Field field;
        final int   modifiers;
        try {
            // 在特权模式下获取字段（绕过访问权限检查）
            field = AccessController.doPrivileged(
                    new PrivilegedExceptionAction<Field>() {
                        public Field run() throws NoSuchFieldException {
                            return tclass.getDeclaredField(fieldName);
                        }
                    });
            modifiers = field.getModifiers();  // 获取字段修饰符

            // 确保调用者有访问该字段的权限
            sun.reflect.misc.ReflectUtil.ensureMemberAccess(
                    caller, tclass, null, modifiers);

            // 检查类加载器权限
            ClassLoader cl  = tclass.getClassLoader();
            ClassLoader ccl = caller.getClassLoader();
            if ((ccl != null) && (ccl != cl) &&
                    ((cl == null) || !isAncestor(cl, ccl))) {
                sun.reflect.misc.ReflectUtil.checkPackageAccess(tclass);
            }
        } catch (PrivilegedActionException pae) {
            throw new RuntimeException(pae.getException());
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }

        // 检查字段类型是否为int
        if (field.getType() != int.class)
            throw new IllegalArgumentException("Must be integer type");

        // 检查字段是否为volatile
        if (!Modifier.isVolatile(modifiers))
            throw new IllegalArgumentException("Must be volatile type");

        // 处理protected字段的访问限制
        // 如果字段是protected，且调用者不是同一个包，则限制接收者类型为调用者类
        this.cclass = (Modifier.isProtected(modifiers) &&
                tclass.isAssignableFrom(caller) &&
                !isSamePackage(tclass, caller))
                ? caller : tclass;
        this.tclass = tclass;
        // 获取字段的内存偏移量（用于Unsafe操作）
        this.offset = U.objectFieldOffset(field);
    }


    /**
     * Atomically increments by one the current value.
     * 原子地将当前值加1并返回新值。
     */
    public int incrementAndGet(T obj) {
        int prev, next;
        do {
            prev = get(obj);
            next = prev + 1;
        } while (!compareAndSet(obj, prev, next));
        return next;
    }


}
```



```java
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

public class AtomicIntegerFieldUpdaterCounter {

    // 要累加的类
    static class Counter {
        private volatile int count;  // 必须用volatile修饰
        private String name;

        public Counter(String name) {
            this.name = name;
            this.count = 0;
        }

        public int getCount() {
            return count;
        }

        @Override
        public String toString() {
            return String.format("Counter{name='%s', count=%d}", name, count);
        }
    }

    public static void main(String[] args) throws InterruptedException {
        // 创建更新器
        AtomicIntegerFieldUpdater<Counter> updater =
                AtomicIntegerFieldUpdater.newUpdater(Counter.class, "count");

        Counter counter = new Counter("测试计数器");

        int threadCount = 10;
        int incrementsPerThread = 100000;

        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        long startTime = System.currentTimeMillis();

        // 启动多个线程并发累加
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < incrementsPerThread; j++) {
                        // 原子性累加
                        updater.incrementAndGet(counter);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        long endTime = System.currentTimeMillis();

        int expected = threadCount * incrementsPerThread;
        System.out.println("累加完成后的计数: " + counter);
        System.out.println("期望值: " + expected);
        System.out.println("实际值: " + counter.getCount());
        System.out.println("结果正确: " + (expected == counter.getCount()));
        System.out.println("耗时: " + (endTime - startTime) + "ms");
    }
}
```



### 2.5 累加器（LongAdder / DoubleAdder）
#### 2.5.1 与 AtomicLong 对比

| 维度         | AtomicLong           | LongAdder          |
| :----------- | :------------------- | :----------------- |
| **核心优势** | 精确控制、内存小     | 高并发吞吐量大     |
| **核心劣势** | 高竞争性能差         | 内存大、非实时     |
| **最佳实践** | ID生成、限流、信号量 | 统计计数、监控指标 |
| **读频率**   | 读写均衡             | 读少写多           |
| **一致性**   | 强一致性             | 最终一致性         |

#### 2.5.2 源码解析：分段累加与最终求和

```java
package java.util.concurrent.atomic;

import java.io.Serializable;

/**
 * 当多个线程更新一个用于统计等目的的共同和（而非细粒度同步控制）时，
 * 这个类通常比AtomicLong更优。
 */
public class LongAdder extends Striped64 implements Serializable {
    private static final long serialVersionUID = 7249069246863182397L;

    /**
     * 创建一个新的累加器，初始和为0。
     */
    public LongAdder() {
    }

    /**
     * 添加给定的值。
     *
     * @param x the value to add 要添加的值
     */
    public void add(long x) {
        // cells: 单元格数组（父类Striped64中的字段）
        // base: 基础值（父类中的字段，当没有竞争时使用）
        Cell[] cs; long b, v; int m; Cell c;

        // 如果cells不为空（已有竞争），或者CAS更新base失败（出现竞争），进入分支
        if ((cs = cells) != null || !casBase(b = base, b + x)) {
            int index = getProbe();  // 获取当前线程的探针值（用于哈希到具体cell）
            boolean uncontended = true;  // 是否无竞争标志

            // 判断条件：
            // cs == null: cells数组未初始化
            // (m = cs.length - 1) < 0: cells数组长度为0
            // (c = cs[index & m]) == null: 当前线程对应的cell为null
            // !(uncontended = c.cas(v = c.value, v + x)): CAS更新cell失败
            if (cs == null || (m = cs.length - 1) < 0 ||
                    (c = cs[index & m]) == null ||
                    !(uncontended = c.cas(v = c.value, v + x)))
                // 调用父类的longAccumulate方法进行扩容或重试
                longAccumulate(x, null, uncontended, index);// cells 也是在这里进行初始化的（大小为 2）
        }
    }

    /**
     * 等同于add(1)。
     */
    public void increment() {
        add(1L);
    }

    /**
     * 等同于add(-1)。
     */
    public void decrement() {
        add(-1L);
    }

    /**
     * 返回当前总和。返回值不是原子快照；
     * 计算过程中发生的并发更新可能不会被包含在内。
     *
     * @return the sum 总和
     */
    public long sum() {
        Cell[] cs = cells;
        long sum = base;  // 从base开始
        if (cs != null) {
            // 遍历所有cell，累加它们的值
            for (Cell c : cs)
                if (c != null)
                    sum += c.value;
        }
        return sum;
    }

    /**
     * 将维护总和的所有变量重置为0。如果没有并发更新，这个方法可以替代创建新的累加器。
     */
    public void reset() {
        Cell[] cs = cells;
        base = 0L;  // 重置base
        if (cs != null) {
            // 重置所有cell
            for (Cell c : cs)
                if (c != null)
                    c.reset();
        }
    }

    /**
     * 相当于先sum()再reset()。可用于多线程计算之间的静默点。
     *
     * @return the sum 总和
     */
    public long sumThenReset() {
        Cell[] cs = cells;
        long sum = getAndSetBase(0L);  // 原子地设置base为0并返回旧值
        if (cs != null) {
            // 遍历所有cell，累加并重置
            for (Cell c : cs) {
                if (c != null)
                    sum += c.getAndSet(0L);  // 原子地设置cell为0并返回旧值
            }
        }
        return sum;
    }

    /**
     * 返回sum()的字符串表示。
     */
    public String toString() {
        return Long.toString(sum());
    }

    /**
     * 等同于sum()。
     *
     * @return the sum 总和
     */
    public long longValue() {
        return sum();
    }

    /**
     * 返回sum()的int类型值（窄化转换）。
     */
    public int intValue() {
        return (int)sum();
    }

    /**
     * 返回sum()的float类型值（加宽转换）。
     */
    public float floatValue() {
        return (float)sum();
    }

    /**
     * 返回sum()的double类型值（加宽转换）。
     */
    public double doubleValue() {
        return (double)sum();
    }

    /**
     * 序列化代理，用于避免在序列化形式中引用非公共的Striped64父类。
     */
    private static class SerializationProxy implements Serializable {
        private static final long serialVersionUID = 7249069246863182397L;

        /**
         * sum()返回的当前值。
         * @serial
         */
        private final long value;

        SerializationProxy(LongAdder a) {
            value = a.sum();
        }

        /**
         * 返回一个具有此代理所保存初始状态的LongAdder对象。
         */
        private Object readResolve() {
            LongAdder a = new LongAdder();
            a.base = value;  // 只设置base，因为反序列化时不会有竞争
            return a;
        }
    }

    /**
     * 返回表示此实例状态的SerializationProxy。
     */
    private Object writeReplace() {
        return new SerializationProxy(this);
    }

    /**
     * @throws java.io.InvalidObjectException always
     */
    private void readObject(java.io.ObjectInputStream s)
            throws java.io.InvalidObjectException {
        // 禁止直接反序列化，必须通过代理
        throw new java.io.InvalidObjectException("Proxy required");
    }
}
```

```java
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.*;

public class LongAdderMultithreadExample {

    // 模拟网站访问统计
    static class WebsiteCounter {
        // 使用LongAdder替代AtomicLong
        private final LongAdder pageViewCount = new LongAdder();
        private final LongAdder uniqueIpCount = new LongAdder();
        private final ConcurrentHashMap<String, Boolean> visitedIps = new ConcurrentHashMap<>();

        // 记录页面访问
        public void recordVisit(String ip) {
            pageViewCount.increment();  // 页面访问量+1

            // 记录独立IP
            visitedIps.computeIfAbsent(ip, k -> {
                uniqueIpCount.increment();  // 新IP才增加
                return true;
            });
        }

        public long getPageViews() {
            return pageViewCount.sum();
        }

        public long getUniqueIps() {
            return uniqueIpCount.sum();
        }

        public void reset() {
            pageViewCount.reset();
            uniqueIpCount.reset();
            visitedIps.clear();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        WebsiteCounter counter = new WebsiteCounter();
        int threadCount = 50;
        int visitsPerThread = 10000;

        // 模拟50个线程，每个线程访问10000次
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            String threadIp = "192.168.1." + (i % 10);  // 只有10个不同的IP
            Thread t = new Thread(() -> {
                for (int j = 0; j < visitsPerThread; j++) {
                    counter.recordVisit(threadIp);
                }
            });
            threads.add(t);
            t.start();
        }

        // 等待所有线程完成
        for (Thread t : threads) {
            t.join();
        }

        System.out.println("=== 网站访问统计 ===");
        System.out.println("总访问量 (PV): " + counter.getPageViews());
        System.out.println("独立IP数 (UV): " + counter.getUniqueIps());
        System.out.println("期望PV: " + (threadCount * visitsPerThread));
        System.out.println("期望UV: 10");
    }
}
```



## 三、locks 锁框架源码分析
### 3.1 抽象队列同步器 AQS（AbstractQueuedSynchronizer）
#### 3.1.1 AQS 核心数据结构：Node 与 CLH 队列变种
#### 3.1.2 独占模式源码流程（acquire / release）
#### 3.1.3 共享模式源码流程（acquireShared / releaseShared）
#### 3.1.4 条件队列 ConditionObject 源码分析（await / signal）

### 3.2 ReentrantLock 源码分析
#### 3.2.1 非公平锁与公平锁的实现差异
#### 3.2.2 lock / unlock 流程（结合 AQS）
#### 3.2.3 可重入性原理

### 3.3 ReentrantReadWriteLock 源码分析
#### 3.3.1 读写锁状态设计（高 16 位读，低 16 位写）
#### 3.3.2 读锁获取与释放流程
#### 3.3.3 写锁获取与释放流程
#### 3.3.4 锁降级与锁升级

### 3.4 StampedLock 源码分析
#### 3.4.1 三种访问模式（写、读、乐观读）
#### 3.4.2 乐观读的实现原理（无锁验证）

## 四、并发集合源码分析
### 4.1 ConcurrentHashMap（JDK 1.8）源码分析
#### 4.1.1 存储结构：Node + 数组 + 链表/红黑树
#### 4.1.2 put 流程（hash 计算、寻址、CAS 插入、树化）
#### 4.1.3 get 流程（无锁读）
#### 4.1.4 transfer 扩容流程（多线程协助）
#### 4.1.5 计数机制：CounterCell 与 size 方法

### 4.2 CopyOnWriteArrayList / CopyOnWriteArraySet
#### 4.2.1 写时复制原理
#### 4.2.2 迭代器弱一致性分析

### 4.3 ConcurrentLinkedQueue
#### 4.3.1 无锁队列设计（head/tail 的不确定性）
#### 4.3.2 offer / poll 源码流程（CAS 操作节点）

### 4.4 BlockingQueue 实现
#### 4.4.1 ArrayBlockingQueue（有界数组，单锁）
#### 4.4.2 LinkedBlockingQueue（有界/无界，双锁）
#### 4.4.3 PriorityBlockingQueue（无界二叉堆）
#### 4.4.4 DelayQueue（延迟队列实现原理）
#### 4.4.5 SynchronousQueue（双栈/双队列算法）

## 五、线程池源码分析
### 5.1 ThreadPoolExecutor 源码分析
#### 5.1.1 核心参数与线程池状态（ctl 原子变量）
#### 5.1.2 execute 流程（addWorker 细节）
#### 5.1.3 addWorker 源码解析（创建并启动线程）
#### 5.1.4 Worker 类与 AQS 的关系
#### 5.1.5 getTask 源码（线程如何获取任务）
#### 5.1.6 shutdown / shutdownNow 流程

### 5.2 四种内置线程池（newCachedThreadPool 等）源码解析
#### 5.2.1 参数设置原理

### 5.3 ScheduledThreadPoolExecutor 源码分析
#### 5.3.1 DelayedWorkQueue 延迟队列
#### 5.3.2 schedule / scheduleAtFixedRate 流程

### 5.4 ForkJoinPool 源码分析
#### 5.4.1 工作窃取算法（work-stealing）
#### 5.4.2 ForkJoinTask 与 RecursiveTask 执行流程

## 六、并发工具类源码分析
### 6.1 CountDownLatch
#### 6.1.1 基于 AQS 共享模式的实现
#### 6.1.2 await / countDown 流程

### 6.2 CyclicBarrier
#### 6.2.1 可循环利用的原理（Generation 机制）
#### 6.2.2 await 与 屏障打破流程
#### 6.2.3 与 CountDownLatch 的对比

### 6.3 Semaphore
#### 6.3.1 共享锁实现（公平/非公平）
#### 6.3.2 acquire / release 源码

### 6.4 Exchanger
#### 6.4.1 交换数据实现（Slot 与 CAS）
#### 6.4.2 多线程时如何避免竞争

### 6.5 Phaser
#### 6.5.1 阶段同步器设计（树形结构）
#### 6.5.2 register / arrive / await 流程

## 七、总结与流程图
### 7.1 AQS 核心流程图
### 7.2 ConcurrentHashMap 核心流程图
### 7.3 ThreadPoolExecutor 核心流程图
### 7.4 JUC 体系脑图