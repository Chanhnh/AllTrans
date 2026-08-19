package chanhnh.alltrans

import android.content.Context
import android.text.Editable
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.nio.CharBuffer

class ViewTextMethodHookHandler : XC_MethodHook(), OriginalCallable {

    companion object {
        private const val TAG = "AllTrans:ViewTextMethod"
        private const val FIELD_APPLYING_TRANSLATED = "alltrans:view_method_applying_translated"
    }

    private data class ViewMethodCall(
        val method: Method,
        val target: Any,
        val originalArgs: Array<Any?>,
        val argType: Class<*>,
        val originalText: String
    )

    @Throws(Throwable::class)
    override fun beforeHookedMethod(param: MethodHookParam) {
        val method = param.method as? Method ?: return
        if (param.args.isEmpty() || param.args[0] !is CharSequence) return

        if (isApplyingTranslated(param.thisObject)) {
            setApplyingTranslated(param.thisObject, false)
            return
        }

        val originalCs = param.args[0] as CharSequence
        val originalText = originalCs.toString()
        if (!SetTextHookHandler.isNotWhiteSpace(originalText) || originalCs is Editable) return

        val context = try {
            XposedHelpers.callMethod(param.thisObject, "getContext") as? Context
        } catch (_: Throwable) {
            null
        } ?: Alltrans.context ?: return
        if (!PreferenceManager.isEnabledForPackage(context, context.packageName)) return

        if (PreferenceList.TranslateFromLanguage != "auto" &&
            PreferenceList.TranslateFromLanguage == PreferenceList.TranslateToLanguage) {
            ViewTextCache.markNoTranslation(originalText)
            return
        }

        if (SetTextHookHandler.shouldSkipTranslation(originalText)) {
            ViewTextCache.markNoTranslation(originalText)
            return
        }

        val cachedTranslation = ViewTextCache.get(originalText)
        if (!cachedTranslation.isNullOrEmpty() && cachedTranslation != originalText) {
            param.args[0] = adaptArgumentType(cachedTranslation, method.parameterTypes[0])
            return
        }

        val compositeKey = createCompositeKey(param.thisObject, method.name, originalText)
        synchronized(Alltrans.pendingTextViewTranslations) {
            if (Alltrans.pendingTextViewTranslations.contains(compositeKey)) return
            Alltrans.pendingTextViewTranslations.add(compositeKey)
        }

        val callbackData = ViewMethodCall(
            method = method,
            target = param.thisObject,
            originalArgs = param.args.copyOf(),
            argType = method.parameterTypes[0],
            originalText = originalText
        )

        val getTranslate = GetTranslate().apply {
            stringToBeTrans = originalText
            userData = callbackData
            originalCallable = this@ViewTextMethodHookHandler
            canCallOriginal = true
            pendingCompositeKey = compositeKey
            forceCacheResult = true
        }
        GetTranslateToken().apply { this.getTranslate = getTranslate }.doAll()
    }

    override fun callOriginalMethod(translatedString: CharSequence?, userData: Any?) {
        val call = userData as? ViewMethodCall ?: return
        val translated = translatedString?.toString().orEmpty().ifEmpty { call.originalText }

        try {
            val updatedArgs = call.originalArgs.copyOf()
            updatedArgs[0] = adaptArgumentType(translated, call.argType)
            setApplyingTranslated(call.target, true)
            XposedBridge.invokeOriginalMethod(call.method, call.target, updatedArgs)
        } catch (t: Throwable) {
            Utils.debugLog("$TAG: Failed to invoke ${call.method.name} for ${call.target.javaClass.name}: ${t.message}")
        } finally {
            setApplyingTranslated(call.target, false)
        }
    }

    private fun createCompositeKey(target: Any, methodName: String, text: String): Int {
        return (31 * System.identityHashCode(target) + methodName.hashCode()) * 31 + text.hashCode()
    }

    private fun adaptArgumentType(value: String, type: Class<*>): Any {
        return when {
            type == String::class.java -> value
            CharSequence::class.java.isAssignableFrom(type) -> value
            else -> CharBuffer.wrap(value)
        }
    }

    private fun isApplyingTranslated(target: Any): Boolean {
        return try {
            XposedHelpers.getAdditionalInstanceField(target, FIELD_APPLYING_TRANSLATED) as? Boolean ?: false
        } catch (_: Throwable) {
            false
        }
    }

    private fun setApplyingTranslated(target: Any, value: Boolean) {
        try {
            XposedHelpers.setAdditionalInstanceField(target, FIELD_APPLYING_TRANSLATED, value)
        } catch (_: Throwable) {
        }
    }
}
