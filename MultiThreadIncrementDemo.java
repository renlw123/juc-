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