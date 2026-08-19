/*
 * Copyright 2017 Akhil Kedia
 * This file is part of AllTrans.
 *
 * AllTrans is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AllTrans is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AllTrans. If not, see <http://www.gnu.org/licenses/>.
 *
 */

package chanhnh.alltrans

import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object ThreadPoolManager {
    private const val TAG = "AllTrans:ThreadPoolManager"

    private val CPU_COUNT = Runtime.getRuntime().availableProcessors()
    private val CORE_POOL_SIZE = maxOf(2, minOf(CPU_COUNT - 1, 4))
    private val MAX_POOL_SIZE = CPU_COUNT * 2 + 1
    private val KEEP_ALIVE_TIME = 60L

    // Main network executor for API calls
    val networkExecutor: ExecutorService = createNetworkExecutor()

    // Background executor for I/O operations
    val ioExecutor: ExecutorService = createIoExecutor()

    // Cache executor for cache operations
    val cacheExecutor: ExecutorService = createCacheExecutor()

    private fun createNetworkExecutor(): ExecutorService {
        return ThreadPoolExecutor(
            CORE_POOL_SIZE,
            MAX_POOL_SIZE,
            KEEP_ALIVE_TIME, TimeUnit.SECONDS,
            LinkedBlockingQueue(200),
            OptimizedThreadFactory("AllTrans-Network")
        ).apply {
            allowCoreThreadTimeOut(true)
        }
    }

    private fun createIoExecutor(): ExecutorService {
        return ThreadPoolExecutor(
            1,
            3,
            KEEP_ALIVE_TIME, TimeUnit.SECONDS,
            LinkedBlockingQueue(100),
            OptimizedThreadFactory("AllTrans-IO")
        ).apply {
            allowCoreThreadTimeOut(true)
        }
    }

    private fun createCacheExecutor(): ExecutorService {
        return ThreadPoolExecutor(
            1,
            2,
            KEEP_ALIVE_TIME, TimeUnit.SECONDS,
            LinkedBlockingQueue(50),
            OptimizedThreadFactory("AllTrans-Cache")
        ).apply {
            allowCoreThreadTimeOut(true)
        }
    }

    fun getExecutorStats(): String {
        fun getStats(executor: ExecutorService, name: String): String {
            return if (executor is ThreadPoolExecutor) {
                "$name: ${executor.activeCount}/${executor.poolSize} (Queue: ${executor.queue.size})"
            } else {
                "$name: N/A"
            }
        }

        return "${getStats(networkExecutor, "Network")} | ${getStats(ioExecutor, "I/O")} | ${getStats(cacheExecutor, "Cache")}"
    }

    fun shutdown() {
        Log.i(TAG, "Shutting down thread pools...")

        val executors = listOf(
            "Network" to networkExecutor,
            "I/O" to ioExecutor,
            "Cache" to cacheExecutor
        )

        executors.forEach { (name, executor) ->
            try {
                executor.shutdown()
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    Log.w(TAG, "$name executor did not terminate gracefully, forcing shutdown")
                    executor.shutdownNow()
                }
                Log.i(TAG, "$name executor shutdown complete")
            } catch (e: InterruptedException) {
                Log.w(TAG, "$name executor shutdown interrupted", e)
                executor.shutdownNow()
                Thread.currentThread().interrupt()
            }
        }

        Log.i(TAG, "All thread pools shutdown complete")
    }

    private class OptimizedThreadFactory(private val namePrefix: String) : ThreadFactory {
        private val threadNumber = AtomicInteger(1)

        override fun newThread(r: Runnable): Thread {
            return Thread(r, "$namePrefix-${threadNumber.getAndIncrement()}").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { thread, ex ->
                    Log.e(TAG, "Uncaught exception in thread ${thread.name}", ex)
                }
            }
        }
    }
}