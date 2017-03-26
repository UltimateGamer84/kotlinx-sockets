package kotlinx.sockets

import kotlinx.coroutines.experimental.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*

private val group = ThreadGroup("io-pool-group")
private val counter = AtomicInteger()
private val cpuCount = Runtime.getRuntime().availableProcessors()

private val ioPool = ThreadPoolExecutor(cpuCount + 1, cpuCount * 2 + 1, 10L, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(100000), { r ->
    Thread(group, r, group.name + counter.incrementAndGet()).apply {
        isDaemon = true
    }
})

val ioCoroutineDispatcher = ioPool.asCoroutineDispatcher()

