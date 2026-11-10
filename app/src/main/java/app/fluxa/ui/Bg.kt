package app.fluxa

import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * Shared background pool for the small periodic reads (clocks, battery, ping). A cached pool keeps its
 * threads alive between ticks, so nothing spawns a new Thread every second, and the threads run at
 * background priority so they never compete with the game or the UI thread.
 */
object Bg {
    private val pool = Executors.newCachedThreadPool(ThreadFactory { r ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "fluxa-bg").apply { isDaemon = true }
    })

    fun run(block: () -> Unit) {
        runCatching { pool.execute { runCatching(block) } }
    }
}
