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
#### 2.2.2 AtomicStampedReference 源码分析（版本号机制）

### 2.3 原子数组（AtomicIntegerArray / AtomicLongArray）
#### 2.3.1 内部实现：对数组元素的 CAS 操作

### 2.4 字段更新器（AtomicIntegerFieldUpdater）
#### 2.4.1 原理与使用限制

### 2.5 累加器（LongAdder / DoubleAdder）
#### 2.5.1 与 AtomicLong 对比
#### 2.5.2 源码解析：分段累加与最终求和

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