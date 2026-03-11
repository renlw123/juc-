# Java JUC 源码学习笔记

## 一、JUC 概览
### 1.1 JUC 包的由来与作用
### 1.2 JUC 的核心组件
### 1.3 并发理论基础回顾（happens-before、内存模型）

## 二、atomic 原子类源码分析
### 2.1 基础原子类（AtomicInteger / AtomicLong / AtomicBoolean）
#### 2.1.1 源码结构：Unsafe 与 CAS 原理
#### 2.1.2 核心方法源码解读（getAndAddInt / compareAndSwapInt）
#### 2.1.3 流程示例：多线程下的自增

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