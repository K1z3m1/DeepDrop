package app.fluxa

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * One small shared pool for the short periodic reads (sysfs clocks, battery, ping).
 * Creating a new Thread every second costs CPU and churns memory; this reuses 2 threads
 * and lets them die after 30 s of nothing to do.
 */
object Bg {
    private val pool = ThreadPoolExecutor(
        2, 2, 30L, TimeUnit.SECONDS, LinkedBlockingQueue(),
    ) { r -> Thread(r, "fluxa-bg").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
        .apply { allowCoreThreadTimeOut(true) }

    fun run(block: () -> Unit) {
        runCatching { pool.execute(block) }
    }
}
