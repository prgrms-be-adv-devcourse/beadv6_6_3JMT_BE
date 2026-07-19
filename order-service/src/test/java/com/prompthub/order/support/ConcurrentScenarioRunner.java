package com.prompthub.order.support;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public final class ConcurrentScenarioRunner implements AutoCloseable {

	private static final int TASK_COUNT = 2;

	private final long timeoutSeconds;
	private final ExecutorService executor;
	private final TransactionTemplate newTransaction;

	public ConcurrentScenarioRunner(
		PlatformTransactionManager transactionManager,
		long timeoutSeconds
	) {
		if (timeoutSeconds <= 0 || timeoutSeconds > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("timeoutSeconds must be between 1 and Integer.MAX_VALUE");
		}
		this.timeoutSeconds = timeoutSeconds;
		this.executor = Executors.newFixedThreadPool(TASK_COUNT);
		this.newTransaction = new TransactionTemplate(transactionManager);
		this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.newTransaction.setTimeout((int) timeoutSeconds);
	}

	public Results run(ThrowingTask first, ThrowingTask second) {
		CountDownLatch ready = new CountDownLatch(TASK_COUNT);
		CountDownLatch start = new CountDownLatch(1);
		Future<Throwable> firstFuture = executor.submit(() -> execute(first, ready, start));
		Future<Throwable> secondFuture = executor.submit(() -> execute(second, ready, start));

		awaitReady(ready, firstFuture, secondFuture);
		start.countDown();
		try {
			return new Results(
				firstFuture.get(timeoutSeconds, TimeUnit.SECONDS),
				secondFuture.get(timeoutSeconds, TimeUnit.SECONDS)
			);
		} catch (InterruptedException exception) {
			cancel(firstFuture, secondFuture);
			Thread.currentThread().interrupt();
			throw new AssertionError("concurrent scenario was interrupted", exception);
		} catch (TimeoutException exception) {
			cancel(firstFuture, secondFuture);
			throw new AssertionError("concurrent scenario timed out", exception);
		} catch (ExecutionException exception) {
			cancel(firstFuture, secondFuture);
			throw new AssertionError("concurrent scenario runner failed", exception.getCause());
		}
	}

	private Throwable execute(
		ThrowingTask task,
		CountDownLatch ready,
		CountDownLatch start
	) {
		ready.countDown();
		try {
			if (!start.await(timeoutSeconds, TimeUnit.SECONDS)) {
				return new AssertionError("concurrency start latch timed out");
			}
			return newTransaction.execute(status -> executeInTransaction(task));
		} catch (ScenarioExecutionException exception) {
			return exception.getCause();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			return new AssertionError("concurrent task was interrupted", exception);
		} catch (Throwable throwable) {
			return throwable;
		}
	}

	private Throwable executeInTransaction(ThrowingTask task) {
		try {
			task.run();
			return null;
		} catch (Throwable throwable) {
			throw new ScenarioExecutionException(throwable);
		}
	}

	private void awaitReady(
		CountDownLatch ready,
		Future<Throwable> firstFuture,
		Future<Throwable> secondFuture
	) {
		try {
			if (!ready.await(timeoutSeconds, TimeUnit.SECONDS)) {
				cancel(firstFuture, secondFuture);
				throw new AssertionError("concurrent tasks did not become ready in time");
			}
		} catch (InterruptedException exception) {
			cancel(firstFuture, secondFuture);
			Thread.currentThread().interrupt();
			throw new AssertionError("waiting for concurrent tasks was interrupted", exception);
		}
	}

	private void cancel(Future<Throwable> firstFuture, Future<Throwable> secondFuture) {
		firstFuture.cancel(true);
		secondFuture.cancel(true);
	}

	@Override
	public void close() {
		executor.shutdownNow();
		try {
			if (!executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
				throw new AssertionError("concurrent scenario executor did not terminate in time");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError("executor shutdown was interrupted", exception);
		}
	}

	@FunctionalInterface
	public interface ThrowingTask {
		void run() throws Exception;
	}

	public record Results(Throwable firstFailure, Throwable secondFailure) {
	}

	private static final class ScenarioExecutionException extends RuntimeException {

		private ScenarioExecutionException(Throwable cause) {
			super(cause);
		}
	}
}
