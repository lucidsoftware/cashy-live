package com.lucidchart.open.cashylive.util

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ThreadFactory


protected class NamedThreadFactory(prefix: String) extends ThreadFactory {
	private val counter = new AtomicInteger(0)
	private val group = new ThreadGroup(prefix)

	def newThread(r: Runnable) = {
		val count = counter.getAndIncrement()
		new Thread(group, r, prefix + "-" + count)
	}
}
