package app.fluxa

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Shared background pool for the small periodic reads (clocks, battery, ping). A cached pool keeps its
 * threads alive between ticks, so nothing spawns a new Thread every second, and the threads run at
 * background priority so they never compete with the game or the UI thread.
 */
object Bg {
    // At most 3 threads (each costs ~1 MB of stack); extra ticks queue instead of spawning more. Idle threads
    // are released after 20 s so the pool uses no RAM while the app sits unused.
    private val pool = ThreadPoolExecutor(
        3, 3, 20L, TimeUnit.SECONDS, LinkedBlockingQueue(),
        ThreadFactory { r ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                r.run()
            }, "fluxa-bg").apply { isDaemon = true }
        },
    ).apply { allowCoreThreadTimeOut(true) }

    fun run(block: () -> Unit) {
        runCatching { pool.execute { runCatching(block) } }
    }
}
