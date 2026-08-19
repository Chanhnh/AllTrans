// AttachBaseContextHookHandler.kt (Corrigido - readPrefAndHook no companion object)
package chanhnh.alltrans

import android.util.LruCache
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.UserHandle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.text.Layout
import android.text.TextPaint
import android.text.TextUtils
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.TextView.BufferType
import androidx.core.net.toUri
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.concurrent.ConcurrentHashMap

internal class AttachBaseContextHookHandler : XC_MethodHook() {

    companion object {
        // Rastreia quais pacotes já foram hookados para evitar hooks duplicados
        private val hookedPackages = ConcurrentHashMap.newKeySet<String>()

        /**
         * Função principal para ler preferências e aplicar hooks
         * DEVE estar no companion object para ser acessível de outros lugares
         */
        fun readPrefAndHook(context: Context?) {
            if (context == null) {
                Utils.debugLog("AllTrans: readPrefAndHook called with null context")
                return
            }

            val packageName = context.packageName
            Utils.debugLog("readPrefAndHook for $packageName")

            // Verificar se é um app do sistema que deve ser ignorado
            if (isSystemAppToSkip(context)) {
                Utils.debugLog("AllTrans: Skipping system app: $packageName")
                return
            }

            // Prevenir hooks duplicados
            if (hookedPackages.contains(packageName)) {
                Utils.debugLog("AllTrans: Already hooked $packageName, skipping duplicate hooks")
                // Ainda atualiza preferências caso tenham mudado
                try {
                    val prefsBundle = getPreferencesViaProviders(context, packageName)
                    if (prefsBundle != null) {
                        val globalPref = prefsBundle.getString("globalPref")
                        val localPref = prefsBundle.getString("localPref")
                        if (!globalPref.isNullOrEmpty()) {
                            PreferenceList.getPref(globalPref, localPref ?: "", packageName)
                            Utils.Debug = PreferenceList.Debug
                        }
                    }
                } catch (e: Exception) {
                    Utils.debugLog("AllTrans: Error updating preferences: ${e.message}")
                }
                return
            }

            // Obter preferências
            try {
                val prefsBundle = getPreferencesViaProviders(context, packageName)
                if (prefsBundle == null) {
                    Utils.debugLog("AllTrans: No preferences found for $packageName, skipping hooks")
                    return
                }

                val globalPref = prefsBundle.getString("globalPref")
                val localPref = prefsBundle.getString("localPref")

                if (globalPref.isNullOrEmpty()) {
                    Utils.debugLog("AllTrans: Empty global preferences for $packageName, skipping hooks")
                    return
                }

                try {
                    PreferenceList.getPref(globalPref, localPref ?: "", packageName)
                    Utils.Debug = PreferenceList.Debug
                } catch (e: Exception) {
                    Utils.debugLog("AllTrans: Error applying preferences: ${e.message}")
                    return
                }

                if (!PreferenceList.Enabled || !PreferenceList.LocalEnabled) {
                    Utils.debugLog("AllTrans disabled for $packageName")
                    return
                }
                Utils.debugLog("AllTrans Enabled for $packageName")

                // Gerenciar cache
                try {
                    manageCache(context)
                } catch (e: Exception) {
                    Utils.debugLog("AllTrans: Error managing cache: ${e.message}")
                }

                // Aplicar hooks
                try {
                    applyAllHooks(context)
                    hookedPackages.add(packageName)
                    Utils.debugLog("AllTrans: Successfully hooked $packageName")
                } catch (e: Exception) {
                    Utils.debugLog("AllTrans: Error applying hooks: ${e.message}")
                }

            } catch (e: Exception) {
                Utils.debugLog("AllTrans: Error in readPrefAndHook main flow: ${Log.getStackTraceString(e)}")
            }
        }

        /**
         * Obtém preferências via ContentProvider
         */
        private fun getPreferencesViaProviders(context: Context?, packageName: String?): Bundle? {
            if (context == null || packageName == null) return null
            val resultBundle = Bundle()
            var globalPref: String? = null
            var localPref: String? = null
            var cursor: Cursor? = null
            val identity = Binder.clearCallingIdentity()

            try {
                // Tentar primeiro via proxy
                if (Alltrans.isProxyEnabled) {
                    try {
                        val proxyUri = "content://settings/system/alltransProxyProviderURI/chanhnh.alltrans.SharedPrefProvider/$packageName".toUri()

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val queryArgs = Bundle().apply {
                                putInt("android:queryArgSelectionBehavior", 0)
                                putBoolean("android:asyncQuery", true)
                                putInt("android:honorsExtraArgs", 1)
                            }
                            cursor = context.contentResolver.query(proxyUri, null, queryArgs, null)
                        } else {
                            cursor = context.contentResolver.query(proxyUri, null, null, null, null)
                        }

                        if (cursor != null && cursor.moveToFirst()) {
                            val dataColumnIndex = 0
                            if (cursor.columnCount > dataColumnIndex) {
                                globalPref = cursor.getString(dataColumnIndex)
                                if (cursor.moveToNext()) localPref = cursor.getString(dataColumnIndex)
                                Utils.debugLog("Proxy query successful for $packageName.")
                            }
                        }
                    } catch (e: Exception) {
                        Utils.debugLog("Proxy query exception: ${e.message}")
                    } finally {
                        cursor?.close()
                        cursor = null
                    }
                }

                // Fallback para conexão direta
                if (globalPref == null) {
                    try {
                        val directUri = "content://chanhnh.alltrans.SharedPrefProvider/$packageName".toUri()

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val queryArgs = Bundle().apply {
                                putInt("android:queryArgSelectionBehavior", 0)
                                putBoolean("android:asyncQuery", true)
                                putInt("android:honorsExtraArgs", 1)
                            }
                            cursor = context.contentResolver.query(directUri, null, queryArgs, null)
                        } else {
                            cursor = context.contentResolver.query(directUri, null, null, null, null)
                        }

                        if (cursor != null && cursor.moveToFirst()) {
                            val dataColumnIndex = 0
                            if (cursor.columnCount > dataColumnIndex) {
                                globalPref = cursor.getString(dataColumnIndex)
                                if (cursor.moveToNext()) localPref = cursor.getString(dataColumnIndex)
                                Utils.debugLog("Direct query successful for $packageName.")
                            }
                        }
                    } catch (e: Exception) {
                        Utils.debugLog("Direct query exception: ${Log.getStackTraceString(e)}")
                    } finally {
                        cursor?.close()
                    }
                }

                if (globalPref != null) {
                    resultBundle.putString("globalPref", globalPref)
                    resultBundle.putString("localPref", localPref ?: "")
                    return resultBundle
                } else {
                    Log.e("AllTrans", "Failed to get prefs for $packageName")
                    return null
                }
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }

        /**
         * Verifica se é um aplicativo do sistema que deve ser ignorado
         */
        private fun isSystemAppToSkip(context: Context): Boolean {
            val packageName = context.packageName

            // Lista de pacotes do sistema para ignorar
            val systemSkipList = listOf(
                "com.android.systemui",
                "com.android.settings",
                "com.android.launcher3",
                "com.android.keyguard",
                "com.android.providers",
                "com.android.bluetooth",
                "com.android.phone",
                "com.google.android.webview"
            )

            for (skipPkg in systemSkipList) {
                if (packageName.startsWith(skipPkg)) {
                    return true
                }
            }

            // Verificar se é um app do sistema
            try {
                val appInfo = context.applicationInfo
                if ((appInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) {
                    // É um app do sistema mas não está na lista branca
                    if (packageName != "chanhnh.alltrans" && packageName != "com.android.providers.settings") {
                        return true
                    }
                }
            } catch (e: Exception) {
                Utils.debugLog("Error checking if system app: ${e.message}")
            }

            return false
        }

        /**
         * Aplica todos os hooks de uma vez
         */
        private fun applyAllHooks(context: Context) {
            val packageName = context.packageName
            Utils.debugLog("Applying all hooks for $packageName")

            // TextView hooks (expandido)
            applyTextViewHooks()

            if (PreferenceList.ExtendedViewTranslation) {
                // View/ViewGroup hooks to trigger translation on attached/dynamic views
                applyViewHierarchyHooks()

                // Hook direct framework View text-bearing methods
                applyFrameworkViewTextHooks()

                // Hook custom View/ViewGroup implementations exposing text setters
                CustomViewSetTextHookHandler.hookCustomSetTextMethods(context)

                // StaticLayout hook for text rendered outside the TextView setText path
                applyStaticLayoutHooks(context)

                // Legacy layout constructors still used by many custom View implementations
                applyLegacyLayoutHooks(context)
            } else {
                Utils.debugLog("Extended view translation hooks disabled for $packageName")
            }

            // WebView hooks
            applyWebViewHooks(packageName)

            // Notification hooks
            applyNotificationHooks()
        }

        /**
         * Gerencia o cache do AllTrans
         */
        private fun manageCache(context: Context) {
            Utils.debugLog("manageCache for ${context.packageName}: Caching = ${PreferenceList.Caching}")

            if (PreferenceList.Caching) {
                Utils.debugLog("Cache ENABLED for ${context.packageName}")
                clearCacheIfNeeded(context, PreferenceList.CachingTime)
                loadCacheFromDisk(context)
            } else {
                Utils.debugLog("Cache DISABLED for ${context.packageName}")
                Alltrans.cacheAccess.acquireUninterruptibly()
                try {
                    Alltrans.cache?.evictAll()

                    val cacheFile = File(context.filesDir, "AllTransCache")
                    if (cacheFile.exists() && cacheFile.delete()) {
                        Utils.debugLog("Disk cache deleted")
                    }

                    FileOutputStream(File(context.filesDir, "AllTransCacheClear")).use { fos ->
                        ObjectOutputStream(fos).use { oos ->
                            oos.writeObject(System.currentTimeMillis())
                        }
                    }
                } catch (e: Exception) {
                    Utils.debugLog("Error in cache management: ${e.message}")
                } finally {
                    if (Alltrans.cacheAccess.availablePermits() == 0) {
                        Alltrans.cacheAccess.release()
                    }
                }
            }
        }

        /**
         * Carrega o cache do disco
         */
        private fun loadCacheFromDisk(context: Context) {
            var fis: FileInputStream? = null
            var ois: ObjectInputStream? = null
            var semaphoreAcquired = false

            try {
                val cacheFile = File(context.filesDir, "AllTransCache")
                if (!cacheFile.exists() || cacheFile.length() == 0L) {
                    Utils.debugLog("Cache file not found or empty")
                    return
                }

                fis = context.openFileInput("AllTransCache")
                Alltrans.cacheAccess.acquireUninterruptibly()
                semaphoreAcquired = true

                val cacheRef = Alltrans.cache
                cacheRef?.evictAll()

                try {
                    ois = ObjectInputStream(fis)
                    val readObj = ois.readObject()

                    when (readObj) {
                        is HashMap<*, *> -> {
                            @Suppress("UNCHECKED_CAST")
                            val typedMap = readObj as HashMap<String, String>
                            typedMap.forEach { (key, value) ->
                                if (key != null && value != null) {
                                    cacheRef?.put(key, value)
                                }
                            }
                            Utils.debugLog("Cache loaded from HashMap. Size: ${cacheRef?.size()}")
                        }
                        is LruCache<*, *> -> {
                            @Suppress("UNCHECKED_CAST")
                            val lruMap = readObj as LruCache<String, String>
                            lruMap.snapshot().forEach { (key, value) ->
                                if (key != null && value != null) {
                                    cacheRef?.put(key, value)
                                }
                            }
                            Utils.debugLog("Cache loaded from LruCache. Size: ${cacheRef?.size()}")
                        }
                        else -> Utils.debugLog("Unknown cache format")
                    }
                } catch (e: Exception) {
                    Utils.debugLog("Error reading cache: ${e.message}")
                    context.deleteFile("AllTransCache")
                }
            } catch (e: Throwable) {
                Utils.debugLog("Error loading cache: ${e.message}")
                try { context.deleteFile("AllTransCache") } catch (ignored: Exception) {}
            } finally {
                try { ois?.close() } catch (ignored: IOException) {}
                try { fis?.close() } catch (ignored: IOException) {}
                if (semaphoreAcquired && Alltrans.cacheAccess.availablePermits() == 0) {
                    Alltrans.cacheAccess.release()
                }
            }
        }

        /**
         * Hooks do TextView expandidos
         */
        private fun applyTextViewHooks() {
            val setTextHook = SetTextHookHandler()

            if (PreferenceList.SetText) {
                // Todas as variantes de setText
                Utils.tryHookMethod(TextView::class.java, "setText", CharSequence::class.java, setTextHook)
                Utils.tryHookMethod(TextView::class.java, "setText", CharSequence::class.java, BufferType::class.java, setTextHook)
                Utils.tryHookMethod(TextView::class.java, "setText", CharSequence::class.java, BufferType::class.java, Boolean::class.javaPrimitiveType, setTextHook)
                Utils.tryHookMethod(TextView::class.java, "setText", CharSequence::class.java, BufferType::class.java, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, setTextHook)

                // Hook append()
                Utils.tryHookMethod(TextView::class.java, "append", CharSequence::class.java, setTextHook)
                Utils.tryHookMethod(TextView::class.java, "append", CharSequence::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, setTextHook)
            }

            if (PreferenceList.SetHint) {
                Utils.tryHookMethod(TextView::class.java, "setHint", CharSequence::class.java, setTextHook)
            }

            // Hook setError()
            Utils.tryHookMethod(TextView::class.java, "setError", CharSequence::class.java, setTextHook)
        }

        /**
         * Hooks do WebView
         */
        private fun applyWebViewHooks(packageName: String) {
            if (PreferenceList.LoadURL) {
                Utils.debugLog("Applying WebView hooks for $packageName")

                Utils.tryHookMethod(WebView::class.java, "onAttachedToWindow",
                    WebViewOnCreateHookHandler())

                Utils.tryHookMethod(WebView::class.java, "setWebViewClient",
                    WebViewClient::class.java, WebViewSetClientHookHandler())
            }
        }

        private fun applyViewHierarchyHooks() {
            if (!PreferenceList.SetText && !PreferenceList.SetHint) return

            Utils.debugLog("Applying View/ViewGroup hooks")
            Utils.tryHookMethod(View::class.java, "onAttachedToWindow", Alltrans.viewHierarchyHook)
            Utils.tryHookMethod(ViewGroup::class.java, "onViewAdded", View::class.java, Alltrans.viewHierarchyHook)
            Utils.tryHookMethod(ViewGroup::class.java, "addView", View::class.java, Alltrans.viewHierarchyHook)
            Utils.tryHookMethod(ViewGroup::class.java, "addView", View::class.java, Int::class.javaPrimitiveType, Alltrans.viewHierarchyHook)
            Utils.tryHookMethod(ViewGroup::class.java, "addView", View::class.java, ViewGroup.LayoutParams::class.java, Alltrans.viewHierarchyHook)
            Utils.tryHookMethod(
                ViewGroup::class.java,
                "addView",
                View::class.java,
                Int::class.javaPrimitiveType,
                ViewGroup.LayoutParams::class.java,
                Alltrans.viewHierarchyHook
            )
        }

        private fun applyFrameworkViewTextHooks() {
            if (!PreferenceList.SetText) return

            val viewTextHook = ViewTextMethodHookHandler()
            Utils.debugLog("Applying framework View text hooks")
            Utils.tryHookMethod(View::class.java, "setContentDescription", CharSequence::class.java, viewTextHook)
            Utils.tryHookMethod(View::class.java, "setTooltipText", CharSequence::class.java, viewTextHook)
            Utils.tryHookMethod(View::class.java, "setAccessibilityPaneTitle", CharSequence::class.java, viewTextHook)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Utils.tryHookMethod(View::class.java, "setStateDescription", CharSequence::class.java, viewTextHook)
            }
        }

        private fun applyStaticLayoutHooks(context: Context) {
            if (!PreferenceList.SetText) return

            Utils.debugLog("Applying StaticLayout hooks")
            Utils.tryHookMethod(
                "android.text.StaticLayout\$Builder",
                context.classLoader,
                "build",
                Alltrans.staticLayoutHook
            )
        }

        private fun applyLegacyLayoutHooks(context: Context) {
            if (!PreferenceList.SetText) return

            val hook = LayoutConstructorHookHandler()
            val classLoader = context.classLoader

            try {
                XposedHelpers.findAndHookConstructor(
                    "android.text.StaticLayout",
                    classLoader,
                    CharSequence::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    TextPaint::class.java,
                    Int::class.javaPrimitiveType,
                    Layout.Alignment::class.java,
                    Class.forName("android.text.TextDirectionHeuristic"),
                    Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    TextUtils.TruncateAt::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    hook
                )
            } catch (_: Throwable) {
            }

            try {
                XposedHelpers.findAndHookConstructor(
                    "android.text.StaticLayout",
                    classLoader,
                    CharSequence::class.java,
                    TextPaint::class.java,
                    Int::class.javaPrimitiveType,
                    Layout.Alignment::class.java,
                    Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    hook
                )
            } catch (_: Throwable) {
            }

            try {
                XposedHelpers.findAndHookConstructor(
                    "android.text.BoringLayout",
                    classLoader,
                    CharSequence::class.java,
                    TextPaint::class.java,
                    Int::class.javaPrimitiveType,
                    Layout.Alignment::class.java,
                    Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                    Class.forName("android.text.BoringLayout\$Metrics"),
                    Boolean::class.javaPrimitiveType,
                    hook
                )
            } catch (_: Throwable) {
            }

            try {
                XposedHelpers.findAndHookConstructor(
                    "android.text.BoringLayout",
                    classLoader,
                    CharSequence::class.java,
                    TextPaint::class.java,
                    Int::class.javaPrimitiveType,
                    Layout.Alignment::class.java,
                    Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                    Class.forName("android.text.BoringLayout\$Metrics"),
                    Boolean::class.javaPrimitiveType,
                    TextUtils.TruncateAt::class.java,
                    Int::class.javaPrimitiveType,
                    hook
                )
            } catch (_: Throwable) {
            }
        }

        /**
         * Hooks de Notificações
         */
        private fun applyNotificationHooks() {
            if (PreferenceList.Notif) {
                Utils.debugLog("Applying Notification hooks")

                XposedBridge.hookAllMethods(NotificationManager::class.java, "notify",
                    Alltrans.notifyHook)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    try {
                        XposedHelpers.findAndHookMethod(NotificationManager::class.java,
                            "notifyAsUser", String::class.java, Int::class.javaPrimitiveType,
                            Notification::class.java, UserHandle::class.java, Alltrans.notifyHook)
                    } catch (t: Throwable) {
                        Utils.debugLog("notifyAsUser hook failed: ${t.message}")
                    }
                }
            }
        }

        /**
         * Limpa o cache se necessário
         */
        private fun clearCacheIfNeeded(context: Context?, localClearRequestTimestamp: Long) {
            Utils.debugLog("clearCacheIfNeeded: localClearRequestTimestamp = $localClearRequestTimestamp")

            if (context == null) {
                Utils.debugLog("clearCacheIfNeeded: Context is null")
                return
            }

            Alltrans.cacheAccess.acquireUninterruptibly()
            var lastActualGlobalClearTime: Long = 0L

            var fis: FileInputStream? = null
            var ois: ObjectInputStream? = null
            try {
                fis = context.openFileInput("AllTransCacheClear")
                ois = ObjectInputStream(fis)
                lastActualGlobalClearTime = ois.readObject() as Long
                Utils.debugLog("lastActualGlobalClearTime: $lastActualGlobalClearTime")
            } catch (e: FileNotFoundException) {
                Utils.debugLog("'AllTransCacheClear' not found, assuming 0L")
                lastActualGlobalClearTime = 0L
            } catch (e: Throwable) {
                Utils.debugLog("Error reading 'AllTransCacheClear': ${e.message}")
                lastActualGlobalClearTime = 0L
            } finally {
                try { ois?.close() } catch (ignored: IOException) {}
                try { fis?.close() } catch (ignored: IOException) {}
            }

            if (localClearRequestTimestamp > 0L &&
                localClearRequestTimestamp > lastActualGlobalClearTime) {
                Utils.debugLog("Clearing cache for ${context.packageName}...")

                var fos: FileOutputStream? = null
                var oos: ObjectOutputStream? = null

                try {
                    val cacheFile = File(context.filesDir, "AllTransCache")
                    if (cacheFile.exists() && cacheFile.delete()) {
                        Utils.debugLog("Disk cache deleted")
                    }

                    Alltrans.cache?.evictAll()
                    Utils.debugLog("In-memory cache evicted")

                    fos = context.openFileOutput("AllTransCacheClear", Context.MODE_PRIVATE)
                    oos = ObjectOutputStream(fos)
                    oos.writeObject(localClearRequestTimestamp)
                    Utils.debugLog("Updated 'AllTransCacheClear' to $localClearRequestTimestamp")

                } catch (e: Throwable) {
                    Log.e("AllTrans", "Error clearing cache", e)
                } finally {
                    try { oos?.close() } catch (ignored: IOException) {}
                    try { fos?.close() } catch (ignored: IOException) {}
                }
            } else if (localClearRequestTimestamp > 0L) {
                Utils.debugLog("No need to clear cache - not newer than last clear")
            } else {
                Utils.debugLog("No pending clear request (timestamp is 0L)")
            }

            if (Alltrans.cacheAccess.availablePermits() == 0) {
                Alltrans.cacheAccess.release()
            }
        }
    }

    // ============================================================================
    // MÉTODOS DA INSTÂNCIA (não do companion object)
    // ============================================================================

    override fun afterHookedMethod(methodHookParam: MethodHookParam) {
        try {
            if (methodHookParam.args == null || methodHookParam.args.isEmpty() ||
                (methodHookParam.args[0] !is Context)) {
                Utils.debugLog("AllTrans: Invalid args in attachBaseContext hook.")
                return
            }

            val baseContext = methodHookParam.args[0] as Context
            val packageName = baseContext.packageName

            // Verificar se é um serviço do sistema crítico que deve ser ignorado
            if (isCriticalSystemService(packageName)) {
                Utils.debugLog("AllTrans: Skipping critical system service: $packageName")
                return
            }

            Utils.debugLog("AllTrans: in after attachBaseContext of ContextWrapper for package $packageName")

            // Tentativa progressiva de obter um contexto válido
            var usableContext: Context? = null

            val appContext = baseContext.applicationContext
            if (appContext != null) {
                usableContext = appContext
                Utils.debugLog("AllTrans: Got application context from baseContext")
            } else if (methodHookParam.thisObject is ContextWrapper) {
                try {
                    val wrapper = methodHookParam.thisObject as ContextWrapper
                    val wrapperAppContext = wrapper.applicationContext
                    if (wrapperAppContext != null) {
                        usableContext = wrapperAppContext
                        Utils.debugLog("AllTrans: Got application context from ContextWrapper.thisObject")
                    }
                } catch (e: Exception) {
                    Utils.debugLog("AllTrans: Error accessing ContextWrapper.applicationContext: ${e.message}")
                }

                if (usableContext == null) {
                    usableContext = baseContext
                    Utils.debugLog("AllTrans: Using baseContext directly as fallback")
                }
            }

            if (usableContext == null) {
                Utils.debugLog("AllTrans: Unable to find a usable context for package $packageName")
                return
            }

            // Define o contexto global se ainda não estiver definido
            if (Alltrans.context == null) {
                Alltrans.context = usableContext
                Utils.debugLog("AllTrans: Application context set successfully for package: $packageName")
            }

            // Inicializar a chave da tag
            Alltrans.initializeTagKeyIfNeeded()

            // Evitar chamar readPrefAndHook para o próprio pacote AllTrans
            if (packageName != "chanhnh.alltrans") {
                try {
                    readPrefAndHook(usableContext)
                } catch (e: Throwable) {
                    Utils.debugLog("AllTrans: Error in readPrefAndHook from attachBaseContext: ${e.message}")
                }
            } else {
                Utils.debugLog("AllTrans: Skipping readPrefAndHook for own package.")
            }
        } catch (e: Throwable) {
            Utils.debugLog("Caught Exception in attachBaseContext ${Log.getStackTraceString(e)}")
            XposedBridge.log(e)
        }
    }

    /**
     * Verifica se o pacote é um serviço do sistema CRÍTICO que deve ser ignorado
     */
    private fun isCriticalSystemService(packageName: String): Boolean {
        return packageName.startsWith("com.android.server.") ||
                packageName.startsWith("com.android.internal.") ||
                packageName == "android" ||
                packageName == "system" ||
                packageName == "com.android.systemui" ||
                packageName == "com.android.providers.settings"
    }
}
