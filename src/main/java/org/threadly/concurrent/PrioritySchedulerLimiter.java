package org.threadly.concurrent;

import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

import org.threadly.util.ExceptionUtils;

/**
 * This class is designed to limit how much parallel execution happens 
 * on a provided {@link PriorityScheduledExecutor}.  This allows the 
 * implementor to have one thread pool for all their code, and if 
 * they want certain sections to have less levels of parallelism 
 * (possibly because those those sections would completely consume the 
 * global pool), they can wrap the executor in this class.
 * 
 * Thus providing you better control on the absolute thread count and 
 * how much parallism can occur in different sections of the program.  
 * 
 * Thus avoiding from having to create multiple thread pools, and also 
 * using threads more efficiently than multiple thread pools would.
 * 
 * @author jent - Mike Jensen
 */
public class PrioritySchedulerLimiter extends AbstractSchedulerLimiter 
                                      implements PrioritySchedulerInterface {
  protected final PrioritySchedulerInterface scheduler;
  protected final Queue<Wrapper> waitingTasks;
  
  /**
   * Constructs a new limiter that implements the {@link PrioritySchedulerInterface}.
   * 
   * @param scheduler {@link PrioritySchedulerInterface} implementation to submit task executions to.
   * @param maxConcurrency maximum qty of runnables to run in parallel
   */
  public PrioritySchedulerLimiter(PrioritySchedulerInterface scheduler, 
                                  int maxConcurrency) {
    this(scheduler, maxConcurrency, null);
  }
  
  /**
   * Constructs a new limiter that implements the {@link PrioritySchedulerInterface}.
   * 
   * @param scheduler {@link PrioritySchedulerInterface} implementation to submit task executions to.
   * @param maxConcurrency maximum qty of runnables to run in parallel
   * @param subPoolName name to describe threads while tasks running in pool (null to not change thread names)
   */
  public PrioritySchedulerLimiter(PrioritySchedulerInterface scheduler, 
                                  int maxConcurrency, String subPoolName) {
    super(subPoolName, maxConcurrency);
    
    if (scheduler == null) {
      throw new IllegalArgumentException("Must provide scheduler");
    }
    
    this.scheduler = scheduler;
    waitingTasks = new ConcurrentLinkedQueue<Wrapper>();
  }
  
  @Override
  protected void consumeAvailable() {
    /* must synchronize in queue consumer to avoid 
     * multiple threads from consuming tasks in parallel 
     * and possibly emptying after .isEmpty() check but 
     * before .poll()
     */
    synchronized (this) {
      while (! waitingTasks.isEmpty() && canRunTask()) {
        // by entering loop we can now execute task
        Wrapper next = waitingTasks.poll();
        if (next.hasFuture()) {
          if (next.isCallable()) {
            ListenableFuture<?> f = scheduler.submit(next.getCallable(), next.getPriority());
            next.getFuture().setParentFuture(f);
          } else {
            ListenableFuture<?> f = scheduler.submit(next.getRunnable(), next.getPriority());
            next.getFuture().setParentFuture(f);
          }
        } else {
          // all callables will have futures, so we know this is a runnable
          scheduler.execute(next.getRunnable(), next.getPriority());
        }
      }
    }
  }

  @Override
  public void execute(Runnable task) {
    execute(task, scheduler.getDefaultPriority());
  }

  @Override
  public ListenableFuture<?> submit(Runnable task) {
    return submit(task, scheduler.getDefaultPriority());
  }

  @Override
  public <T> ListenableFuture<T> submit(Runnable task, T result) {
    return submit(task, result, scheduler.getDefaultPriority());
  }

  @Override
  public <T> ListenableFuture<T> submit(Callable<T> task) {
    return submit(task, scheduler.getDefaultPriority());
  }

  @Override
  public void schedule(Runnable task, long delayInMs) {
    schedule(task, delayInMs, 
             scheduler.getDefaultPriority());
  }

  @Override
  public ListenableFuture<?> submitScheduled(Runnable task, long delayInMs) {
    return submitScheduled(task, delayInMs, 
                           scheduler.getDefaultPriority());
  }

  @Override
  public <T> ListenableFuture<T> submitScheduled(Runnable task, T result, long delayInMs) {
    return submitScheduled(task, result, delayInMs, 
                           scheduler.getDefaultPriority());
  }

  @Override
  public <T> ListenableFuture<T> submitScheduled(Callable<T> task, long delayInMs) {
    return submitScheduled(task, delayInMs, 
                           scheduler.getDefaultPriority());
  }

  @Override
  public void scheduleWithFixedDelay(Runnable task, long initialDelay,
                                     long recurringDelay) {
    scheduleWithFixedDelay(task, initialDelay, recurringDelay, 
                           scheduler.getDefaultPriority());
  }

  @Override
  public boolean isShutdown() {
    return scheduler.isShutdown();
  }

  @Override
  public void execute(Runnable task, TaskPriority priority) {
    if (task == null) {
      throw new IllegalArgumentException("Must provide task");
    }
    if (priority == null) {
      priority = scheduler.getDefaultPriority();
    }
    
    PriorityRunnableWrapper wrapper = new PriorityRunnableWrapper(task, priority, null);
    
    if (canRunTask()) {  // try to avoid adding to queue if we can
      scheduler.execute(wrapper, priority);
    } else {
      waitingTasks.add(wrapper);
      consumeAvailable(); // call to consume in case task finished after first check
    }
  }

  @Override
  public ListenableFuture<?> submit(Runnable task, TaskPriority priority) {
    return submit(task, null, priority);
  }

  @Override
  public <T> ListenableFuture<T> submit(Runnable task, T result, TaskPriority priority) {
    if (task == null) {
      throw new IllegalArgumentException("Must provide task");
    }
    if (priority == null) {
      priority = scheduler.getDefaultPriority();
    }
    
    FutureFuture<T> ff = new FutureFuture<T>();
    
    doSubmit(task, result, priority, ff);
    
    return ff;
  }
  
  private <T> void doSubmit(Runnable task, T result, 
                            TaskPriority priority, FutureFuture<T> ff) {
    PriorityRunnableWrapper wrapper = new PriorityRunnableWrapper(task, priority, ff);
    
    if (canRunTask()) {  // try to avoid adding to queue if we can
      ff.setParentFuture(scheduler.submit(wrapper, result, priority));
    } else {
      waitingTasks.add(wrapper);
      consumeAvailable(); // call to consume in case task finished after first check
    }
  }

  @Override
  public <T> ListenableFuture<T> submit(Callable<T> task, TaskPriority priority) {
    if (task == null) {
      throw new IllegalArgumentException("Must provide task");
    }
    if (priority == null) {
      priority = scheduler.getDefaultPriority();
    }
    
    FutureFuture<T> ff = new FutureFuture<T>();
    
    doSubmit(task, priority, ff);
    
    return ff;
  }
  
  private <T> void doSubmit(Callable<T> task, 
                            TaskPriority priority, FutureFuture<T> ff) {
    PriorityCallableWrapper<T> wrapper = new PriorityCallableWrapper<T>(task, priority, ff);
    
    if (canRunTask()) {  // try to avoid adding to queue if we can
      ff.setParentFuture(scheduler.submit(wrapper, priority));
    } else {
      waitingTasks.add(wrapper);
      consumeAvailable(); // call to consume in case task finished after first check
    }
  }

  @Override
  public void schedule(Runnable task, long delayInMs, 
                       TaskPriority priority) {
    if (task == null) {
      throw new IllegalArgumentException("Must provide a task");
    } else if (delayInMs < 0) {
      throw new IllegalArgumentException("delayInMs must be >= 0");
    }
    if (priority == null) {
      priority = scheduler.getDefaultPriority();
    }
    
    if (delayInMs == 0) {
      execute(task, priority);
    } else {
      scheduler.schedule(new DelayedExecutionRunnable<Object>(task, null, priority, null), 
                         delayInMs, priority);
    }
  }

  @Override
  public ListenableFuture<?> submitScheduled(Runnable task, long delayInMs,
                                             TaskPriority priority) {
    return submitScheduled(task, null, delayInMs, priority);
  }

  @Override
  public <T> ListenableFuture<T> submitScheduled(Runnable task, T result, long delayInMs,
                                                 TaskPriority priority) {
    if (task == null) {
      throw new IllegalArgumentException("Must provide a task");
    } else if (delayInMs < 0) {
      throw new IllegalArgumentException("delayInMs must be >= 0");
    }
    if (priority == null) {
      priority = scheduler.getDefaultPriority();
    }

    FutureFuture<T> ff = new FutureFuture<T>();
    if (delayInMs == 0) {
      doSubmit(task, result, priority, ff);
    } else {
      scheduler.schedule(new DelayedExecutionRunnable<T>(task, result, priority, ff), 
                         delayInMs, priority);
    }
      
    return ff;
  }

  @Override
  public <T> ListenableFuture<T> submitScheduled(Callable<T> task, long delayInMs,
                                                 TaskPriority priority) {
    if (task == null) {
      throw new IllegalArgumentException("Must provide a task");
    } else if (delayInMs < 0) {
      throw new IllegalArgumentException("delayInMs must be >= 0");
    }
    if (priority == null) {
      priority = scheduler.getDefaultPriority();
    }

    FutureFuture<T> ff = new FutureFuture<T>();
    if (delayInMs == 0) {
      doSubmit(task, priority, ff);
    } else {
      scheduler.schedule(new DelayedExecutionCallable<T>(task, priority, ff), 
                         delayInMs, priority);
    }
    
    return ff;
  }

  @Override
  public void scheduleWithFixedDelay(Runnable task, long initialDelay,
                                     long recurringDelay, TaskPriority priority) {
    if (task == null) {
      throw new IllegalArgumentException("Must provide a task");
    } else if (initialDelay < 0) {
      throw new IllegalArgumentException("initialDelay must be >= 0");
    } else if (recurringDelay < 0) {
      throw new IllegalArgumentException("recurringDelay must be >= 0");
    }
    if (priority == null) {
      priority = scheduler.getDefaultPriority();
    }
    
    RecurringRunnableWrapper rrw = new RecurringRunnableWrapper(task, recurringDelay, priority);
    
    if (initialDelay == 0) {
      execute(rrw, priority);
    } else {
      scheduler.schedule(new DelayedExecutionRunnable<Object>(rrw, null, priority, null), 
                         initialDelay, priority);
    }
  }

  @Override
  public TaskPriority getDefaultPriority() {
    return scheduler.getDefaultPriority();
  }
  
  /**
   * Small runnable that allows scheduled tasks to pass through 
   * the same execution queue that immediate execution has to.
   * 
   * @author jent - Mike Jensen
   */
  protected class DelayedExecutionRunnable<T> extends VirtualRunnable {
    private final Runnable runnable;
    private final T runnableResult;
    private final TaskPriority priority;
    private final FutureFuture<T> future;

    public DelayedExecutionRunnable(Runnable runnable, T runnableResult, 
                                    TaskPriority priority, 
                                    FutureFuture<T> future) {
      this.runnable = runnable;
      this.runnableResult = runnableResult;
      this.priority = priority;
      this.future = future;
    }
    
    @Override
    public void run() {
      if (future == null) {
        execute(runnable, priority);
      } else {
        doSubmit(runnable, runnableResult, priority, future);
      }
    }
  }
  
  /**
   * Small runnable that allows scheduled tasks to pass through 
   * the same execution queue that immediate execution has to.
   * 
   * @author jent - Mike Jensen
   */
  protected class DelayedExecutionCallable<T> extends VirtualRunnable {
    private final Callable<T> callable;
    private final TaskPriority priority;
    private final FutureFuture<T> future;

    public DelayedExecutionCallable(Callable<T> runnable, 
                                    TaskPriority priority, 
                                    FutureFuture<T> future) {
      this.callable = runnable;
      this.priority = priority;
      this.future = future;
    }
    
    @Override
    public void run() {
      doSubmit(callable, priority, future);
    }
  }

  /**
   * Wrapper for priority tasks which are executed in this sub pool, 
   * this ensures that handleTaskFinished() will be called 
   * after the task completes.
   * 
   * @author jent - Mike Jensen
   */
  protected class RecurringRunnableWrapper extends VirtualRunnable
                                           implements Wrapper  {
    private final Runnable runnable;
    private final long recurringDelay;
    private final TaskPriority priority;
    
    public RecurringRunnableWrapper(Runnable runnable, 
                                    long recurringDelay, 
                                    TaskPriority priority) {
      this.runnable = runnable;
      this.recurringDelay = recurringDelay;
      this.priority = priority;
    }
    
    @Override
    public void run() {
      Thread currentThread = null;
      String originalThreadName = null;
      if (subPoolName != null) {
        currentThread = Thread.currentThread();
        originalThreadName = currentThread.getName();
        
        currentThread.setName(makeSubPoolThreadName(originalThreadName));
      }
      
      try {
        if (factory != null && 
            runnable instanceof VirtualRunnable) {
          VirtualRunnable vr = (VirtualRunnable)runnable;
          vr.run(factory);
        } else {
          runnable.run();
        }
      } finally {
        try {
          handleTaskFinished();
        } finally {
          scheduler.schedule(this, recurringDelay, priority);
          
          if (subPoolName != null) {
            currentThread.setName(originalThreadName);
          }
        }
      }
    }

    @Override
    public boolean isCallable() {
      return false;
    }

    @Override
    public FutureFuture<?> getFuture() {
      throw new UnsupportedOperationException();
    }

    @Override
    public TaskPriority getPriority() {
      return priority;
    }

    @Override
    public boolean hasFuture() {
      return false;
    }

    @Override
    public Callable<?> getCallable() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Runnable getRunnable() {
      return this;
    }
  }

  /**
   * Wrapper for priority tasks which are executed in this sub pool, 
   * this ensures that handleTaskFinished() will be called 
   * after the task completes.
   * 
   * @author jent - Mike Jensen
   */
  protected class PriorityRunnableWrapper extends RunnableWrapper
                                          implements Wrapper  {
    private final TaskPriority priority;
    private final FutureFuture<?> future;
    
    public PriorityRunnableWrapper(Runnable runnable, 
                                   TaskPriority priority, 
                                   FutureFuture<?> future) {
      super(runnable);
      
      this.priority = priority;
      this.future = future;
    }

    @Override
    public boolean isCallable() {
      return false;
    }

    @Override
    public FutureFuture<?> getFuture() {
      return future;
    }

    @Override
    public TaskPriority getPriority() {
      return priority;
    }

    @Override
    public boolean hasFuture() {
      return future != null;
    }

    @Override
    public Callable<?> getCallable() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Runnable getRunnable() {
      return this;
    }
  }

  /**
   * Wrapper for priority tasks which are executed in this sub pool, 
   * this ensures that handleTaskFinished() will be called 
   * after the task completes.
   * 
   * @author jent - Mike Jensen
   */
  protected class PriorityCallableWrapper<T> extends VirtualCallable<T>
                                             implements Wrapper {
    private final Callable<T> callable;
    private final TaskPriority priority;
    private final FutureFuture<?> future;
    
    public PriorityCallableWrapper(Callable<T> callable, 
                                   TaskPriority priority, 
                                   FutureFuture<?> future) {
      this.callable = callable;
      this.priority = priority;
      this.future = future;
    }
    
    @Override
    public T call() throws Exception {
      Thread currentThread = null;
      String originalThreadName = null;
      if (subPoolName != null) {
        currentThread = Thread.currentThread();
        originalThreadName = currentThread.getName();
        
        currentThread.setName(makeSubPoolThreadName(originalThreadName));
      }
      
      try {
        if (factory != null && 
            callable instanceof VirtualCallable) {
          VirtualCallable<T> vc = (VirtualCallable<T>)callable;
          return vc.call(factory);
        } else {
          return callable.call();
        }
      } finally {
        handleTaskFinished();
          
        if (subPoolName != null) {
          currentThread.setName(originalThreadName);
        }
      }
    }

    @Override
    public boolean isCallable() {
      return true;
    }

    @Override
    public FutureFuture<?> getFuture() {
      return future;
    }

    @Override
    public TaskPriority getPriority() {
      return priority;
    }

    @Override
    public boolean hasFuture() {
      return future != null;
    }

    @Override
    public Callable<?> getCallable() {
      return this;
    }

    @Override
    public Runnable getRunnable() {
      throw new UnsupportedOperationException();
    }
  }
  
  /**
   * Interface so that we can handle both callables and runnables.
   * 
   * @author jent - Mike Jensen
   */
  private interface Wrapper {
    public boolean isCallable();
    public FutureFuture<?> getFuture();
    public TaskPriority getPriority();
    public boolean hasFuture();
    public Callable<?> getCallable();
    public Runnable getRunnable();
  }
  
  /**
   * ListenableFuture which contains a parent {@link ListenableFuture}. 
   * (which may not be created yet).
   * 
   * @author jent - Mike Jensen
   * @param <T> result type returned by .get()
   */
  protected static class FutureFuture<T> extends AbstractSchedulerLimiter.FutureFuture<T> 
                                         implements ListenableFuture<T> {
    private ListenableFuture<?> parentFuture;
    
    public FutureFuture() {
      parentFuture = null;
    }
    
    protected void setParentFuture(ListenableFuture<?> parentFuture) {
      synchronized (this) {
        this.parentFuture = parentFuture;
        
        super.setParentFuture(parentFuture);  // parent will call this.notifyAll()
      }
    }

    @Override
    public void addListener(Runnable listener) {
      addListener(listener, null);
    }

    @Override
    public void addListener(Runnable listener, Executor executor) {
      synchronized (this) {
        while (parentFuture == null) {
          try {
            this.wait();
          } catch (InterruptedException e) {
            throw ExceptionUtils.makeRuntime(e);
          }
        }
      }
      
      parentFuture.addListener(listener, executor);
    }
  }
}