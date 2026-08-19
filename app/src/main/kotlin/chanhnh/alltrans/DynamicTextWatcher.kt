package chanhnh.alltrans

import android.text.Editable
import android.text.Spanned
import android.text.TextWatcher
import android.widget.TextView

class DynamicTextWatcher(private val textView: TextView) : TextWatcher {
    private var isTranslating = false

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

    override fun afterTextChanged(s: Editable?) {
        if (isTranslating || s.isNullOrEmpty()) return

        val originalTextCs = s as CharSequence
        val originalText = originalTextCs.toString()
        if (!SetTextHookHandler.isNotWhiteSpace(originalText)) return

        // Verifica se o texto já foi traduzido
        val appliedTag = textView.getTag(Alltrans.ALLTRANS_TRANSLATION_APPLIED_TAG_KEY)
        if (appliedTag == true) {
            textView.setTag(Alltrans.ALLTRANS_TRANSLATION_APPLIED_TAG_KEY, false)
            isTranslating = false
            return
        }

        // Verifica se o AllTrans está habilitado para este app
        val packageName = textView.context?.packageName
        if (!PreferenceManager.isEnabledForPackage(textView.context, packageName)) return

        val spanPayload = if (originalTextCs is Spanned) {
            SpanTranslationHelper.createPayload(originalTextCs)
        } else {
            null
        }
        val requestKey = spanPayload?.requestKey ?: originalText

        // Evita retradução do mesmo texto
        val pendingTag = textView.getTag(Alltrans.ALLTRANS_PENDING_TRANSLATION_TAG_KEY) as? String
        if (pendingTag == requestKey) return

        isTranslating = true
        textView.setTag(Alltrans.ALLTRANS_PENDING_TRANSLATION_TAG_KEY, requestKey)
        if (spanPayload != null) {
            textView.setTag(Alltrans.ALLTRANS_PENDING_SPAN_PAYLOAD_TAG_KEY, spanPayload)
        } else {
            textView.setTag(Alltrans.ALLTRANS_PENDING_SPAN_PAYLOAD_TAG_KEY, null)
        }

        // Solicita tradução
        val compositeKey = 31 * textView.hashCode() + requestKey.hashCode()
        val cachedTranslation = if (spanPayload != null) {
            getCachedSpannedTranslation(spanPayload)
        } else {
            getCachedTranslation(originalText)
        }

        if (cachedTranslation != null) {
            applyTranslation(cachedTranslation)
        } else {
            requestTranslation(requestKey, compositeKey, spanPayload)
            isTranslating = false
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
            val sourceText = segment.sourceText
            val translated = when {
                sourceText.isEmpty() -> sourceText
                SetTextHookHandler.shouldSkipTranslation(sourceText) -> {
                    cacheAsNoTranslation(sourceText)
                    sourceText
                }
                else -> getCachedTranslation(sourceText)
            } ?: return null

            translatedSegments.add(translated)
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

    private fun applyTranslation(translatedText: CharSequence) {
        textView.setTag(Alltrans.ALLTRANS_TRANSLATION_APPLIED_TAG_KEY, true)
        textView.setTag(Alltrans.ALLTRANS_PENDING_TRANSLATION_TAG_KEY, null)
        textView.setTag(Alltrans.ALLTRANS_PENDING_SPAN_PAYLOAD_TAG_KEY, null)
        textView.text = translatedText
        isTranslating = false
    }

    private fun requestTranslation(
        text: String,
        compositeKey: Int,
        spanPayload: SpanTranslationPayload? = null
    ) {
        val getTranslate = GetTranslate().apply {
            stringToBeTrans = text
            userData = textView
            originalCallable = null
            canCallOriginal = false
            pendingCompositeKey = compositeKey
            spanTranslationPayload = spanPayload
        }
        GetTranslateToken().apply { this.getTranslate = getTranslate }.doAll()
    }
}
