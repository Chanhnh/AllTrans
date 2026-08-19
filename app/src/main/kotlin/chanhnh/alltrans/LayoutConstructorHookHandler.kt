package chanhnh.alltrans

import android.text.Editable
import android.text.Spanned
import de.robv.android.xposed.XC_MethodHook

class LayoutConstructorHookHandler : XC_MethodHook() {

    companion object {
        private const val TAG = "AllTrans:LayoutCtorHook"
    }

    @Throws(Throwable::class)
    override fun beforeHookedMethod(param: MethodHookParam) {
        if (param.args.isEmpty() || param.args[0] !is CharSequence) return

        val sourceText = param.args[0] as CharSequence
        if (sourceText.isEmpty() || sourceText is Editable) return

        val piece = extractRelevantText(param.args, sourceText) ?: return
        val plainText = piece.toString()
        if (!SetTextHookHandler.isNotWhiteSpace(plainText)) return

        if (SetTextHookHandler.shouldSkipTranslation(plainText)) {
            ViewTextCache.markNoTranslation(plainText)
            return
        }

        val spanPayload = (piece as? Spanned)?.let { SpanTranslationHelper.createPayload(it) }
        val translated = if (spanPayload != null) {
            getCachedSpannedTranslation(spanPayload)
        } else {
            ViewTextCache.get(plainText)
        }

        if (!translated.isNullOrEmpty() && translated.toString() != plainText) {
            param.args[0] = translated
            adjustRangeArguments(param.args, translated.length)
            return
        }

        requestPrefetch(spanPayload, plainText)
    }

    private fun extractRelevantText(args: Array<Any?>, sourceText: CharSequence): CharSequence? {
        if (args.size < 3 || args[1] !is Int || args[2] !is Int) {
            return sourceText
        }

        val start = args[1] as Int
        val endOrCount = args[2] as Int
        return try {
            if (start < 0) return null
            if (endOrCount >= start && endOrCount <= sourceText.length) {
                sourceText.subSequence(start, endOrCount)
            } else if (start + endOrCount <= sourceText.length) {
                sourceText.subSequence(start, start + endOrCount)
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun adjustRangeArguments(args: Array<Any?>, translatedLength: Int) {
        if (args.size >= 3 && args[1] is Int && args[2] is Int) {
            args[1] = 0
            args[2] = translatedLength
        }
    }

    private fun getCachedSpannedTranslation(payload: SpanTranslationPayload): CharSequence? {
        val translatedSegments = ArrayList<String>(payload.segments.size)

        payload.segments.forEach { segment ->
            val translatedSegment = when {
                segment.sourceText.isEmpty() -> segment.sourceText
                SetTextHookHandler.shouldSkipTranslation(segment.sourceText) -> {
                    ViewTextCache.markNoTranslation(segment.sourceText)
                    segment.sourceText
                }
                else -> ViewTextCache.get(segment.sourceText)
            } ?: return null

            translatedSegments.add(translatedSegment)
        }

        return payload.rebuildTranslatedCharSequence(translatedSegments)
    }

    private fun requestPrefetch(spanPayload: SpanTranslationPayload?, plainText: String) {
        val requestKey = spanPayload?.requestKey ?: plainText
        val compositeKey = ("layoutctor:$requestKey").hashCode()

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
