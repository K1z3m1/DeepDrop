package com.prirai.android.lr.perf

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.util.Log

class VisualCompletenessQueue {
    private val mutex = Mutex()
    private var isReady = false
    private val queuedTasks = mutableListOf<() -> Unit>()
    
    suspend fun ready() {
        val tasksToRun = mutex.withLock {
            if (isReady) {
                return // Already ready, nothing to do
            }
            isReady = true
            queuedTasks.toList().also { queuedTasks.clear() }
        }
        
        tasksToRun.forEach { task ->
            try {
                task()
            } catch (e: Exception) {
                Log.e("VisualCompletenessQueue", "Task failed", e)
            }
        }
    }
    
    fun runIfReadyOrQueue(task: () -> Unit) {
        CoroutineScope(Dispatchers.Main).launch {
            val shouldRunNow = mutex.withLock {
                if (isReady) {
                    true
                } else {
                    queuedTasks.add(task)
                    false
                }
            }
            
            if (shouldRunNow) {
                try {
                    task()
                } catch (e: Exception) {
                    Log.e("VisualCompletenessQueue", "Task failed", e)
                }
            }
        }
    }
    
    fun isReady(): Boolean = isReady
}
