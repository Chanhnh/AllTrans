package chanhnh.alltrans

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class GetTranslate : Callback {
    private val TAG = "AllTrans:GetTranslate"
    var stringToBeTrans: String? = null
    var originalCallable: OriginalCallable? = null
    var canCallOriginal: Boolean = false
    var userData: Any? = null
    var pendingCompositeKey: Int = 0
    var spanTranslationPayload: SpanTranslationPayload? = null
    var translatedSegments: List<String>? = null
    var forceCacheResult: Boolean = false

    private val responseHandled = AtomicBoolean(false)
    private val failureHandled = AtomicBoolean(false)

    override fun onResponse(call: Call, response: Response) {
        if (!responseHandled.compareAndSet(false, true)) {
            Utils.debugLog("$TAG: Response already handled for: ${call.request().url}")
            response.body?.close()
            return
        }

        val startTime = System.currentTimeMillis()
        var translatedString: String? = stringToBeTrans

        try {
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "null"
                Utils.debugLog("$TAG: HTTP error ${response.code}: $errorBody")
                translatedString = stringToBeTrans
            } else {
                val responseBodyString = response.body!!.string()
                translatedString = processResponse(responseBodyString)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Unexpected error processing translation response: ${Log.getStackTraceString(e)}")
            translatedString = stringToBeTrans
        } finally {
            response.body?.close()
            val finalString: String = translatedString ?: stringToBeTrans ?: ""
            val processingTime = System.currentTimeMillis() - startTime
            Utils.debugLog("$TAG: Response processed in ${processingTime}ms: [$finalString]")
            triggerCallback(finalString, stringToBeTrans, userData, originalCallable, canCallOriginal, pendingCompositeKey)
        }
    }

    private fun processResponse(responseBodyString: String): String? {
        val localOriginalString = stringToBeTrans
        Utils.debugLog("$TAG: Translation response for [$localOriginalString]: $responseBodyString")

        // The response from google provider content query or the inline API is already the plain translated text.
        // When coming through the mock response path (GtransProvider), the body is already the translated string.
        val translated = responseBodyString.ifEmpty { localOriginalString }

        val unescaped = Utils.XMLUnescape(translated.orEmpty()) ?: localOriginalString

        if (spanTranslationPayload == null && localOriginalString != null && unescaped != null) {
            GetTranslateToken.submitIoTask {
                cacheTranslation(localOriginalString, unescaped)
            }
        }

        return unescaped
    }

    private fun cacheTranslation(original: String?, translated: String?) {
        if (original == null || translated == null || !PreferenceList.Caching) {
            if (!PreferenceList.Caching) Utils.debugLog("$TAG: Caching disabled, skipping.")
            return
        }
        if (translated == original) {
            Utils.debugLog("$TAG: Skipping cache for identical translation: [$original]")
            return
        }

        Alltrans.cacheAccess.acquireUninterruptibly()
        try {
            Alltrans.cache?.let {
                Utils.debugLog("$TAG: Caching: [$original] -> [$translated]")
                it.put(original, translated)
            } ?: Utils.debugLog("$TAG: Cache is null.")
        } finally {
            if (Alltrans.cacheAccess.availablePermits() == 0) {
                Alltrans.cacheAccess.release()
            }
        }
    }

    private fun triggerCallback(
        finalString: String,
        originalString: String?,
        currentUserData: Any?,
        currentOriginalCallable: OriginalCallable?,
        currentCanCallOriginal: Boolean,
        keyToRemove: Int
    ) {
        val delay = PreferenceList.Delay.toLong()
        val task = Runnable {
            try {
                if (currentUserData is TextView) {
                    val tv = currentUserData
                    val pendingText = tv.getTag(Alltrans.ALLTRANS_PENDING_TRANSLATION_TAG_KEY) as? String
                    val pendingSpanPayload = tv.getTag(Alltrans.ALLTRANS_PENDING_SPAN_PAYLOAD_TAG_KEY) as? SpanTranslationPayload

                    if (pendingText != originalString) {
                        Utils.debugLog("$TAG: Discarding stale translation for (${tv.hashCode()}). Expected '$pendingText', got '$originalString'.")
                        return@Runnable
                    }

                    val translatedText: CharSequence = if (pendingSpanPayload != null && pendingSpanPayload.requestKey == originalString) {
                        pendingSpanPayload.rebuildTranslatedCharSequence(translatedSegments ?: emptyList())
                    } else {
                        finalString
                    }

                    if (translatedText.toString() != originalString || !tv.text.toString().equals(translatedText.toString())) {
                        Utils.debugLog("$TAG: Updating TextView (${tv.hashCode()}) key ($keyToRemove): [${translatedText.toString()}]")
                        tv.setTag(Alltrans.ALLTRANS_TRANSLATION_APPLIED_TAG_KEY, true)
                        tv.text = translatedText
                    } else {
                        Utils.debugLog("$TAG: Skipping TextView update (${tv.hashCode()}) key ($keyToRemove) - same text.")
                    }
                    tv.setTag(Alltrans.ALLTRANS_PENDING_TRANSLATION_TAG_KEY, null)
                    tv.setTag(Alltrans.ALLTRANS_PENDING_SPAN_PAYLOAD_TAG_KEY, null)
                } else if (currentCanCallOriginal && currentOriginalCallable != null) {
                    Utils.debugLog("$TAG: Calling originalCallable for key ($keyToRemove): [$finalString]")
                    currentOriginalCallable.callOriginalMethod(finalString, currentUserData)
                } else {
                    Utils.debugLog("$TAG: No callback action for key ($keyToRemove), userData: ${currentUserData?.javaClass?.name ?: "null"}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in callback for key ($keyToRemove), original: $originalString", e)
            } finally {
                synchronized(Alltrans.pendingTextViewTranslations) {
                    if (Alltrans.pendingTextViewTranslations.remove(keyToRemove)) {
                        Utils.debugLog("$TAG: Removed key ($keyToRemove) from pending set.")
                    }
                }
            }
        }

        if (delay > 0) {
            Handler(Looper.getMainLooper()).postDelayed(task, delay)
        } else {
            Handler(Looper.getMainLooper()).post(task)
        }
    }

    override fun onFailure(call: Call, e: IOException) {
        if (!failureHandled.compareAndSet(false, true)) {
            Utils.debugLog("$TAG: Failure already handled for: ${call.request().url}")
            return
        }

        Log.e(TAG, "Network request failed for: [${stringToBeTrans ?: "Unknown"}] Reason: ${Log.getStackTraceString(e)}")

        val keyToRemove = pendingCompositeKey
        val localOriginalString = stringToBeTrans
        val localUserData = userData
        val localOriginalCallable = originalCallable
        val localCanCallOriginal = canCallOriginal
        val delay = PreferenceList.Delay.toLong()

        val task = Runnable {
            try {
                if (localUserData is TextView) {
                    Utils.debugLog("$TAG: Network failure for TextView (key $keyToRemove), original text remains.")
                    localUserData.setTag(Alltrans.ALLTRANS_PENDING_TRANSLATION_TAG_KEY, null)
                    localUserData.setTag(Alltrans.ALLTRANS_PENDING_SPAN_PAYLOAD_TAG_KEY, null)
                } else if (localCanCallOriginal && localOriginalCallable != null) {
                    Utils.debugLog("$TAG: Calling originalCallable on failure for key ($keyToRemove).")
                    localOriginalCallable.callOriginalMethod(localOriginalString.orEmpty(), localUserData)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error in failure callback for key ($keyToRemove)", t)
            } finally {
                synchronized(Alltrans.pendingTextViewTranslations) {
                    if (Alltrans.pendingTextViewTranslations.remove(keyToRemove)) {
                        Utils.debugLog("$TAG: Removed key ($keyToRemove) from pending set after failure.")
                    }
                }
            }
        }

        if (delay > 0) {
            Handler(Looper.getMainLooper()).postDelayed(task, delay)
        } else {
            Handler(Looper.getMainLooper()).post(task)
        }
    }
}
