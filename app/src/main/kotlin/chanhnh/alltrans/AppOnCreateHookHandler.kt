package chanhnh.alltrans

import android.app.Application
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * Hook para Application.onCreate()
 * Este hook é executado quando o app é iniciado, garantindo que o contexto
 * seja capturado mesmo que attachBaseContext não seja chamado.
 */
internal class AppOnCreateHookHandler : XC_MethodHook() {

    override fun afterHookedMethod(methodHookParam: MethodHookParam) {
        try {
            Utils.debugLog("AllTrans: in after OnCreate of Application")

            // Verificar se o objeto hookado é realmente uma Application
            val application = methodHookParam.thisObject as? Application
            if (application == null) {
                Utils.debugLog("AllTrans: Hooked object in Application.onCreate is not an Application instance")
                return
            }

            // Obter o contexto da aplicação
            val appContext = application.applicationContext
            if (appContext == null) {
                Utils.debugLog("AllTrans: Could not get application context in Application.onCreate")
                return
            }

            val packageName = appContext.packageName
            Utils.debugLog("AllTrans: Application.onCreate for package: $packageName")

            // Definir contexto global se ainda não estiver definido
            if (Alltrans.context == null) {
                Alltrans.context = appContext
                Utils.debugLog("AllTrans: Application context set successfully from Application.onCreate")

                // Inicializar a chave da tag
                Alltrans.initializeTagKeyIfNeeded()

                // Aplicar hooks (exceto para o próprio AllTrans)
                if (packageName != "chanhnh.alltrans") {
                    try {
                        AttachBaseContextHookHandler.readPrefAndHook(appContext)
                    } catch (e: Throwable) {
                        Utils.debugLog("AllTrans: Error calling readPrefAndHook from AppOnCreateHookHandler for package $packageName: ${e.message}")
                        XposedBridge.log(e)
                        // Não propagar exceção - permitir que o app continue
                    }
                } else {
                    Utils.debugLog("AllTrans: Skipping readPrefAndHook for own package in Application.onCreate")
                }
            } else {
                Utils.debugLog("AllTrans: Application context already set (from attachBaseContext), skipping hooks in Application.onCreate")
                // Ainda assim, garantir que a tag key está inicializada
                Alltrans.initializeTagKeyIfNeeded()
            }

            // Registrar lifecycle callbacks para monitorar Activities
            try {
                val myActivityLifecycleCallbacks = MyActivityLifecycleCallbacks()
                application.registerActivityLifecycleCallbacks(myActivityLifecycleCallbacks)
                Utils.debugLog("AllTrans: Activity lifecycle callbacks registered successfully")
            } catch (e: Throwable) {
                Utils.debugLog("AllTrans: Error registering activity lifecycle callbacks: ${e.message}")
                XposedBridge.log(e)
                // Não propagar exceção
            }

        } catch (e: Throwable) {
            Utils.debugLog("AllTrans: Caught exception in AppOnCreateHookHandler: ${e.message}")
            XposedBridge.log(e)
            // Não propagar exceção - permitir que o app continue inicializando
        }
    }
}