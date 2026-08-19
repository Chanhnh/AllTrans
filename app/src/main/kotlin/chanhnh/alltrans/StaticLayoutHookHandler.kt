package chanhnh.alltrans

import android.text.Editable
import android.text.Spanned
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

class StaticLayoutHookHandler : XC_MethodReplacement() {

    companion object {
        private const val TAG = "AllTrans:StaticLayoutHook"
    }

    override fun replaceHookedMethod(param: XC_MethodHook.MethodHookParam): Any? {
        val builder = param.thisObject ?: return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)

        try {
            val sourceText = readBuilderText(builder)
                ?: return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)

            if (sourceText.isEmpty() || sourceText is Editable) {
                return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)
            }

            val range = readBuilderRange(builder, sourceText.length)
            if (range.first >= range.second) {
                return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)
            }

            val piece = sourceText.subSequence(range.first, range.second)
            if (piece.isEmpty() || piece is Editable) {
                return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)
            }

            val plainText = piece.toString()
            if (!SetTextHookHandler.isNotWhiteSpace(plainText)) {
                return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)
            }
            if (SetTextHookHandler.shouldSkipTranslation(plainText)) {
                ViewTextCache.markNoTranslation(plainText)
                cacheAsNoTranslation(plainText)
                return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)
            }

            val spanPayload = (piece as? Spanned)?.let { SpanTranslationHelper.createPayload(it) }
            val translated = if (spanPayload != null) {
                getCachedSpannedTranslation(spanPayload)
            } else {
                ViewTextCache.get(plainText) ?: getCachedTranslation(plainText)
            }

            if (translated != null && translated.toString() != plainText) {
                applyTranslatedText(builder, translated)
                return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)
            }

            requestPrefetch(spanPayload, plainText)
        } catch (t: Throwable) {
            Utils.debugLog("$TAG: Failed to replace StaticLayout text: ${t.message}")
        }

        return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)
    }

    private fun readBuilderText(builder: Any): CharSequence? {
        return try {
            XposedHelpers.getObjectField(builder, "mText") as? CharSequence
        } catch (_: Throwable) {
            try {
                XposedHelpers.getObjectField(builder, "mSource") as? CharSequence
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun readBuilderRange(builder: Any, textLength: Int): Pair<Int, Int> {
        return try {
            val start = XposedHelpers.getIntField(builder, "mStart").coerceAtLeast(0)
            val end = XposedHelpers.getIntField(builder, "mEnd").coerceAtMost(textLength)
            start to end
        } catch (_: Throwable) {
            0 to textLength
        }
    }

    private fun applyTranslatedText(builder: Any, translated: CharSequence) {
        try {
            XposedHelpers.setObjectField(builder, "mText", translated)
        } catch (_: Throwable) {
            try {
                XposedHelpers.setObjectField(builder, "mSource", translated)
            } catch (_: Throwable) {
                return
            }
        }

        try {
            XposedHelpers.setIntField(builder, "mStart", 0)
            XposedHelpers.setIntField(builder, "mEnd", translated.length)
        } catch (_: Throwable) {
        }
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

    private fun getCachedSpannedTranslation(payload: SpanTranslationPayload): CharSequence? {
        val translatedSegments = ArrayList<String>(payload.segments.size)

        payload.segments.forEach { segment ->
            val translatedSegment = when {
                segment.sourceText.isEmpty() -> segment.sourceText
                SetTextHookHandler.shouldSkipTranslation(segment.sourceText) -> {
                    ViewTextCache.markNoTranslation(segment.sourceText)
                    cacheAsNoTranslation(segment.sourceText)
                    segment.sourceText
                }
                else -> ViewTextCache.get(segment.sourceText) ?: getCachedTranslation(segment.sourceText)
            } ?: return null

            translatedSegments.add(translatedSegment)
        }

        return payload.rebuildTranslatedCharSequence(translatedSegments)
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

    private fun requestPrefetch(spanPayload: SpanTranslationPayload?, plainText: String) {
        val requestKey = spanPayload?.requestKey ?: plainText
        val compositeKey = ("staticlayout:$requestKey").hashCode()

        synchronized(Alltrans.pendingTextViewTranslations) {
            if (Alltrans.pendingTextViewTranslations.contains(compositeKey)) return
            Alltrans.pendingTextViewTranslations.add(compositeKey)
        }

        val getTranslate = GetTranslate().apply {
            stringToBeTrans = requestKey
            userData = null
            originalCallable = null
            canCallOriginal = false
            pendingCompositeKey = compositeKey
            this.spanTranslationPayload = spanPayload
            forceCacheResult = true
        }
        GetTranslateToken().apply { this.getTranslate = getTranslate }.doAll()
    }
}
