package chanhnh.alltrans

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import android.content.res.XModuleResources
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.CancellationSignal
import android.util.Log
import android.util.LruCache
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.Collections
import java.util.HashSet
import java.util.WeakHashMap
import java.util.concurrent.Semaphore

class Alltrans : IXposedHookLoadPackage, IXposedHookZygoteInit {

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        Log.i("AllTransZygoteInit", "initZygote called. Current MODULE_PATH: $MODULE_PATH")
        Log.i("AllTransZygoteInit", "startupParam.modulePath: ${startupParam.modulePath}")

        if (startupParam.modulePath != null && startupParam.modulePath.isNotEmpty()) {
            if (MODULE_PATH == null || MODULE_PATH != startupParam.modulePath) {
                MODULE_PATH = startupParam.modulePath
                Log.i("AllTransZygoteInit", "MODULE_PATH has been set to: $MODULE_PATH")
            } else {
                Log.i("AllTransZygoteInit", "MODULE_PATH was already correctly set to: $MODULE_PATH")
            }
        } else {
            Log.e("AllTransZygoteInit", "ERROR: startupParam.modulePath is null or empty!")
        }
    }

    @Throws(Throwable::class)
    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        XposedBridge.log("AllTrans: handleLoadPackage for ${lpparam.packageName}. Current MODULE_PATH at entry: $MODULE_PATH")

        if (lpparam.packageName == "chanhnh.alltrans") {
            XposedBridge.log("AllTrans: [OWN_PACKAGE_LOG] Processing own package: ${lpparam.packageName}, Process: ${lpparam.processName}")

            if (MODULE_PATH == null) {
                XposedBridge.log("AllTrans: [OWN_PACKAGE_LOG] WARNING - MODULE_PATH is NULL after initZygote. Attempting to set from lpparam.")
                var pathSetInHandleLoad = false
                if (lpparam.appInfo != null) {
                    if (lpparam.appInfo.sourceDir != null && lpparam.appInfo.sourceDir.isNotEmpty()) {
                        MODULE_PATH = lpparam.appInfo.sourceDir
                        XposedBridge.log("AllTrans: [OWN_PACKAGE_LOG] MODULE_PATH set via sourceDir (in handleLoadPackage): $MODULE_PATH")
                        pathSetInHandleLoad = true
                    } else if (lpparam.appInfo.publicSourceDir != null && lpparam.appInfo.publicSourceDir.isNotEmpty()) {
                        MODULE_PATH = lpparam.appInfo.publicSourceDir
                        XposedBridge.log("AllTrans: [OWN_PACKAGE_LOG] MODULE_PATH set via publicSourceDir (in handleLoadPackage): $MODULE_PATH")
                        pathSetInHandleLoad = true
                    }
                }
                if (!pathSetInHandleLoad) {
                    XposedBridge.log("AllTrans: [OWN_PACKAGE_LOG] CRITICAL ERROR - Failed to set MODULE_PATH from lpparam in handleLoadPackage as well.")
                    if (lpparam.appInfo == null) {
                        XposedBridge.log("AllTrans: [OWN_PACKAGE_LOG] Reason: lpparam.appInfo is NULL for own package.")
                    } else {
                        XposedBridge.log("AllTrans: [OWN_PACKAGE_LOG] appInfo.sourceDir: ${lpparam.appInfo.sourceDir}, appInfo.publicSourceDir: ${lpparam.appInfo.publicSourceDir}")
                    }
                }
            } else {
                XposedBridge.log("AllTrans: [OWN_PACKAGE_LOG] MODULE_PATH was already set by initZygote: $MODULE_PATH")
                if (lpparam.appInfo != null && lpparam.appInfo.sourceDir != null && MODULE_PATH != lpparam.appInfo.sourceDir) {
                    XposedBridge.log("AllTrans: [OWN_PACKAGE_LOG] Mismatch Warning: MODULE_PATH ($MODULE_PATH) vs lpparam.appInfo.sourceDir (${lpparam.appInfo.sourceDir})")
                }
            }

            Utils.tryHookMethod(Application::class.java, "onCreate", AppOnCreateHookHandler())
            Utils.tryHookMethod(
                ContextWrapper::class.java,
                "attachBaseContext",
                Context::class.java,
                AttachBaseContextHookHandler()
            )
            return
        }

        if (lpparam.appInfo == null) {
            XposedBridge.log("AllTrans: Skipping package with null appInfo: ${lpparam.packageName}")
            return
        }

        if ((lpparam.appInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) {
            if ("com.android.providers.settings" != lpparam.packageName && "chanhnh.alltrans" != lpparam.packageName) {
                XposedBridge.log("AllTrans: Skipping system app: ${lpparam.packageName}")
                return
            }
        }

        if ("com.android.providers.settings" == lpparam.packageName) {
            XposedBridge.log("AllTrans: Hooking Settings Provider for proxy functionality.")
            if (!settingsHooked) {
                try {
                    hookSettingsProviderMethods(lpparam)
                    settingsHooked = true
                    XposedBridge.log("AllTrans: Settings Provider hooks applied successfully.")
                } catch (e: Exception) {
                    XposedBridge.log("AllTrans: Failed to hook Settings Provider for package ${lpparam.packageName}: " + e.message)
                    XposedBridge.log(e)
                }
            } else {
                XposedBridge.log("AllTrans: Settings Provider already hooked.")
            }
            return
        }

        XposedBridge.log("AllTrans: Processing package: ${lpparam.packageName} (MODULE_PATH check: $MODULE_PATH)")

        cache

        Utils.tryHookMethod(Application::class.java, "onCreate", AppOnCreateHookHandler())
        Utils.tryHookMethod(
            ContextWrapper::class.java,
            "attachBaseContext",
            Context::class.java,
            AttachBaseContextHookHandler()
        )

        val packageName = lpparam.packageName
        val isSystemPackage = packageName.startsWith("android.") ||
                packageName.startsWith("com.android.") ||
                packageName == "system" ||
                packageName == "com.google.android.webview"

        if (!isSystemPackage) {
            try {
                val webViewClass = lpparam.classLoader.loadClass("android.webkit.WebView")
                Utils.debugLog("AllTrans: WebView class found in $packageName")

                Utils.tryHookMethod(
                    webViewClass,
                    "onAttachedToWindow",
                    WebViewOnCreateHookHandler()
                )
                Utils.debugLog("AllTrans: WebView.onAttachedToWindow hook applied successfully for $packageName")

                Utils.tryHookMethod(
                    webViewClass,
                    "setWebViewClient",
                    WebViewClient::class.java,
                    WebViewSetClientHookHandler()
                )
                Utils.debugLog("AllTrans: WebView.setWebViewClient hook applied successfully for $packageName")

                try {
                    XposedBridge.hookAllMethods(webViewClass, "onDetachedFromWindow", object : XC_MethodHook() {
                        @Throws(Throwable::class)
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val webView = param.thisObject as? WebView
                            if (webView != null) {
                                val removedInstance = webViewHookInstances.remove(webView)
                                if (removedInstance != null) {
                                    Utils.debugLog("AllTrans: Cleaned up WebView instance on detach (hookAllMethods): ${webView.hashCode()}")
                                }
                            }
                        }
                    })
                    Utils.debugLog("AllTrans: WebView.onDetachedFromWindow hook (hookAllMethods) applied for $packageName")
                } catch (e: Throwable) {
                    Utils.debugLog("AllTrans: Failed to hook WebView.onDetachedFromWindow (hookAllMethods) for $packageName: ${e.message}")
                    XposedBridge.log(e)
                }

            } catch (e: ClassNotFoundException) {
                Utils.debugLog("AllTrans: WebView class not found in $packageName for main hooks: " + e.message)
            } catch (e: Exception) {
                Utils.debugLog("AllTrans: Error setting up main WebView hooks for $packageName: " + e.message)
                XposedBridge.log(e)
            }
        } else {
            Utils.debugLog("AllTrans: Skipping main WebView hooks for system package: $packageName")
        }
    }

    @Throws(Throwable::class)
    private fun hookSettingsProviderMethods(lpparam: LoadPackageParam) {
        val clsSet: Class<*>? = try {
            Class.forName(
                "com.android.providers.settings.SettingsProvider",
                false,
                lpparam.classLoader
            )
        } catch (e: ClassNotFoundException) {
            XposedBridge.log("AllTrans: SettingsProvider class not found in ${lpparam.packageName}: " + e.message)
            return
        }

        if (clsSet != null) {
            hookSettingsQuery(clsSet)
            hookSettingsCall(clsSet)
        }
    }

    @Throws(Throwable::class)
    private fun hookSettingsQuery(clsSet: Class<*>) {
        XposedBridge.log("AllTrans: Trying to hook SettingsProvider.query method.")
        val mQuery: Method? = try {
            clsSet.getDeclaredMethod(
                "query",
                Uri::class.java,
                Array<String>::class.java,
                Bundle::class.java,
                CancellationSignal::class.java
            ).also {
                XposedBridge.log("AllTrans: Found SettingsProvider.query (Uri, String[], Bundle, CancellationSignal) method.")
            }
        } catch (e1: NoSuchMethodException) {
            XposedBridge.log("AllTrans: Query signature with Bundle not found, trying (Uri, String[], String, String[], String, CancellationSignal)...")
            try {
                clsSet.getDeclaredMethod(
                    "query",
                    Uri::class.java,
                    Array<String>::class.java,
                    String::class.java,
                    Array<String>::class.java,
                    String::class.java,
                    CancellationSignal::class.java
                ).also {
                    XposedBridge.log("AllTrans: Found SettingsProvider.query (Uri, String[], String, String[], String, CancellationSignal) method.")
                }
            } catch (e2: NoSuchMethodException) {
                XposedBridge.log("AllTrans: Query signature with CancellationSignal not found, trying (Uri, String[], String, String[], String)...")
                try {
                    clsSet.getDeclaredMethod(
                        "query",
                        Uri::class.java,
                        Array<String>::class.java,
                        String::class.java,
                        Array<String>::class.java,
                        String::class.java
                    ).also {
                        XposedBridge.log("AllTrans: Found SettingsProvider.query (Uri, String[], String, String[], String) method.")
                    }
                } catch (e3: NoSuchMethodException) {
                    XposedBridge.log("AllTrans: Could not find any known SettingsProvider.query method signature!")
                    return
                }
            }
        }

        XposedBridge.log("AllTrans: Applying hook to SettingsProvider.query method.")
        XposedBridge.hookMethod(mQuery, object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args == null || param.args.isEmpty() || (param.args[0] !is Uri)) return
                val uri = param.args[0] as Uri
                val uriString = uri.toString()
                if (uriString.startsWith("content://settings/system/alltransProxyProviderURI/")) {
                    XposedBridge.log("AllTrans Proxy Hook: Intercepted query URI: $uriString")
                    val originalProviderUriString = uriString.replaceFirst(
                        "content://settings/system/alltransProxyProviderURI/".toRegex(),
                        "content://"
                    )
                    XposedBridge.log("AllTrans Proxy Hook: Rewritten query URI: $originalProviderUriString")
                    val newUri = originalProviderUriString.toUri()

                    val ident = Binder.clearCallingIdentity()
                    var cursor: Cursor? = null
                    var providerContext: Context? = null
                    try {
                        val settingsProviderInstance = param.thisObject
                        try {
                            val mGetContext =
                                settingsProviderInstance.javaClass.getMethod("getContext")
                            providerContext =
                                mGetContext.invoke(settingsProviderInstance) as Context?
                        } catch (e: NoSuchMethodException) {
                            XposedBridge.log("AllTrans Proxy Hook: SettingsProvider.getContext() method not found: ${e.message}")
                            try {
                                providerContext = XposedHelpers.getObjectField(
                                    settingsProviderInstance,
                                    "mContext"
                                ) as Context?
                            } catch (fieldEx: Exception) {
                                XposedBridge.log("AllTrans Proxy Hook: Failed to get context from SettingsProvider field 'mContext': ${fieldEx.message}")
                                XposedBridge.log(fieldEx)
                            }
                        } catch (e: IllegalAccessException) {
                            XposedBridge.log("AllTrans Proxy Hook: IllegalAccessException for SettingsProvider.getContext(): ${e.message}")
                            try {
                                providerContext = XposedHelpers.getObjectField(
                                    settingsProviderInstance,
                                    "mContext"
                                ) as Context?
                            } catch (fieldEx: Exception) {
                                XposedBridge.log("AllTrans Proxy Hook: Failed to get context from SettingsProvider field 'mContext' after IllegalAccess: ${fieldEx.message}")
                                XposedBridge.log(fieldEx)
                            }
                        } catch (e: InvocationTargetException) {
                            XposedBridge.log("AllTrans Proxy Hook: InvocationTargetException for SettingsProvider.getContext(): ${e.message}")
                            XposedBridge.log(e.targetException ?: e)
                            try {
                                providerContext = XposedHelpers.getObjectField(
                                    settingsProviderInstance,
                                    "mContext"
                                ) as Context?
                            } catch (fieldEx: Exception) {
                                XposedBridge.log("AllTrans Proxy Hook: Failed to get context from SettingsProvider field 'mContext' after InvocationTarget: ${fieldEx.message}")
                                XposedBridge.log(fieldEx)
                            }
                        }

                        if (providerContext == null) {
                            XposedBridge.log("AllTrans Proxy Hook: Provider context is null for URI $newUri, cannot execute proxy query.")
                            param.setResult(null)
                            return
                        }

                        val projectionArg = param.args[1]
                        val projection = if (projectionArg is Array<*>) {
                            @Suppress("UNCHECKED_CAST")
                            projectionArg as? Array<String?>
                        } else null

                        var cancellationSignal: CancellationSignal? = null
                        for (arg in param.args) {
                            if (arg is CancellationSignal) {
                                cancellationSignal = arg
                                break
                            }
                        }
                        val methodParams = (param.method as Method).parameterTypes
                        if (methodParams.size > 2 && methodParams[2] == Bundle::class.java) {
                            val queryArgsBundle = param.args[2] as Bundle?
                            XposedBridge.log("AllTrans Proxy Hook: Querying using Bundle signature.")
                            cursor = providerContext.contentResolver
                                .query(newUri, projection, queryArgsBundle, cancellationSignal)
                        } else if (methodParams.size > 4) {
                            val selection = param.args[2] as String?
                            val selectionArgsArg = param.args[3]
                            val selectionArgs = if (selectionArgsArg is Array<*>) {
                                @Suppress("UNCHECKED_CAST")
                                selectionArgsArg as? Array<String?>
                            } else null
                            val sortOrder = param.args[4] as String?
                            XposedBridge.log("AllTrans Proxy Hook: Querying using String signature (Uri, String[], String, String[], String, CancellationSignal).")
                            cursor = providerContext.contentResolver.query(
                                newUri,
                                projection,
                                selection,
                                selectionArgs,
                                sortOrder,
                                cancellationSignal
                            )
                        } else {
                            XposedBridge.log("AllTrans Proxy Hook: Querying using simpler signature for URI $newUri (Uri, String[], null, null, null).")
                            cursor = providerContext.contentResolver
                                .query(newUri, projection, null, null, null, cancellationSignal)
                        }

                        param.setResult(cursor)
                        XposedBridge.log(
                            "AllTrans Proxy Hook: Setting query result for URI $newUri (${if (cursor != null) cursor.count.toString() + " rows" else "null"})."
                        )
                    } catch (e: Exception) {
                        XposedBridge.log("AllTrans Proxy Hook: Error during proxy query execution for URI $newUri: ${e.message}")
                        XposedBridge.log(e)
                        param.setResult(null)
                        cursor?.close()
                    } finally {
                        Binder.restoreCallingIdentity(ident)
                    }
                }
            }
        })
    }

    @Throws(Throwable::class)
    private fun hookSettingsCall(clsSet: Class<*>) {
        XposedBridge.log("AllTrans: Trying to hook SettingsProvider.call method.")
        val mCall: Method? = try {
            clsSet.getDeclaredMethod(
                "call",
                String::class.java,
                String::class.java,
                String::class.java,
                Bundle::class.java
            ).also {
                XposedBridge.log("AllTrans: Found SettingsProvider.call (String authority, String method, String arg, Bundle extras) method.")
            }
        } catch (e1: NoSuchMethodException) {
            XposedBridge.log("AllTrans: Call signature (String authority, String method, String arg, Bundle extras) not found, trying (String method, String arg, Bundle extras)...")
            try {
                clsSet.getDeclaredMethod(
                    "call",
                    String::class.java,
                    String::class.java,
                    Bundle::class.java
                ).also {
                    XposedBridge.log("AllTrans: Found SettingsProvider.call (String method, String arg, Bundle extras) method.")
                }
            } catch (e2: NoSuchMethodException) {
                XposedBridge.log("AllTrans: Could not find any known SettingsProvider.call method signature!")
                return
            }
        }

        if (mCall == null) {
            XposedBridge.log("AllTrans: ERROR - mCall is null in hookSettingsCall, cannot apply hook.")
            return
        }

        XposedBridge.log("AllTrans: Applying hook to SettingsProvider.call method.")
        XposedBridge.hookMethod(mCall, object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: MethodHookParam) {
                val paramTypes = (param.method as Method).parameterTypes
                val method: String?
                val extras: Bundle?

                if (paramTypes.size == 4 &&
                    paramTypes[0] == String::class.java &&
                    paramTypes[1] == String::class.java &&
                    paramTypes[2] == String::class.java &&
                    paramTypes[3] == Bundle::class.java
                ) {
                    method = param.args[1] as String?
                    extras = param.args[3] as Bundle?
                    if (PreferenceList.Debug || "alltransProxyCall" == method) {
                        XposedBridge.log("AllTrans Proxy Hook: Matched call signature (String authority, String method, String arg, Bundle extras)")
                    }
                    handleProxyCall(param, method, extras)
                }
                else if (paramTypes.size == 3 &&
                    paramTypes[0] == String::class.java &&
                    paramTypes[1] == String::class.java &&
                    paramTypes[2] == Bundle::class.java
                ) {
                    method = param.args[0] as String?
                    extras = param.args[2] as Bundle?
                    if (PreferenceList.Debug || "alltransProxyCall" == method) {
                        XposedBridge.log("AllTrans Proxy Hook: Matched call signature (String method, String arg, Bundle extras)")
                    }
                    handleProxyCall(param, method, extras)
                } else {
                    XposedBridge.log("AllTrans Proxy Hook: Unexpected SettingsProvider.call signature. Params: ${paramTypes.joinToString { it.simpleName }}")
                }
            }

            private fun handleProxyCall(param: MethodHookParam, method: String?, extras: Bundle?) {
                if ("alltransProxyCall" == method) {
                    if (extras == null) {
                        XposedBridge.log("Proxy call: extras is null!")
                        param.setResult(null)
                        return
                    }

                    val originalUriString = extras.getString("alltransOriginalUri")
                    val originalMethod = extras.getString("alltransOriginalMethod")
                    val originalArg = extras.getString("alltransOriginalArg")

                    if (originalUriString == null || originalMethod == null) {
                        XposedBridge.log("Proxy call: original URI/Method missing!")
                        param.setResult(null)
                        return
                    }
                    val originalUri = originalUriString.toUri()

                    extras.remove("alltransOriginalUri")
                    extras.remove("alltransOriginalMethod")
                    extras.remove("alltransOriginalArg")

                    XposedBridge.log("AllTrans Proxy Hook: Intercepted call for $originalUri, Method: $originalMethod, Arg: $originalArg")

                    val ident = Binder.clearCallingIdentity()
                    var resultBundle: Bundle?
                    var providerContext: Context? = null
                    try {
                        val settingsProviderInstance = param.thisObject
                        try {
                            val mGetContext =
                                settingsProviderInstance.javaClass.getMethod("getContext")
                            providerContext =
                                mGetContext.invoke(settingsProviderInstance) as Context?
                        } catch (e: NoSuchMethodException) {
                            XposedBridge.log("AllTrans Proxy Hook: SettingsProvider.getContext() method not found for call: ${e.message}")
                            try {
                                providerContext = XposedHelpers.getObjectField(
                                    settingsProviderInstance,
                                    "mContext"
                                ) as Context?
                            } catch (fieldEx: Exception) {
                                XposedBridge.log("AllTrans Proxy Hook: Failed to get context from SettingsProvider field 'mContext' for call: ${fieldEx.message}")
                                XposedBridge.log(fieldEx)
                            }
                        } catch (e: IllegalAccessException) {
                            XposedBridge.log("AllTrans Proxy Hook: IllegalAccessException for SettingsProvider.getContext() for call: ${e.message}")
                            try {
                                providerContext = XposedHelpers.getObjectField(
                                    settingsProviderInstance,
                                    "mContext"
                                ) as Context?
                            } catch (fieldEx: Exception) {
                                XposedBridge.log("AllTrans Proxy Hook: Failed to get context from SettingsProvider field 'mContext' after IllegalAccess for call: ${fieldEx.message}")
                                XposedBridge.log(fieldEx)
                            }
                        } catch (e: InvocationTargetException) {
                            XposedBridge.log("AllTrans Proxy Hook: InvocationTargetException for SettingsProvider.getContext() for call: ${e.message}")
                            XposedBridge.log(e.targetException ?: e)
                            try {
                                providerContext = XposedHelpers.getObjectField(
                                    settingsProviderInstance,
                                    "mContext"
                                ) as Context?
                            } catch (fieldEx: Exception) {
                                XposedBridge.log("AllTrans Proxy Hook: Failed to get context from SettingsProvider field 'mContext' after InvocationTarget for call: ${fieldEx.message}")
                                XposedBridge.log(fieldEx)
                            }
                        }
                        if (providerContext == null) {
                            XposedBridge.log("Proxy call: Failed to get context for $originalUri, Method: $originalMethod!")
                            param.setResult(null)
                            return
                        }

                        XposedBridge.log("AllTrans Proxy Hook: Calling actual provider: $originalUri, Method: $originalMethod, Arg: $originalArg")
                        resultBundle = providerContext.contentResolver
                            .call(originalUri, originalMethod, originalArg, extras)
                        param.setResult(resultBundle)
                        XposedBridge.log("AllTrans Proxy Hook: Setting call result for $originalUri, Method: $originalMethod: $resultBundle")
                    } catch (e: Exception) {
                        XposedBridge.log("AllTrans Proxy Hook: Error during proxy call execution for $originalUri, Method: $originalMethod: ${e.message}")
                        XposedBridge.log(e)
                        param.setResult(null)
                    } finally {
                        Binder.restoreCallingIdentity(ident)
                    }
                }
            }
        })
    }

    companion object {
        val cacheAccess: Semaphore = Semaphore(1, true)

        @SuppressLint("StaticFieldLeak")
        val viewHierarchyHook: ViewHierarchyHookHandler = ViewHierarchyHookHandler()

        @SuppressLint("StaticFieldLeak")
        val staticLayoutHook: StaticLayoutHookHandler = StaticLayoutHookHandler()

        @SuppressLint("StaticFieldLeak")
        val notifyHook: NotificationHookHandler = NotificationHookHandler()

        private const val CACHE_MAX_SIZE = 200
        private var _cache: LruCache<String, String>? = null

        var cache: LruCache<String, String>?
            get() {
                if (_cache == null) {
                    synchronized(Alltrans::class.java) {
                        if (_cache == null) {
                            _cache = LruCache(CACHE_MAX_SIZE)
                            Utils.debugLog("AllTrans: LruCache inicializado com tamanho $CACHE_MAX_SIZE")
                        }
                    }
                }
                return _cache
            }
            set(value) {
                synchronized(Alltrans::class.java) {
                    _cache = value
                }
            }

        @SuppressLint("StaticFieldLeak")
        var context: Context? = null
        var settingsHooked: Boolean = false

        var isProxyEnabled: Boolean = true

        val pendingTextViewTranslations: MutableSet<Int> = Collections.synchronizedSet(
            HashSet()
        )

        // Tag keys used in TextViews to track translation state
        const val ALLTRANS_TRANSLATION_APPLIED_TAG_KEY: Int = 0x7A117AA6
        const val ALLTRANS_PENDING_TRANSLATION_TAG_KEY: Int = 0x7A117AA7
        const val ALLTRANS_PENDING_SPAN_PAYLOAD_TAG_KEY: Int = 0x7A117AA8

        private const val WEBVIEW_HOOK_TAG_KEY_DEFAULT: Int = 0x7f080001
        private var WEBVIEW_HOOK_TAG_KEY: Int = WEBVIEW_HOOK_TAG_KEY_DEFAULT
        private var tagKeyInitialized = false
        private var MODULE_PATH: String? = null

        private const val TAG_RESOURCE_NAME = "tag_alltrans_webview_hook"
        private const val TAG_RESOURCE_TYPE = "id"

        val webViewHookInstances: MutableMap<WebView?, VirtWebViewOnLoad?> =
            Collections.synchronizedMap(
                WeakHashMap()
            )

        fun cleanupWebViewHooks() {
            synchronized(webViewHookInstances) {
                webViewHookInstances.keys.removeIf { it == null }
            }
        }



        @Synchronized
        fun initializeTagKeyIfNeeded() {
            if (MODULE_PATH == null) {
                XposedBridge.log("AllTrans: CRITICAL - initializeTagKeyIfNeeded() chamado, mas MODULE_PATH é NULL. Usando chave de tag padrão.")
                tagKeyInitialized = true
                WEBVIEW_HOOK_TAG_KEY = WEBVIEW_HOOK_TAG_KEY_DEFAULT
                XposedBridge.log(
                    "AllTrans: Usando fallback WEBVIEW_HOOK_TAG_KEY (devido a MODULE_PATH nulo): $WEBVIEW_HOOK_TAG_KEY (0x${Integer.toHexString(WEBVIEW_HOOK_TAG_KEY)})"
                )
                return
            }

            if (!tagKeyInitialized && context != null) {
                var successfullyInitializedDynamicKey = false
                try {
                    XposedBridge.log("AllTrans: Attempting to initialize tag key dynamically with MODULE_PATH: $MODULE_PATH")
                    val localModulePath = MODULE_PATH

                    val modRes: Resources = XModuleResources.createInstance(localModulePath, null)
                    val dynamicKey = modRes.getIdentifier(TAG_RESOURCE_NAME, TAG_RESOURCE_TYPE, "chanhnh.alltrans")

                    if (dynamicKey != 0) {
                        WEBVIEW_HOOK_TAG_KEY = dynamicKey
                        successfullyInitializedDynamicKey = true
                        XposedBridge.log(
                            "AllTrans: Successfully initialized WEBVIEW_HOOK_TAG_KEY dynamically to: $WEBVIEW_HOOK_TAG_KEY (0x${Integer.toHexString(WEBVIEW_HOOK_TAG_KEY)}) using resource '$TAG_RESOURCE_NAME' from $localModulePath"
                        )
                    } else {
                        XposedBridge.log("AllTrans: Resource ID for '$TAG_RESOURCE_NAME' not found via getIdentifier (returned 0) from $localModulePath. Using fallback.")
                    }
                } catch (e: Resources.NotFoundException) {
                    XposedBridge.log("AllTrans: XModuleResources.createInstance failed or resource '$TAG_RESOURCE_NAME' not found from $MODULE_PATH: ${e.message}")
                    XposedBridge.log(e)
                } catch (e: Exception) {
                    XposedBridge.log("AllTrans: Error initializing XModuleResources or getting dynamic tag key for '$TAG_RESOURCE_NAME' from $MODULE_PATH: ${e.message}")
                    XposedBridge.log(e)
                } finally {
                    tagKeyInitialized = true
                    if (!successfullyInitializedDynamicKey) {
                        WEBVIEW_HOOK_TAG_KEY = WEBVIEW_HOOK_TAG_KEY_DEFAULT
                        XposedBridge.log(
                            "AllTrans: Using fallback WEBVIEW_HOOK_TAG_KEY (inicialização dinâmica falhou): $WEBVIEW_HOOK_TAG_KEY (0x${Integer.toHexString(WEBVIEW_HOOK_TAG_KEY)})"
                        )
                    }
                }
            } else if (tagKeyInitialized) {
                Utils.debugLog("AllTrans: Tag key already initialized: $WEBVIEW_HOOK_TAG_KEY (0x${Integer.toHexString(WEBVIEW_HOOK_TAG_KEY)})")
            } else {
                val localContext = context
                val localModulePath = MODULE_PATH
                Utils.debugLog("AllTrans: Cannot initialize tag key yet. Context: $localContext, MODULE_PATH: $localModulePath, TagKeyInitialized: $tagKeyInitialized")
            }
        }
    }
}
