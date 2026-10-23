package chanhnh.alltrans

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.CancellationSignal
import android.util.LruCache
import android.webkit.WebView
import androidx.core.net.toUri
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.Collections
import java.util.HashSet
import java.util.WeakHashMap
import java.util.concurrent.Semaphore

class Alltrans : XposedModule() {

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        HookRuntime.attach(this)
        HookRuntime.log("AllTrans: module loaded in ${param.processName} (systemServer=${param.isSystemServer})")
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        val packageName = param.packageName
        HookRuntime.log("AllTrans: onPackageLoaded for $packageName")

        val appInfo = param.applicationInfo

        // The settings provider implements the cross-app preference/translation proxy.
        // Modern LSPosed exposes system_server as "system"; older builds may report "android".
        if (packageName == "com.android.providers.settings" ||
            packageName == "system" ||
            packageName == "android"
        ) {
            HookRuntime.log("AllTrans: Hooking Settings Provider for proxy functionality.")
            if (!settingsHooked) {
                try {
                    settingsHooked = hookSettingsProviderMethods(packageName, param.defaultClassLoader)
                    if (settingsHooked) {
                        HookRuntime.log("AllTrans: Settings Provider hooks applied successfully.")
                    } else {
                        HookRuntime.log("AllTrans: Settings Provider not available in $packageName yet; will retry.")
                    }
                } catch (e: Throwable) {
                    HookRuntime.log("AllTrans: Failed to hook Settings Provider for package $packageName: ${e.message}", e)
                }
            } else {
                HookRuntime.log("AllTrans: Settings Provider already hooked.")
            }
            return
        }

        if ((appInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) {
            HookRuntime.log("AllTrans: Skipping system app: $packageName")
            return
        }

        // API 102 calls onPackageLoaded for every package loaded in the process.
        // Only the package that started the process should install the app-wide hooks.
        if (!param.isFirstPackage) {
            HookRuntime.log("AllTrans: Skipping non-first package: $packageName")
            return
        }

        HookRuntime.log("AllTrans: Processing package: $packageName")

        cache

        Utils.tryHookMethod(Application::class.java, "onCreate", AppOnCreateHookHandler())
        Utils.tryHookMethod(
            ContextWrapper::class.java,
            "attachBaseContext",
            Context::class.java,
            AttachBaseContextHookHandler()
        )

    }

    @Throws(Throwable::class)
    private fun hookSettingsProviderMethods(packageName: String, classLoader: ClassLoader): Boolean {
        val clsSet: Class<*> = try {
            Class.forName(
                "com.android.providers.settings.SettingsProvider",
                false,
                classLoader
            )
        } catch (e: ClassNotFoundException) {
            HookRuntime.log("AllTrans: SettingsProvider class not found in $packageName: " + e.message)
            return false
        }

        hookSettingsQuery(clsSet)
        hookSettingsCall(clsSet)
        return true
    }

    @Throws(Throwable::class)
    private fun hookSettingsQuery(clsSet: Class<*>) {
        HookRuntime.log("AllTrans: Trying to hook SettingsProvider.query method.")
        val mQuery: Method? = try {
            clsSet.getDeclaredMethod(
                "query",
                Uri::class.java,
                Array<String>::class.java,
                Bundle::class.java,
                CancellationSignal::class.java
            ).also {
                HookRuntime.log("AllTrans: Found SettingsProvider.query (Uri, String[], Bundle, CancellationSignal) method.")
            }
        } catch (e1: NoSuchMethodException) {
            HookRuntime.log("AllTrans: Query signature with Bundle not found, trying (Uri, String[], String, String[], String, CancellationSignal)...")
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
                    HookRuntime.log("AllTrans: Found SettingsProvider.query (Uri, String[], String, String[], String, CancellationSignal) method.")
                }
            } catch (e2: NoSuchMethodException) {
                HookRuntime.log("AllTrans: Query signature with CancellationSignal not found, trying (Uri, String[], String, String[], String)...")
                try {
                    clsSet.getDeclaredMethod(
                        "query",
                        Uri::class.java,
                        Array<String>::class.java,
                        String::class.java,
                        Array<String>::class.java,
                        String::class.java
                    ).also {
                        HookRuntime.log("AllTrans: Found SettingsProvider.query (Uri, String[], String, String[], String) method.")
                    }
                } catch (e3: NoSuchMethodException) {
                    HookRuntime.log("AllTrans: Could not find any known SettingsProvider.query method signature!")
                    return
                }
            }
        }

        val queryMethod = mQuery ?: run {
            HookRuntime.log("AllTrans: ERROR - mQuery is null in hookSettingsQuery, cannot apply hook.")
            return
        }

        HookRuntime.log("AllTrans: Applying hook to SettingsProvider.query method.")
        HookRuntime.hook(queryMethod, object : SafeHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: HookParam) {
                if (param.args.isEmpty() || (param.args[0] !is Uri)) return
                val uri = param.args[0] as Uri
                val uriString = uri.toString()
                if (uriString.startsWith("content://settings/system/alltransProxyProviderURI/")) {
                    HookRuntime.log("AllTrans Proxy Hook: Intercepted query URI: $uriString")
                    val originalProviderUriString = uriString.replaceFirst(
                        "content://settings/system/alltransProxyProviderURI/".toRegex(),
                        "content://"
                    )
                    HookRuntime.log("AllTrans Proxy Hook: Rewritten query URI: $originalProviderUriString")
                    val newUri = originalProviderUriString.toUri()

                    val ident = Binder.clearCallingIdentity()
                    var cursor: Cursor? = null
                    var providerContext: Context? = null
                    try {
                        val settingsProviderInstance = param.thisObject ?: run {
                            HookRuntime.log("AllTrans Proxy Hook: SettingsProvider instance is null for query.")
                            return
                        }
                        try {
                            val mGetContext =
                                settingsProviderInstance.javaClass.getMethod("getContext")
                            providerContext =
                                mGetContext.invoke(settingsProviderInstance) as Context?
                        } catch (e: NoSuchMethodException) {
                            HookRuntime.log("AllTrans Proxy Hook: SettingsProvider.getContext() method not found: ${e.message}")
                            try {
                                providerContext = Reflect.getObjectField(
                                    settingsProviderInstance,
                                    "mContext"
                                ) as Context?
                            } catch (fieldEx: Exception) {
                                HookRuntime.log("AllTrans Proxy Hook: Failed to get context from SettingsProvider field 'mContext': ${fieldEx.message}")
                                HookRuntime.log(fieldEx)
                            }
                        } catch (e: IllegalAccessException) {
                            HookRuntime.log("AllTrans Proxy Hook: IllegalAccessException for SettingsProvider.getContext(): ${e.message}")
                            try {
                                providerContext = Reflect.getObjectField(
                                    settingsProviderInstance,
                                    "mContext"
                                ) as Context?
                            } catch (fieldEx: Exception) {
                                HookRuntime.log("AllTrans Proxy Hook: Failed to get context from SettingsProvider field 'mContext' after IllegalAccess: ${fieldEx.message}")
                                HookRuntime.log(fieldEx)
                            }
                        } catch (e: InvocationTargetException) {
                            HookRuntime.log("AllTrans Proxy Hook: InvocationTargetException for SettingsProvider.getContext(): ${e.message}")
                            HookRuntime.log(e.targetException ?: e)
                            try {
                                providerContext = Reflect.getObjectField(
                                    settingsProviderInstance,
                                    "mContext"
                                ) as Context?
                            } catch (fieldEx: Exception) {
                                HookRuntime.log("AllTrans Proxy Hook: Failed to get context from SettingsProvider field 'mContext' after InvocationTarget: ${fieldEx.message}")
                                HookRuntime.log(fieldEx)
                            }
                        }

                        if (providerContext == null) {
                            HookRuntime.log("AllTrans Proxy Hook: Provider context is null for URI $newUri, cannot execute proxy query.")
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
                            HookRuntime.log("AllTrans Proxy Hook: Querying using Bundle signature.")
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
                            HookRuntime.log("AllTrans Proxy Hook: Querying using String signature (Uri, String[], String, String[], String, CancellationSignal).")
                            cursor = providerContext.contentResolver.query(
                                newUri,
                                projection,
                                selection,
                                selectionArgs,
                                sortOrder,
                                cancellationSignal
                            )
                        } else {
                            HookRuntime.log("AllTrans Proxy Hook: Querying using simpler signature for URI $newUri (Uri, String[], null, null, null).")
                            cursor = providerContext.contentResolver
                                .query(newUri, projection, null, null, null, cancellationSignal)
                        }

                        param.setResult(cursor)
                        HookRuntime.log(
                            "AllTrans Proxy Hook: Setting query result for URI $newUri (${if (cursor != null) cursor.count.toString() + " rows" else "null"})."
                        )
                    } catch (e: Exception) {
                        HookRuntime.log("AllTrans Proxy Hook: Error during proxy query execution for URI $newUri: ${e.message}")
                        HookRuntime.log(e)
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
        HookRuntime.log("AllTrans: Trying to hook SettingsProvider.call method.")
        val mCall: Method? = try {
            clsSet.getDeclaredMethod(
                "call",
                String::class.java,
                String::class.java,
                String::class.java,
                Bundle::class.java
            ).also {
                HookRuntime.log("AllTrans: Found SettingsProvider.call (String authority, String method, String arg, Bundle extras) method.")
            }
        } catch (e1: NoSuchMethodException) {
            HookRuntime.log("AllTrans: Call signature (String authority, String method, String arg, Bundle extras) not found, trying (String method, String arg, Bundle extras)...")
            try {
                clsSet.getDeclaredMethod(
                    "call",
                    String::class.java,
                    String::class.java,
                    Bundle::class.java
                ).also {
                    HookRuntime.log("AllTrans: Found SettingsProvider.call (String method, String arg, Bundle extras) method.")
                }
            } catch (e2: NoSuchMethodException) {
                HookRuntime.log("AllTrans: Could not find any known SettingsProvider.call method signature!")
                return
            }
        }

        if (mCall == null) {
            HookRuntime.log("AllTrans: ERROR - mCall is null in hookSettingsCall, cannot apply hook.")
            return
        }

        HookRuntime.log("AllTrans: Applying hook to SettingsProvider.call method.")
        HookRuntime.hook(mCall, object : SafeHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: HookParam) {
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
                        HookRuntime.log("AllTrans Proxy Hook: Matched call signature (String authority, String method, String arg, Bundle extras)")
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
                        HookRuntime.log("AllTrans Proxy Hook: Matched call signature (String method, String arg, Bundle extras)")
                    }
                    handleProxyCall(param, method, extras)
                } else {
                    HookRuntime.log("AllTrans Proxy Hook: Unexpected SettingsProvider.call signature. Params: ${paramTypes.joinToString { it.simpleName }}")
                }
            }

            private fun handleProxyCall(param: HookParam, method: String?, extras: Bundle?) {
                if ("alltransProxyCall" == method) {
                    if (extras == null) {
                        HookRuntime.log("Proxy call: extras is null!")
                        param.setResult(null)
                        return
                    }

                    val originalUriString = extras.getString("alltransOriginalUri")
                    val originalMethod = extras.getString("alltransOriginalMethod")
                    val originalArg = extras.getString("alltransOriginalArg")

                    if (originalUriString == null || originalMethod == null) {
                        HookRuntime.log("Proxy call: original URI/Method missing!")
                        param.setResult(null)
                        return
                    }
                    val originalUri = originalUriString.toUri()

                    extras.remove("alltransOriginalUri")
                    extras.remove("alltransOriginalMethod")
                    extras.remove("alltransOriginalArg")

                    HookRuntime.log("AllTrans Proxy Hook: Intercepted call for $originalUri, Method: $originalMethod, Arg: $originalArg")

                    val ident = Binder.clearCallingIdentity()
                    var resultBundle: Bundle?
                    var providerContext: Context? = null
                    try {
                        val settingsProviderInstance = param.thisObject ?: run {
                            HookRuntime.log("AllTrans Proxy Hook: SettingsProvider instance is null for call.")
                            return
                        }
                        try {
                            val mGetContext =
                                settingsProviderInstance.javaClass.getMethod("getContext")
                            providerContext =
                                mGetContext.invoke(settingsProviderInstance) as Context?
                        } catch (e: NoSuchMethodException) {
                            HookRuntime.log("AllTrans Proxy Hook: SettingsProvider.getContext() method not found for call: ${e.message}")
                            try {
                                providerContext = Reflect.getObjectField(
                                    settingsProviderInstance,
                                    "mContext"
                                ) as Context?
                            } catch (fieldEx: Exception) {
                                HookRuntime.log("AllTrans Proxy Hook: Failed to get context from SettingsProvider field 'mContext' for call: ${fieldEx.message}")
                                HookRuntime.log(fieldEx)
                            }
                        } catch (e: IllegalAccessException) {
                            HookRuntime.log("AllTrans Proxy Hook: IllegalAccessException for SettingsProvider.getContext() for call: ${e.message}")
                            try {
                                providerContext = Reflect.getObjectField(
                                    settingsProviderInstance,
                                    "mContext"
                                ) as Context?
                            } catch (fieldEx: Exception) {
                                HookRuntime.log("AllTrans Proxy Hook: Failed to get context from SettingsProvider field 'mContext' after IllegalAccess for call: ${fieldEx.message}")
                                HookRuntime.log(fieldEx)
                            }
                        } catch (e: InvocationTargetException) {
                            HookRuntime.log("AllTrans Proxy Hook: InvocationTargetException for SettingsProvider.getContext() for call: ${e.message}")
                            HookRuntime.log(e.targetException ?: e)
                            try {
                                providerContext = Reflect.getObjectField(
                                    settingsProviderInstance,
                                    "mContext"
                                ) as Context?
                            } catch (fieldEx: Exception) {
                                HookRuntime.log("AllTrans Proxy Hook: Failed to get context from SettingsProvider field 'mContext' after InvocationTarget for call: ${fieldEx.message}")
                                HookRuntime.log(fieldEx)
                            }
                        }
                        if (providerContext == null) {
                            HookRuntime.log("Proxy call: Failed to get context for $originalUri, Method: $originalMethod!")
                            param.setResult(null)
                            return
                        }

                        HookRuntime.log("AllTrans Proxy Hook: Calling actual provider: $originalUri, Method: $originalMethod, Arg: $originalArg")
                        resultBundle = providerContext.contentResolver
                            .call(originalUri, originalMethod, originalArg, extras)
                        param.setResult(resultBundle)
                        HookRuntime.log("AllTrans Proxy Hook: Setting call result for $originalUri, Method: $originalMethod: $resultBundle")
                    } catch (e: Exception) {
                        HookRuntime.log("AllTrans Proxy Hook: Error during proxy call execution for $originalUri, Method: $originalMethod: ${e.message}")
                        HookRuntime.log(e)
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

        @Volatile
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

        @Volatile
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
        const val WEBVIEW_CLEANUP_TAG_KEY: Int = 0x7A117AA9

        val webViewHookInstances: MutableMap<WebView?, VirtWebViewOnLoad?> =
            Collections.synchronizedMap(
                WeakHashMap()
            )
    }
}
