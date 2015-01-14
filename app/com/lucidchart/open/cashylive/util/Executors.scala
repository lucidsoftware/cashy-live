package com.lucidchart.open.cashylive.util

import scala.concurrent.ExecutionContext
import java.util.concurrent.{Executors => JavaExecutors}


object Executors {
	/**
	 * Creates an Executor that uses a single worker thread operating off an
	 * unbounded queue.
	 */
	def singleThreadExecutor(threadNamePrefix: String) = {
		ExecutionContext.fromExecutor(
			JavaExecutors.newSingleThreadExecutor(
				new NamedThreadFactory(threadNamePrefix)
			)
		)
	}

	/**
	 * Creates a thread pool that reuses a fixed number of threads operating
	 * off a shared unbounded queue.
	 */
	def fixedThreadPool(threadPoolSize: Int, threadNamePrefix: String) = {
		ExecutionContext.fromExecutor(
			JavaExecutors.newFixedThreadPool(
				threadPoolSize,
				new NamedThreadFactory(threadNamePrefix)
			)
		)
	}

	/**
	 * Creates a thread pool that creates new threads as needed,
	 * but will reuse previously constructed threads when they are available.
	 */
	def cachedThreadPool(threadNamePrefix: String) = {
		ExecutionContext.fromExecutor(
			JavaExecutors.newCachedThreadPool(
				new NamedThreadFactory(threadNamePrefix)
			)
		)
	}

	/**
	 * Creates a single-threaded executor that can schedule commands to run
	 * after a given delay, or to execute periodically.
	 */
	def singleThreadScheduledExecutor(threadNamePrefix: String) = {
		ExecutionContext.fromExecutor(
			JavaExecutors.newSingleThreadScheduledExecutor(
				new NamedThreadFactory(threadNamePrefix)
			)
		)
	}

	/**
	 * Creates a thread pool that can schedule commands to run after a given
	 * delay, or to execute periodically.
	 */
	def scheduledThreadPool(threadPoolSize: Int, threadNamePrefix: String) = {
		ExecutionContext.fromExecutor(
			JavaExecutors.newScheduledThreadPool(
				threadPoolSize,
				new NamedThreadFactory(threadNamePrefix)
			)
		)
	}
}
