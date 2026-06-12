package dev.arcp.runtime.coverage;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Deterministic {@link ScheduledExecutorService}: nothing scheduled runs until the test runs it
 * explicitly. {@code execute} runs inline.
 */
final class ManualScheduler extends AbstractExecutorService implements ScheduledExecutorService {

  static final class Task implements ScheduledFuture<Object> {
    private final Runnable command;
    private final long delayMillis;
    private final boolean periodic;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    private Task(Runnable command, long delayMillis, boolean periodic) {
      this.command = command;
      this.delayMillis = delayMillis;
      this.periodic = periodic;
    }

    boolean periodic() {
      return periodic;
    }

    long delayMillis() {
      return delayMillis;
    }

    /** Run the task body regardless of cancellation state (tests use this to model races). */
    void runIgnoringCancel() {
      command.run();
    }

    /** Run only if not cancelled. */
    void run() {
      if (!cancelled.get()) {
        command.run();
      }
    }

    @Override
    public long getDelay(TimeUnit unit) {
      return unit.convert(delayMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public int compareTo(Delayed o) {
      return Long.compare(getDelay(TimeUnit.MILLISECONDS), o.getDelay(TimeUnit.MILLISECONDS));
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      return cancelled.compareAndSet(false, true);
    }

    @Override
    public boolean isCancelled() {
      return cancelled.get();
    }

    @Override
    public boolean isDone() {
      return cancelled.get();
    }

    @Override
    public Object get() throws InterruptedException, ExecutionException {
      throw new UnsupportedOperationException("manual task");
    }

    @Override
    public Object get(long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException {
      throw new UnsupportedOperationException("manual task");
    }
  }

  private final List<Task> tasks = new CopyOnWriteArrayList<>();
  private volatile boolean rejecting;
  private volatile boolean shutdown;
  private int drained;

  void rejecting(boolean value) {
    this.rejecting = value;
  }

  List<Task> tasks() {
    return tasks;
  }

  /** Tasks scheduled since the previous call to this method. */
  synchronized List<Task> drainNew() {
    List<Task> fresh = List.copyOf(tasks.subList(drained, tasks.size()));
    drained = tasks.size();
    return fresh;
  }

  /** Wait (bounded) until at least {@code count} tasks have been scheduled in total. */
  Task awaitTask(int index) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadline) {
      if (tasks.size() > index) {
        return tasks.get(index);
      }
      Thread.sleep(5);
    }
    throw new AssertionError("timed out waiting for scheduled task #" + index);
  }

  private Task add(Runnable command, long delay, TimeUnit unit, boolean periodic) {
    if (rejecting) {
      throw new RejectedExecutionException("manual scheduler rejecting (test)");
    }
    Task task = new Task(command, unit.toMillis(delay), periodic);
    tasks.add(task);
    return task;
  }

  @Override
  public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
    return add(command, delay, unit, false);
  }

  @Override
  public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
    throw new UnsupportedOperationException("callable schedule not used");
  }

  @Override
  public ScheduledFuture<?> scheduleAtFixedRate(
      Runnable command, long initialDelay, long period, TimeUnit unit) {
    return add(command, period, unit, true);
  }

  @Override
  public ScheduledFuture<?> scheduleWithFixedDelay(
      Runnable command, long initialDelay, long delay, TimeUnit unit) {
    return add(command, delay, unit, true);
  }

  @Override
  public void execute(Runnable command) {
    command.run();
  }

  @Override
  public void shutdown() {
    shutdown = true;
  }

  @Override
  public List<Runnable> shutdownNow() {
    shutdown = true;
    return List.of();
  }

  @Override
  public boolean isShutdown() {
    return shutdown;
  }

  @Override
  public boolean isTerminated() {
    return shutdown;
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) {
    return true;
  }
}
