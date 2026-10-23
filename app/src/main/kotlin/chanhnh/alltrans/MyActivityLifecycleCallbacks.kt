package chanhnh.alltrans

import android.app.Activity
import android.app.Application.ActivityLifecycleCallbacks
import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.gson.Gson
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class MyActivityLifecycleCallbacks : ActivityLifecycleCallbacks {

    companion object {
        private const val MIN_DISK_SPACE = 1024 * 1024 // 1MB
        private const val TAG = "AllTrans:Lifecycle"
    }

    private val cacheLock = ReentrantLock()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityStopped(activity: Activity) {}

    override fun onActivityDestroyed(activity: Activity) {
        if (!PreferenceList.Caching || (Alltrans.cache?.size() ?: 0) == 0) {
            val appName = activity.componentName?.className ?: "UnknownActivity"
            val reason = when {
                !PreferenceList.Caching -> "caching is disabled"
                Alltrans.cache == null -> "cache object is null"
                else -> "cache is empty"
            }
            Utils.debugLog("$TAG: onActivityDestroyed for $appName: Cache not saved because $reason.")
            return
        }

        val appContext = activity.applicationContext ?: run {
            Utils.debugLog("$TAG: ApplicationContext is null for ${activity.componentName?.className}. Cannot save cache.")
            return
        }
        val packageName = appContext.packageName

        // Never write the cache file on the main thread. Activity destruction is
        // frequent and the cache can contain up to 200 strings.
        try {
            ThreadPoolManager.cacheExecutor.execute {
                saveCacheToDisk(appContext, packageName)
            }
        } catch (e: RejectedExecutionException) {
            Utils.debugLog("$TAG: Cache executor rejected save task for $packageName: ${e.message}")
        }
    }

    @Synchronized
    private fun saveCacheToDisk(appContext: Context, packageName: String) {
        Utils.debugLog("$TAG: Saving cache for $packageName in background.")

        Alltrans.cacheAccess.acquireUninterruptibly()
        var tempFile: File? = null

        try {
            val cache = Alltrans.cache ?: run {
                Utils.debugLog("$TAG: Cache is null for $packageName, nothing to save.")
                return
            }

            if (cache.size() == 0) {
                Utils.debugLog("$TAG: Cache is empty for $packageName, nothing to save.")
                return
            }

            Utils.debugLog("$TAG: Cache has ${cache.size()} items for $packageName. Preparing to write.")

            val cacheDir = appContext.cacheDir
            tempFile = File(cacheDir, "AllTransCache.tmp")
            val finalFile = File(appContext.filesDir, "AllTransCache")

            // File.freeSpace returns 0 for a file that does not exist yet, so
            // query the existing cache directory instead of tempFile.
            val freeSpace = cacheDir.freeSpace
            if (freeSpace < MIN_DISK_SPACE) {
                Utils.debugLog("$TAG: Low disk space ($freeSpace bytes) for $packageName, aborting cache save.")
                return
            }

            try {
                val snapshotMap = cacheLock.withLock {
                    cache.snapshot()
                }

                FileOutputStream(tempFile).use { fileOut ->
                    val json = Gson().toJson(snapshotMap)
                    fileOut.write(json.toByteArray())
                    Utils.debugLog("$TAG: Cache snapshot written for $packageName. Size: ${json.length}")
                }
            } catch (e: IOException) {
                Utils.debugLog("$TAG: IOException writing temp cache for $packageName: ${Log.getStackTraceString(e)}")
                return
            }

            if (finalFile.exists() && !finalFile.delete()) {
                Utils.debugLog("$TAG: Failed to delete existing final cache file for $packageName.")
            }

            if (tempFile.renameTo(finalFile)) {
                Utils.debugLog("$TAG: Cache saved via rename for $packageName. Size: ${finalFile.length()}.")
            } else {
                Utils.debugLog("$TAG: Rename failed for $packageName. Attempting copy.")
                copyFile(tempFile, finalFile, packageName, cache.size())
            }
        } catch (t: Throwable) {
            Utils.debugLog("$TAG: Unexpected error saving cache for $packageName: ${Log.getStackTraceString(t)}")
        } finally {
            if (Alltrans.cacheAccess.availablePermits() == 0) {
                Alltrans.cacheAccess.release()
            }
            tempFile?.let {
                if (it.exists()) {
                    val deleted = it.delete()
                    Utils.debugLog("$TAG: Cleanup temp cache file for $packageName deleted: $deleted")
                }
            }
        }
    }

    private fun copyFile(source: File, dest: File, packageName: String, originalCacheSize: Int) {
        try {
            val sourceSize = source.length()
            var bytesCopied: Long = 0

            FileInputStream(source).use { inputStream ->
                FileOutputStream(dest).use { outputStream ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        bytesCopied += bytesRead
                    }
                    Utils.debugLog("$TAG: Bytes copied from temp to final for $packageName: $bytesCopied. Original source size: $sourceSize")
                }
            }

            if (bytesCopied == sourceSize && dest.exists() && dest.length() == sourceSize) {
                Utils.debugLog("$TAG: Cache saved via copy for $packageName. Size: ${dest.length()}. Cache items: $originalCacheSize")
                val deletedTemp = source.delete()
                Utils.debugLog("$TAG: Temp cache file for $packageName deleted after copy: $deletedTemp")
            } else {
                Utils.debugLog("$TAG: Copy failed or incomplete for $packageName. Source size: $sourceSize, copied: $bytesCopied, dest exists: ${dest.exists()}, dest size: ${dest.length()}.")
                if (dest.exists()) dest.delete()
            }
        } catch (e: IOException) {
            Utils.debugLog("$TAG: IOException copying cache for $packageName from '${source.path}' to '${dest.path}': ${Log.getStackTraceString(e)}")
            if (dest.exists()) dest.delete()
        }
    }
}
