package chanhnh.alltrans

import android.app.Activity
import android.app.Application.ActivityLifecycleCallbacks
import android.os.Bundle
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import com.google.gson.Gson
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
        if (PreferenceList.Caching && (Alltrans.cache?.size() ?: 0) > 0) {
            val appContext = activity.applicationContext
            if (appContext == null) {
                Utils.debugLog("$TAG: ApplicationContext is null for ${activity.componentName?.className}. Cannot save cache.")
                return
            }

            val packageName = appContext.packageName ?: "unknown_package"
            Utils.debugLog("$TAG: onActivityDestroyed for $packageName: Attempting to save cache.")

            Alltrans.cacheAccess.acquireUninterruptibly()
            var tempFile: File? = null

            try {
                val cache = Alltrans.cache ?: run {
                    Utils.debugLog("$TAG: onActivityDestroyed for $packageName: Cache is null, nothing to save.")
                    return
                }

                if (cache.size() == 0) {
                    Utils.debugLog("$TAG: onActivityDestroyed for $packageName: Cache is empty, nothing to save.")
                    return
                }

                Utils.debugLog("$TAG: onActivityDestroyed for $packageName: Cache has ${cache.size()} items. Preparing to write.")

                tempFile = File(appContext.cacheDir, "AllTransCache.tmp")
                val finalFile = File(appContext.filesDir, "AllTransCache")

                Utils.debugLog("$TAG: onActivityDestroyed for $packageName: Temp file: ${tempFile.absolutePath}, Final file: ${finalFile.absolutePath}")

                // Verificar espaço em disco
                if (tempFile.freeSpace < MIN_DISK_SPACE) {
                    Utils.debugLog("$TAG: onActivityDestroyed for $packageName: Low disk space (${tempFile.freeSpace} bytes), aborting cache save.")
                    return
                }

                try {
                    Utils.debugLog("$TAG: onActivityDestroyed for $packageName: Temp file exists before write: ${tempFile.exists()}")

                    // Obter snapshot do cache de forma thread-safe
                    val snapshotMap = cacheLock.withLock {
                        cache.snapshot()
                    }

                    FileOutputStream(tempFile).use { fileOut ->
                        // Usar Gson para serialização mais eficiente
                        val json = Gson().toJson(snapshotMap)
                        fileOut.write(json.toByteArray())
                        Utils.debugLog("$TAG: onActivityDestroyed for $packageName: LruCache snapshot written to temp file '${tempFile.name}'. Size: ${json.length}")
                    }
                    Utils.debugLog("$TAG: onActivityDestroyed for $packageName: Temp file exists after write: ${tempFile.exists()}, size: ${tempFile.length()}")

                } catch (e: IOException) {
                    Utils.debugLog("$TAG: onActivityDestroyed for $packageName: IOException during temp file write: ${Log.getStackTraceString(e)}")
                    return
                }

                Utils.debugLog("$TAG: onActivityDestroyed for $packageName: Final file exists before potential delete: ${finalFile.exists()}")
                if (finalFile.exists()) {
                    val deletedOldFinal = finalFile.delete()
                    Utils.debugLog("$TAG: onActivityDestroyed for $packageName: Deletion of existing finalFile ('${finalFile.name}') successful: $deletedOldFinal")
                    if (!deletedOldFinal) {
                        Utils.debugLog("$TAG: onActivityDestroyed for $packageName: WARNING - Failed to delete existing final cache file. Rename/Copy might fail or lead to issues.")
                    }
                }

                Utils.debugLog("$TAG: onActivityDestroyed for $packageName: Attempting to rename '${tempFile.name}' to '${finalFile.name}'")
                if (tempFile.renameTo(finalFile)) {
                    Utils.debugLog("$TAG: onActivityDestroyed for $packageName: Cache saved successfully via rename. Final file size: ${finalFile.length()}.")
                } else {
                    Utils.debugLog("$TAG: onActivityDestroyed for $packageName: Rename failed. Attempting copy from '${tempFile.name}' to '${finalFile.name}'.")
                    copyFile(tempFile, finalFile, packageName, cache.size())
                }

            } catch (t: Throwable) {
                Utils.debugLog("$TAG: onActivityDestroyed for $packageName: Unexpected error saving cache: ${Log.getStackTraceString(t)}")
            } finally {
                if (Alltrans.cacheAccess.availablePermits() == 0) {
                    Alltrans.cacheAccess.release()
                    Utils.debugLog("$TAG: onActivityDestroyed for $packageName: Cache access semaphore released.")
                }
                tempFile?.let {
                    if (it.exists()) {
                        val deleted = it.delete()
                        Utils.debugLog("$TAG: onActivityDestroyed for $packageName: Cleanup - Temp file '${it.name}' deleted: $deleted")
                    }
                }
            }
        } else {
            val appName = activity.componentName?.className ?: "UnknownActivity"
            val reason = when {
                !PreferenceList.Caching -> "caching is disabled"
                (Alltrans.cache == null || Alltrans.cache?.size() == 0) -> "cache object is null or empty"
                else -> "unknown reason (cache not empty but main condition failed)"
            }
            Utils.debugLog("$TAG: onActivityDestroyed for $appName: Cache not saved because $reason.")
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
                    Utils.debugLog("$TAG: onActivityDestroyed for $packageName: Bytes copied from temp to final: $bytesCopied. Original source size: $sourceSize")
                }
            }

            if (bytesCopied == sourceSize && dest.exists() && dest.length() == sourceSize) {
                Utils.debugLog("$TAG: onActivityDestroyed for $packageName: Cache saved successfully via copy. Final file size: ${dest.length()}. Cache items: $originalCacheSize")
                val deletedTemp = source.delete()
                Utils.debugLog("$TAG: onActivityDestroyed for $packageName: Temp file '${source.name}' deleted after copy: $deletedTemp")
            } else {
                Utils.debugLog("$TAG: onActivityDestroyed for $packageName: WARNING - Copy operation failed or was incomplete. Source size: $sourceSize, Bytes copied: $bytesCopied, Dest exists: ${dest.exists()}, Dest size: ${dest.length()}. Temp file not deleted.")
                if (dest.exists()) dest.delete()
            }

        } catch (e: IOException) {
            Utils.debugLog("$TAG: onActivityDestroyed for $packageName: IOException during copyFile from '${source.path}' to '${dest.path}': ${Log.getStackTraceString(e)}")
            if (dest.exists()) dest.delete()
        }
    }
}