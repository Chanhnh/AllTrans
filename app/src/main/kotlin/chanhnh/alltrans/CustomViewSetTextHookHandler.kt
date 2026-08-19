package chanhnh.alltrans

import android.content.Context
import android.text.Editable
import android.view.View
import android.widget.TextView
import dalvik.system.DexFile
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.CharBuffer
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap

class CustomViewSetTextHookHandler : XC_MethodHook(), OriginalCallable {

    companion object {
        private const val TAG = "AllTrans:CustomSetText"
        private const val FIELD_APPLYING_TRANSLATED = "alltrans:custom_settext_applying_translated"
        private val hookScheduledPackages = ConcurrentHashMap.newKeySet<String>()
        private val hookedPackages = ConcurrentHashMap.newKeySet<String>()
        private val prioritizedSetterNames = setOf(
            "setText",
            "setTitle",
            "setSubtitle",
            "setLabel",
            "setSummary",
            "setMessage",
            "setHint",
            "setDescription",
            "setPrompt",
            "setEmptyText"
        )

        fun hookCustomSetTextMethods(context: Context) {
            if (!PreferenceList.SetText) return

            val packageName = context.packageName
            if (!hookScheduledPackages.add(packageName)) {
                Utils.debugLog("$TAG: Custom view text hook scan already scheduled for $packageName")
                return
            }

            val classLoader = context.classLoader ?: run {
                Utils.debugLog("$TAG: Missing classLoader for $packageName")
                return
            }
            val sourceDir = context.applicationInfo?.sourceDir ?: run {
                Utils.debugLog("$TAG: Missing sourceDir for $packageName")
                return
            }

            ThreadPoolManager.ioExecutor.execute {
                val hook = CustomViewSetTextHookHandler()
                val hookedSignatures = java.util.HashSet<String>()
                var hookedCount = 0

                try {
                    val dexFile = DexFile(sourceDir)
                    val entries: Enumeration<String> = dexFile.entries()

                    while (entries.hasMoreElements()) {
                        val className = entries.nextElement() ?: continue
                        if (shouldSkipClassName(className)) continue

                        val clazz = try {
                            classLoader.loadClass(className)
                        } catch (_: Throwable) {
                            continue
                        }
                        if (clazz.isInterface || clazz.isAnnotation) continue
                        if (!View::class.java.isAssignableFrom(clazz) || TextView::class.java.isAssignableFrom(clazz)) continue

                        clazz.declaredMethods.forEach { method ->
                            if (!isEligibleViewTextMethod(method)) return@forEach
                            val signature = "${method.declaringClass.name}#${method.name}(${method.parameterTypes[0].name})"
                            if (!hookedSignatures.add(signature)) return@forEach

                            try {
                                XposedBridge.hookMethod(method, hook)
                                hookedCount++
                            } catch (_: Throwable) {
                            }
                        }
                    }

                    hookedPackages.add(packageName)
                    Utils.debugLog("$TAG: Hooked $hookedCount custom view text methods for $packageName")
                } catch (t: Throwable) {
                    hookScheduledPackages.remove(packageName)
                    Utils.debugLog("$TAG: Failed to scan dex for $packageName: ${t.message}")
                }
            }
        }

        private fun shouldSkipClassName(className: String): Boolean {
            return className.startsWith("android.") ||
                className.startsWith("androidx.") ||
                className.startsWith("java.") ||
                className.startsWith("javax.") ||
                className.startsWith("kotlin.") ||
                className.startsWith("de.robv.android.xposed.") ||
                className.startsWith("chanhnh.alltrans.")
        }

        private fun isEligibleViewTextMethod(method: Method): Boolean {
            if (method.name !in prioritizedSetterNames) return false
            if (Modifier.isAbstract(method.modifiers) || Modifier.isNative(method.modifiers) || Modifier.isStatic(method.modifiers)) return false

            val pTypes = method.parameterTypes
            if (pTypes.size != 1) return false
            val p0 = pTypes[0]
            return CharSequence::class.java.isAssignableFrom(p0) || p0 == String::class.java
        }
    }

    private data class CustomSetTextCall(
        val method: Method,
        val target: Any,
        val originalArgs: Array<Any?>,
        val argType: Class<*>,
        val originalText: String
    )

    @Throws(Throwable::class)
    override fun beforeHookedMethod(param: MethodHookParam) {
        val hookedMethod = param.method as? Method ?: return
        if (param.args.isEmpty() || param.args[0] !is CharSequence) return
        if (param.thisObject is TextView) return

        if (isApplyingTranslated(param.thisObject)) {
            setApplyingTranslated(param.thisObject, false)
            return
        }

        val originalCs = param.args[0] as CharSequence
        val originalText = originalCs.toString()
        if (!SetTextHookHandler.isNotWhiteSpace(originalText)) return
        if (originalCs is Editable) return

        val context = try {
            XposedHelpers.callMethod(param.thisObject, "getContext") as? Context
        } catch (_: Throwable) {
            null
        } ?: Alltrans.context ?: return
        if (!PreferenceManager.isEnabledForPackage(context, context.packageName)) return

        if (PreferenceList.TranslateFromLanguage != "auto" &&
            PreferenceList.TranslateFromLanguage == PreferenceList.TranslateToLanguage) {
            ViewTextCache.markNoTranslation(originalText)
            cacheAsNoTranslation(originalText)
            return
        }

        try {
            val isEditable = XposedHelpers.callMethod(param.thisObject, "isEditable") as? Boolean ?: false
            if (isEditable) return
        } catch (_: Throwable) {
        }

        if (SetTextHookHandler.shouldSkipTranslation(originalText)) {
            ViewTextCache.markNoTranslation(originalText)
            cacheAsNoTranslation(originalText)
            return
        }

        val cachedTranslation = ViewTextCache.get(originalText) ?: getCachedTranslation(originalText)
        if (!cachedTranslation.isNullOrEmpty() && cachedTranslation != originalText) {
            param.args[0] = adaptArgumentType(cachedTranslation, hookedMethod.parameterTypes[0])
            return
        }

        val compositeKey = createCompositeKey(param.thisObject, originalText)
        if (!addPending(compositeKey)) return

        val callbackData = CustomSetTextCall(
            method = hookedMethod,
            target = param.thisObject,
            originalArgs = param.args.copyOf(),
            argType = hookedMethod.parameterTypes[0],
            originalText = originalText
        )

        val getTranslate = GetTranslate().apply {
            stringToBeTrans = originalText
            userData = callbackData
            originalCallable = this@CustomViewSetTextHookHandler
            canCallOriginal = true
            pendingCompositeKey = compositeKey
            forceCacheResult = true
        }
        GetTranslateToken().apply { this.getTranslate = getTranslate }.doAll()
    }

    override fun callOriginalMethod(translatedString: CharSequence?, userData: Any?) {
        val call = userData as? CustomSetTextCall ?: return
        val translated = translatedString?.toString().orEmpty().ifEmpty { call.originalText }

        if (translated == call.originalText) return

        try {
            val updatedArgs = call.originalArgs.copyOf()
            updatedArgs[0] = adaptArgumentType(translated, call.argType)
            setApplyingTranslated(call.target, true)
            XposedBridge.invokeOriginalMethod(call.method, call.target, updatedArgs)
        } catch (t: Throwable) {
            Utils.debugLog("$TAG: Failed to invoke translated ${call.method.name} for ${call.target.javaClass.name}: ${t.message}")
        } finally {
            setApplyingTranslated(call.target, false)
        }
    }

    private fun createCompositeKey(target: Any, text: String): Int {
        return 31 * System.identityHashCode(target) + text.hashCode()
    }

    private fun addPending(compositeKey: Int): Boolean {
        synchronized(Alltrans.pendingTextViewTranslations) {
            if (Alltrans.pendingTextViewTranslations.contains(compositeKey)) return false
            Alltrans.pendingTextViewTranslations.add(compositeKey)
            return true
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
