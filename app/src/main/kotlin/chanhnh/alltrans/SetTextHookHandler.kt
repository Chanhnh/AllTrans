package chanhnh.alltrans

import android.app.Activity
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.View
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.nio.CharBuffer
import java.util.concurrent.ConcurrentHashMap

class SetTextHookHandler : XC_MethodHook() {

    companion object {
        private const val TAG = "AllTrans:SetTextHook"

        // ✅ PUBLIC - Marcador especial para textos que NÃO devem ser traduzidos
        const val NO_TRANSLATION_MARKER = "::SKIP_TRANSLATION::"

        // Cache for detected patterns
        private val properNamesCache = ConcurrentHashMap<String, Boolean>()
        private val technicalIdentifiersCache = ConcurrentHashMap<String, Boolean>()

        fun isNotWhiteSpace(text: String?): Boolean {
            return !text.isNullOrEmpty() && text.trim().isNotEmpty()
        }

        fun shouldSkipTranslation(text: String): Boolean {
            val trimmed = text.trim()

            // URLs apenas se forem 100% URL, não texto misto
            if (isCompleteUrl(trimmed)) return true

            // Tempo curto (26m, 2h, 5d)
            if (trimmed.matches(Regex("^\\d+[smhdw]$"))) return true

            // Identificadores técnicos (fortran77, user_id, v2_0)
            if (isTechnicalIdentifier(trimmed)) return true

            // Nomes próprios APENAS se for palavra única
            if (isSingleProperName(trimmed)) return true

            // Números puros, símbolos puros
            if (trimmed.matches(Regex("^[\\d\\s,\\.\\-+=%@#\\$&*()]+$"))) return true

            // Emojis puros
            if (isOnlyEmojis(trimmed)) return true

            return false
        }

        /**
         * Verifica se é uma URL completa (sem texto adicional)
         */
        private fun isCompleteUrl(text: String): Boolean {
            return text.matches(Regex("^(https?://)?[a-zA-Z0-9][a-zA-Z0-9.-]*\\.[a-zA-Z]{2,}(/[^\\s]*)?$"))
        }

        /**
         * Verifica se é apenas emojis
         */
        private fun isOnlyEmojis(text: String): Boolean {
            return text.all { char ->
                Character.getType(char) == Character.OTHER_SYMBOL.toInt() ||
                        Character.isWhitespace(char)
            }
        }

        private fun isTechnicalIdentifier(text: String): Boolean {
            return technicalIdentifiersCache.getOrPut(text) {
                // Identificadores de programação: snake_case ou com números
                text.matches(Regex("^[a-z0-9_]+$")) &&
                        (text.contains("_") || text.any { it.isDigit() })
            }
        }

        /**
         * Apenas nomes próprios de palavra única
         */
        private fun isSingleProperName(text: String): Boolean {
            // Não cachear nomes compostos
            if (text.contains(" ")) return false

            return properNamesCache.getOrPut(text) {
                // CamelCase ou PascalCase: RizkiAnurka, JohnSmith
                text.matches(Regex("^[A-Z][a-z]+([A-Z][a-z]*)*$"))
            }
        }
    }

    private fun modifyArgument(param: XC_MethodHook.MethodHookParam, newText: CharSequence?) {
        if (param.args.isEmpty() || param.args[0] == null || newText == null) return
        param.args[0] = when (param.args[0]) {
            is SpannableString -> SpannableString(newText)
            is CharBuffer -> CharBuffer.wrap(newText.toString())
            is String -> newText.toString()
            is StringBuffer -> StringBuffer(newText.toString())
            is StringBuilder -> StringBuilder(newText.toString())
            else -> newText
        }
    }

    private fun isEditableTextView(textView: TextView): Boolean {
        return try {
            val isDefaultEditable = XposedHelpers.callMethod(textView, "getDefaultEditable") as? Boolean ?: false
            val isEnabled = textView.isEnabled
            isDefaultEditable && isEnabled
        } catch (e: Throwable) {
            false
        }
    }

    private fun createCompositeKey(textViewHashCode: Int, text: String): Int {
        return 31 * textViewHashCode + text.hashCode()
    }

    private fun getCachedTranslation(text: String, compositeKey: Int): String? {
        if (!PreferenceList.Caching) return null

        var cachedValue: String? = null
        try {
            Alltrans.cacheAccess.acquireUninterruptibly()
            cachedValue = Alltrans.cache?.get(text)
        } finally {
            if (Alltrans.cacheAccess.availablePermits() == 0) {
                Alltrans.cacheAccess.release()
            }
        }

        // Se cache retorna marcador especial, significa "não traduzir"
        if (cachedValue == NO_TRANSLATION_MARKER) {
            return text // Retorna o texto original sem traduzir
        }

        return if (cachedValue != null && cachedValue != text) cachedValue else null
    }

    /**
     * Salva no cache que este texto NÃO deve ser traduzido
     */
    private fun cacheAsNoTranslation(text: String) {
        if (!PreferenceList.Caching) return

        try {
            Alltrans.cacheAccess.acquireUninterruptibly()
            Alltrans.cache?.put(text, NO_TRANSLATION_MARKER)
        } finally {
            if (Alltrans.cacheAccess.availablePermits() == 0) {
                Alltrans.cacheAccess.release()
            }
        }
    }

    private fun removePendingTranslation(compositeKey: Int, reason: String) {
        synchronized(Alltrans.pendingTextViewTranslations) {
            if (Alltrans.pendingTextViewTranslations.remove(compositeKey)) {
                Utils.debugLog("$TAG: Chave $compositeKey removida: $reason")
            }
        }
    }

    private fun addPendingTranslation(compositeKey: Int, text: String): Boolean {
        synchronized(Alltrans.pendingTextViewTranslations) {
            if (Alltrans.pendingTextViewTranslations.contains(compositeKey)) {
                return false
            }
            Alltrans.pendingTextViewTranslations.add(compositeKey)
            return true
        }
    }

    private fun getCachedSpannedTranslation(payload: SpanTranslationPayload): CharSequence? {
        val translatedSegments = ArrayList<String>(payload.segments.size)

        payload.segments.forEach { segment ->
            val segmentText = segment.sourceText
            val translatedSegment = when {
                segmentText.isEmpty() -> segmentText
                shouldSkipTranslation(segmentText) -> {
                    cacheAsNoTranslation(segmentText)
                    segmentText
                }
                else -> getCachedTranslation(segmentText, 0)
            } ?: return null

            translatedSegments.add(translatedSegment)
        }

        return payload.rebuildTranslatedCharSequence(translatedSegments)
    }

    private fun clearPendingSpanPayload(textView: TextView) {
        textView.setTag(Alltrans.ALLTRANS_PENDING_SPAN_PAYLOAD_TAG_KEY, null)
    }

    private fun requestTranslation(
        requestKey: String,
        textView: TextView,
        compositeKey: Int,
        spanPayload: SpanTranslationPayload? = null
    ) {
        val context = textView.context
        if (context is Activity && (context.isFinishing || context.isDestroyed)) {
            removePendingTranslation(compositeKey, "Activity destroyed")
            return
        }

        Utils.debugLog("$TAG: Requesting translation for [${requestKey.take(50)}${if(requestKey.length > 50) "..." else ""}]")

        val getTranslate = GetTranslate().apply {
            stringToBeTrans = requestKey
            userData = textView
            originalCallable = null
            canCallOriginal = false
            pendingCompositeKey = compositeKey
            spanTranslationPayload = spanPayload
        }
        GetTranslateToken().apply { this.getTranslate = getTranslate }.doAll()
    }

    @Throws(Throwable::class)
    override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
        val methodName = param.method.name
        val textView = param.thisObject as? TextView ?: return

        // Verifica tag de tradução aplicada
        val appliedByAllTransTag = textView.getTag(Alltrans.ALLTRANS_TRANSLATION_APPLIED_TAG_KEY)
        if (appliedByAllTransTag == true) {
            textView.setTag(Alltrans.ALLTRANS_TRANSLATION_APPLIED_TAG_KEY, false)
            return
        }

        if (param.args.isEmpty() || param.args[0] !is CharSequence) return

        val originalTextCS = param.args[0] as CharSequence
        val originalText = originalTextCS.toString()
        if (!isNotWhiteSpace(originalText)) return

        val packageName = textView.context?.packageName
        if (!PreferenceManager.isEnabledForPackage(textView.context, packageName)) return

        if (methodName == "setHint" && !PreferenceList.SetHint) return
        if (methodName == "setText" && !PreferenceList.SetText) return

        // EditText: não traduz conteúdo editável (apenas hint)
        if (isEditableTextView(textView) && methodName != "setHint") {
            if (PreferenceList.Scroll) configureScroll(textView)
            return
        }

        if (PreferenceList.Scroll) configureScroll(textView)

        // Verificação de skip antes de detecção de idioma
        if (shouldSkipTranslation(originalText)) {
            Utils.debugLog("$TAG: Texto ignorado por regras de skip: [${originalText.take(30)}...]")
            cacheAsNoTranslation(originalText)
            return
        }

        // Lógica de detecção de idioma
        val needsLanguageDetection = PreferenceList.TranslateFromLanguage == "auto"
        val sameSourceAndTarget = PreferenceList.TranslateFromLanguage == PreferenceList.TranslateToLanguage

        // If source language == target language and not auto, skip translation
        if (sameSourceAndTarget && PreferenceList.TranslateFromLanguage != "auto") {
            Utils.debugLog("$TAG: Same source/target language (${PreferenceList.TranslateFromLanguage}), keeping original")
            cacheAsNoTranslation(originalText)
            return
        }

        // Proceed directly to cache check + translation
        // The online Google API natively handles "auto" source language detection
        val spanPayload = if (originalTextCS is Spanned) {
            SpanTranslationHelper.createPayload(originalTextCS)
        } else {
            null
        }
        val requestKey = spanPayload?.requestKey ?: originalText
        val compositeKey = createCompositeKey(textView.hashCode(), requestKey)

        if (spanPayload != null) {
            val cachedSpannedTranslation = getCachedSpannedTranslation(spanPayload)
            if (cachedSpannedTranslation != null) {
                textView.setTag(Alltrans.ALLTRANS_TRANSLATION_APPLIED_TAG_KEY, true)
                modifyArgument(param, cachedSpannedTranslation)
                clearPendingSpanPayload(textView)
                return
            }
        } else {
            val cachedTranslation = getCachedTranslation(originalText, compositeKey)
            if (cachedTranslation != null) {
                textView.setTag(Alltrans.ALLTRANS_TRANSLATION_APPLIED_TAG_KEY, true)
                modifyArgument(param, cachedTranslation)
                clearPendingSpanPayload(textView)
                return
            }
        }

        if (!addPendingTranslation(compositeKey, requestKey)) {
            Utils.debugLog("$TAG: Translation already pending for [${originalText.take(30)}...]")
            return
        }

        textView.setTag(Alltrans.ALLTRANS_PENDING_TRANSLATION_TAG_KEY, requestKey)
        if (spanPayload != null) {
            textView.setTag(Alltrans.ALLTRANS_PENDING_SPAN_PAYLOAD_TAG_KEY, spanPayload)
        } else {
            clearPendingSpanPayload(textView)
        }
        requestTranslation(requestKey, textView, compositeKey, spanPayload)
    }

    private fun configureScroll(textView: TextView) {
        try {
            if (textView.movementMethod == null && !textView.isSingleLine) {
                textView.isVerticalScrollBarEnabled = true
                textView.movementMethod = ScrollingMovementMethod.getInstance()
                textView.scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
            } else if (textView.isSingleLine) {
                textView.ellipsize = TextUtils.TruncateAt.MARQUEE
                textView.marqueeRepeatLimit = -1
                textView.isFocusable = true
                textView.isFocusableInTouchMode = true
                textView.isSelected = true
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to configure scroll", e)
        }
    }
}
