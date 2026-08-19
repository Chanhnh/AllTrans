package chanhnh.alltrans

import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.net.toUri
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.UnsupportedEncodingException
import java.net.URLEncoder
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class GetTranslateToken {
    var getTranslate: GetTranslate? = null

    fun doAll() {
        val callback = getTranslate ?: run {
            Log.e(TAG, "GetTranslate is null. Aborting.")
            return
        }

        val text = callback.stringToBeTrans
        val spanPayload = callback.spanTranslationPayload

        // Request deduplication for single-text mode
        if (spanPayload == null && !text.isNullOrEmpty()) {
            val deduplicationKey = createDeduplicationKey(text)
            val existingFuture = activeRequests[deduplicationKey]
            if (existingFuture != null && !existingFuture.isDone) {
                Utils.debugLog("$TAG: Deduplicating request for: [$text]")
                existingFuture.thenAccept { result ->
                    handleDuplicateResult(callback, result)
                }
                return
            }
        }

        val task = TranslationTask(callback)
        val future = CompletableFuture.supplyAsync({ task.call() }, googleQueryExecutor)

        if (spanPayload == null && !text.isNullOrEmpty()) {
            val deduplicationKey = createDeduplicationKey(text)
            activeRequests[deduplicationKey] = future
            future.whenComplete { _, _ ->
                activeRequests.remove(deduplicationKey)
            }
        }
    }

    private fun createDeduplicationKey(text: String): String {
        return "${PreferenceList.TranslateFromLanguage}-${PreferenceList.TranslateToLanguage}-${text.hashCode()}"
    }

    private fun handleDuplicateResult(callback: GetTranslate, result: String?) {
        val mockRequest = Request.Builder().url("https://mock.deduplicated.call").build()
        val mockCall = createMockHttpClient().newCall(mockRequest)
        val response = Response.Builder()
            .request(mockRequest)
            .protocol(Protocol.HTTP_2)
            .code(200)
            .message("OK (Deduplicated)")
            .body((result ?: "").toResponseBody(null))
            .build()

        Handler(Looper.getMainLooper()).post {
            callback.onResponse(mockCall, response)
        }
    }

    private fun createMockHttpClient(): okhttp3.OkHttpClient {
        return okhttp3.OkHttpClient.Builder().build()
    }

    private class TranslationTask(private val callback: GetTranslate?) : java.util.concurrent.Callable<String?> {
        override fun call(): String? {
            val token = GetTranslateToken()
            token.getTranslate = callback
            token.doInBackground()
            return null
        }
    }

    private fun createMockCall(): okhttp3.Call {
        val mockRequest = Request.Builder().url("https://mock.error.call").build()
        return createMockHttpClient().newCall(mockRequest)
    }

    private fun handleTranslationFailure(reason: String?, exception: Throwable?) {
        Log.e(TAG, "Translation failed: $reason for text: '${getTranslate?.stringToBeTrans ?: "N/A"}'", exception)
        getTranslate?.let {
            val ioEx = if (exception is java.io.IOException) exception else java.io.IOException(reason, exception)
            it.onFailure(createMockCall(), ioEx)
        } ?: Log.e(TAG, "GetTranslate is null in handleTranslationFailure.")
    }

    private fun getCachedTranslation(text: String): String? {
        if (!PreferenceList.Caching) return null

        Alltrans.cacheAccess.acquireUninterruptibly()
        return try {
            when (val cached = Alltrans.cache?.get(text)) {
                null -> null
                SetTextHookHandler.NO_TRANSLATION_MARKER -> text
                else -> cached
            }
        } finally {
            if (Alltrans.cacheAccess.availablePermits() == 0) {
                Alltrans.cacheAccess.release()
            }
        }
    }

    private fun cacheTranslation(text: String, translated: String) {
        if (!PreferenceList.Caching || text == translated) return

        Alltrans.cacheAccess.acquireUninterruptibly()
        try {
            Alltrans.cache?.put(text, translated)
        } finally {
            if (Alltrans.cacheAccess.availablePermits() == 0) {
                Alltrans.cacheAccess.release()
            }
        }
    }

    private fun cacheAsNoTranslation(text: String) {
        if (!PreferenceList.Caching) return

        Alltrans.cacheAccess.acquireUninterruptibly()
        try {
            Alltrans.cache?.put(text, SetTextHookHandler.NO_TRANSLATION_MARKER)
        } finally {
            if (Alltrans.cacheAccess.availablePermits() == 0) {
                Alltrans.cacheAccess.release()
            }
        }
    }

    private fun queryProvider(provider: String, text: String, fromLang: String, toLang: String): String? {
        return when (provider) {
            "edge" -> queryEdgeProvider(text, fromLang, toLang)
            PreferenceList.VIETPHRASE_PROVIDER -> queryVietPhraseProvider(text, fromLang, toLang)
            else -> queryGoogleProvider(text, fromLang, toLang)
        }
    }

    private fun translateSegmentedText(
        payload: SpanTranslationPayload,
        provider: String,
        fromLang: String,
        toLang: String,
        forceCacheResult: Boolean
    ): List<String> {
        val translatedSegments = ArrayList<String>(payload.segments.size)

        payload.segments.forEach { segment ->
            val sourceText = segment.sourceText
            val translatedText = when {
                sourceText.isEmpty() -> sourceText
                SetTextHookHandler.shouldSkipTranslation(sourceText) -> {
                    if (forceCacheResult) ViewTextCache.markNoTranslation(sourceText)
                    cacheAsNoTranslation(sourceText)
                    sourceText
                }
                else -> {
                    (if (forceCacheResult) ViewTextCache.get(sourceText) else null)
                        ?: getCachedTranslation(sourceText)
                        ?: (queryProvider(provider, sourceText, fromLang, toLang) ?: sourceText).also { translated ->
                            if (translated == sourceText) {
                                if (forceCacheResult) ViewTextCache.markNoTranslation(sourceText)
                                cacheAsNoTranslation(sourceText)
                            } else if (translated.isNotEmpty()) {
                                if (forceCacheResult) ViewTextCache.put(sourceText, translated)
                                cacheTranslation(sourceText, translated)
                            }
                        }
                }
            }

            translatedSegments.add(translatedText)
        }

        return translatedSegments
    }

    private fun queryGoogleProvider(text: String?, fromLang: String?, toLang: String?): String? {
        val context = Alltrans.context
        if (text.isNullOrEmpty() || context == null) {
            if (context == null) Log.e(TAG, "Static context is null in queryGoogleProvider.")
            return text
        }

        val directUri: Uri
        val proxyUri: Uri
        try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val queryParams =
                "?from=${URLEncoder.encode(fromLang ?: "auto", "UTF-8")}&to=${URLEncoder.encode(toLang ?: "en", "UTF-8")}&text=$encodedText"
            directUri = "content://chanhnh.alltrans.GtransProvider$queryParams".toUri()
            proxyUri = "content://settings/system/alltransProxyProviderURI/chanhnh.alltrans.GtransProvider$queryParams".toUri()
        } catch (e: UnsupportedEncodingException) {
            Log.e(TAG, "UTF-8 not supported?!", e)
            return text
        }

        val resolver = context.contentResolver
        var translatedText: String? = null
        var cursor: Cursor? = null
        val identity = Binder.clearCallingIdentity()

        try {
            // Try proxy query first
            try {
                Utils.debugLog("$TAG: Attempting proxy query: $proxyUri")
                cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val queryArgs = Bundle().apply {
                        putInt("android:queryArgSelectionBehavior", 0)
                        putBoolean("android:asyncQuery", true)
                        putInt("android:honorsExtraArgs", 1)
                    }
                    resolver.query(proxyUri, arrayOf("translate"), queryArgs, null)
                } else {
                    resolver.query(proxyUri, arrayOf("translate"), null, null, null)
                }

                if (cursor?.moveToFirst() == true) {
                    val columnIndex = cursor.getColumnIndex("translate")
                    if (columnIndex >= 0) {
                        translatedText = cursor.getString(columnIndex)
                        Utils.debugLog("$TAG: Proxy query successful.")
                    }
                }
            } catch (e: Exception) {
                Utils.debugLog("$TAG: Proxy query exception: ${e.message}")
            } finally {
                cursor?.close()
                cursor = null
            }

            // Fallback to direct query
            if (translatedText == null) {
                try {
                    Utils.debugLog("$TAG: Attempting direct query: $directUri")
                    cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val queryArgs = Bundle().apply {
                            putInt("android:queryArgSelectionBehavior", 0)
                            putBoolean("android:asyncQuery", true)
                            putInt("android:honorsExtraArgs", 1)
                        }
                        resolver.query(directUri, arrayOf("translate"), queryArgs, null)
                    } else {
                        resolver.query(directUri, arrayOf("translate"), null, null, null)
                    }

                    if (cursor?.moveToFirst() == true) {
                        val columnIndex = cursor.getColumnIndex("translate")
                        if (columnIndex >= 0) {
                            translatedText = cursor.getString(columnIndex)
                            Utils.debugLog("$TAG: Direct query successful.")
                        }
                    }
                } catch (e: Exception) {
                    Utils.debugLog("$TAG: Direct query exception: ${Log.getStackTraceString(e)}")
                } finally {
                    cursor?.close()
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }

        return if (!translatedText.isNullOrEmpty()) {
            Utils.debugLog("$TAG: Translation successful: [$text] -> [$translatedText]")
            translatedText
        } else {
            Utils.debugLog("$TAG: Translation failed or returned empty for: [$text]")
            text
        }
    }

    private fun queryEdgeProvider(text: String?, fromLang: String?, toLang: String?): String? {
        val context = Alltrans.context
        if (text.isNullOrEmpty() || context == null) {
            return text
        }

        val directUri: Uri
        val proxyUri: Uri
        try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val queryParams =
                "?from=${URLEncoder.encode(fromLang ?: "auto", "UTF-8")}&to=${URLEncoder.encode(toLang ?: "en", "UTF-8")}&text=$encodedText"
            directUri = "content://chanhnh.alltrans.EdgeProvider$queryParams".toUri()
            proxyUri = "content://settings/system/alltransProxyProviderURI/chanhnh.alltrans.EdgeProvider$queryParams".toUri()
        } catch (e: UnsupportedEncodingException) {
            Log.e(TAG, "UTF-8 not supported?!", e)
            return text
        }

        val resolver = context.contentResolver
        var translatedText: String? = null
        var cursor: Cursor? = null
        val identity = Binder.clearCallingIdentity()

        try {
            // Try proxy query first
            try {
                Utils.debugLog("$TAG: Attempting Edge proxy query: $proxyUri")
                cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val queryArgs = Bundle().apply {
                        putInt("android:queryArgSelectionBehavior", 0)
                        putBoolean("android:asyncQuery", true)
                        putInt("android:honorsExtraArgs", 1)
                    }
                    resolver.query(proxyUri, arrayOf("translate"), queryArgs, null)
                } else {
                    resolver.query(proxyUri, arrayOf("translate"), null, null, null)
                }

                if (cursor?.moveToFirst() == true) {
                    val columnIndex = cursor.getColumnIndex("translate")
                    if (columnIndex >= 0) {
                        translatedText = cursor.getString(columnIndex)
                        Utils.debugLog("$TAG: Edge proxy query successful.")
                    }
                }
            } catch (e: Exception) {
                Utils.debugLog("$TAG: Edge proxy query exception: ${e.message}")
            } finally {
                cursor?.close()
                cursor = null
            }

            // Fallback to direct query
            if (translatedText == null) {
                try {
                    Utils.debugLog("$TAG: Attempting Edge direct query: $directUri")
                    cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val queryArgs = Bundle().apply {
                            putInt("android:queryArgSelectionBehavior", 0)
                            putBoolean("android:asyncQuery", true)
                            putInt("android:honorsExtraArgs", 1)
                        }
                        resolver.query(directUri, arrayOf("translate"), queryArgs, null)
                    } else {
                        resolver.query(directUri, arrayOf("translate"), null, null, null)
                    }

                    if (cursor?.moveToFirst() == true) {
                        val columnIndex = cursor.getColumnIndex("translate")
                        if (columnIndex >= 0) {
                            translatedText = cursor.getString(columnIndex)
                        Utils.debugLog("$TAG: Edge direct query successful.")
                        }
                    }
                } catch (e: Exception) {
                    Utils.debugLog("$TAG: Edge direct query exception: ${Log.getStackTraceString(e)}")
                } finally {
                    cursor?.close()
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }

        return if (!translatedText.isNullOrEmpty()) {
            translatedText
        } else {
            text
        }
    }

    private fun queryVietPhraseProvider(text: String?, fromLang: String?, toLang: String?): String? {
        val context = Alltrans.context
        if (text.isNullOrEmpty() || context == null) {
            return text
        }

        val directUri: Uri
        val proxyUri: Uri
        try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val queryParams =
                "?from=${URLEncoder.encode(fromLang ?: PreferenceList.VIETPHRASE_SOURCE_LANGUAGE, "UTF-8")}&to=${URLEncoder.encode(toLang ?: PreferenceList.VIETPHRASE_TARGET_LANGUAGE, "UTF-8")}&text=$encodedText"
            directUri = "content://chanhnh.alltrans.VietPhraseProvider$queryParams".toUri()
            proxyUri = "content://settings/system/alltransProxyProviderURI/chanhnh.alltrans.VietPhraseProvider$queryParams".toUri()
        } catch (e: UnsupportedEncodingException) {
            Log.e(TAG, "UTF-8 not supported?!", e)
            return text
        }

        val resolver = context.contentResolver
        var translatedText: String? = null
        var cursor: Cursor? = null
        val identity = Binder.clearCallingIdentity()

        try {
            try {
                Utils.debugLog("$TAG: Attempting VietPhrase proxy query: $proxyUri")
                cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val queryArgs = Bundle().apply {
                        putInt("android:queryArgSelectionBehavior", 0)
                        putBoolean("android:asyncQuery", true)
                        putInt("android:honorsExtraArgs", 1)
                    }
                    resolver.query(proxyUri, arrayOf("translate"), queryArgs, null)
                } else {
                    resolver.query(proxyUri, arrayOf("translate"), null, null, null)
                }

                if (cursor?.moveToFirst() == true) {
                    val columnIndex = cursor.getColumnIndex("translate")
                    if (columnIndex >= 0) {
                        translatedText = cursor.getString(columnIndex)
                        Utils.debugLog("$TAG: VietPhrase proxy query successful.")
                    }
                }
            } catch (e: Exception) {
                Utils.debugLog("$TAG: VietPhrase proxy query exception: ${e.message}")
            } finally {
                cursor?.close()
                cursor = null
            }

            if (translatedText == null) {
                try {
                    Utils.debugLog("$TAG: Attempting VietPhrase direct query: $directUri")
                    cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val queryArgs = Bundle().apply {
                            putInt("android:queryArgSelectionBehavior", 0)
                            putBoolean("android:asyncQuery", true)
                            putInt("android:honorsExtraArgs", 1)
                        }
                        resolver.query(directUri, arrayOf("translate"), queryArgs, null)
                    } else {
                        resolver.query(directUri, arrayOf("translate"), null, null, null)
                    }

                    if (cursor?.moveToFirst() == true) {
                        val columnIndex = cursor.getColumnIndex("translate")
                        if (columnIndex >= 0) {
                            translatedText = cursor.getString(columnIndex)
                            Utils.debugLog("$TAG: VietPhrase direct query successful.")
                        }
                    }
                } catch (e: Exception) {
                    Utils.debugLog("$TAG: VietPhrase direct query exception: ${Log.getStackTraceString(e)}")
                } finally {
                    cursor?.close()
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }

        return if (!translatedText.isNullOrEmpty()) {
            translatedText
        } else {
            text
        }
    }

    private fun doInBackground() {
        val callback = getTranslate ?: run {
            Log.e(TAG, "GetTranslate is null. Aborting.")
            return
        }

        val textToTranslate = callback.stringToBeTrans ?: run {
            Log.e(TAG, "String to be translated is null. Aborting.")
            return
        }

        if (Alltrans.context == null) {
            Log.e(TAG, "Static context is null. Aborting.")
            handleTranslationFailure("Static context is null", null)
            return
        }

        try {
            val fromLang = PreferenceList.TranslateFromLanguage ?: "auto"
            val toLang = PreferenceList.TranslateToLanguage ?: "en"

            // Skip translation if source == target (non-auto)
            if (fromLang != "auto" && fromLang == toLang) {
                if (callback.forceCacheResult) {
                    ViewTextCache.markNoTranslation(textToTranslate)
                }
                Log.i(TAG, "Skipping: source and target languages identical ($fromLang).")
                val mockRequest = Request.Builder().url("https://mock.identical.language.skip").build()
                val mockCall = createMockHttpClient().newCall(mockRequest)
                val responseBody = textToTranslate.toResponseBody("text/plain".toMediaTypeOrNull())
                val mockResponse = Response.Builder()
                    .request(mockRequest)
                    .protocol(Protocol.HTTP_2)
                    .code(200)
                    .message("OK (Skipped - identical languages)")
                    .body(responseBody)
                    .build()
                Handler(Looper.getMainLooper()).post {
                    callback.onResponse(mockCall, mockResponse)
                }
                return
            }

            val provider = PreferenceList.TranslatorProvider ?: "g"
            Utils.debugLog("$TAG: doInBackground - Provider: $provider, text: [$textToTranslate]")

            var result: String? = textToTranslate
            try {
                val spanPayload = callback.spanTranslationPayload
                if (spanPayload != null) {
                    val translatedSegments = translateSegmentedText(
                        spanPayload,
                        provider,
                        fromLang,
                        toLang,
                        callback.forceCacheResult
                    )
                    callback.translatedSegments = translatedSegments
                    result = translatedSegments.joinToString("")
                } else {
                    result = queryProvider(provider, textToTranslate, fromLang, toLang)
                    if (callback.forceCacheResult && result != null) {
                        ViewTextCache.put(textToTranslate, result)
                    }
                }
            } catch (t: Throwable) {
                handleTranslationFailure("Error executing $provider Provider query", t)
                return
            }

            val mockRequest = Request.Builder().url("https://mock.google.translate.query").build()
            val mockCall = createMockHttpClient().newCall(mockRequest)
            val response = Response.Builder()
                .request(mockRequest)
                .code(200).message("OK (from Provider Query)")
                .protocol(Protocol.HTTP_2)
                .body((result ?: textToTranslate).toResponseBody(null))
                .build()

            Handler(Looper.getMainLooper()).post {
                getTranslate?.let { currentCallback ->
                    try {
                        currentCallback.onResponse(mockCall, response)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Error in onResponse callback for: [$textToTranslate]", t)
                        handleTranslationFailure("Error in onResponse callback", t)
                    }
                } ?: Log.w(TAG, "getTranslate became null before executing handler for: [$textToTranslate].")
            }
        } catch (e: Exception) {
            handleTranslationFailure("Error preparing translation request", e)
        }
    }

    companion object {
        private val CPU_COUNT = Runtime.getRuntime().availableProcessors()
        private val CORE_POOL_SIZE = maxOf(2, minOf(CPU_COUNT - 1, 4))
        private val MAX_POOL_SIZE = CPU_COUNT * 2 + 1
        private val KEEP_ALIVE_TIME = 60L

        private val googleQueryExecutor: ExecutorService = ThreadPoolExecutor(
            CORE_POOL_SIZE,
            MAX_POOL_SIZE,
            KEEP_ALIVE_TIME, TimeUnit.SECONDS,
            LinkedBlockingQueue(200),
            OptimizedThreadFactory("AllTrans-Google")
        ).apply {
            allowCoreThreadTimeOut(true)
        }

        private val ioExecutor: ExecutorService = ThreadPoolExecutor(
            1, 3,
            KEEP_ALIVE_TIME, TimeUnit.SECONDS,
            LinkedBlockingQueue(50),
            OptimizedThreadFactory("AllTrans-IO")
        ).apply {
            allowCoreThreadTimeOut(true)
        }

        private val activeRequests = ConcurrentHashMap<String, CompletableFuture<String?>>()

        private const val TAG = "AllTrans:GetTranslateToken"

        fun submitIoTask(task: Runnable) {
            ioExecutor.submit(task)
        }

        fun <T> submitIoTask(task: java.util.concurrent.Callable<T>): CompletableFuture<T> {
            return CompletableFuture.supplyAsync({
                try {
                    task.call()
                } catch (e: Exception) {
                    throw RuntimeException(e)
                }
            }, ioExecutor)
        }

        fun shutdown() {
            Utils.debugLog("$TAG: Shutting down thread pools...")
            listOf(googleQueryExecutor, ioExecutor).forEach { executor ->
                executor.shutdown()
                try {
                    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                        executor.shutdownNow()
                    }
                } catch (e: InterruptedException) {
                    executor.shutdownNow()
                    Thread.currentThread().interrupt()
                }
            }
            activeRequests.clear()
            Utils.debugLog("$TAG: Thread pools shutdown complete.")
        }
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
