package chanhnh.alltrans

import io.github.libxposed.api.XposedInterface

/**
 * Adapter that preserves the legacy XC_MethodReplacement style on top of API 102.
 */
abstract class SafeReplacementHook : XposedInterface.Hooker {

    abstract fun replaceHookedMethod(param: HookParam): Any?

    final override fun intercept(chain: XposedInterface.Chain): Any? {
        val param = HookParam(
            method = chain.executable,
            thisObject = chain.thisObject,
            args = chain.args.toArgsArray()
        )

        return try {
            replaceHookedMethod(param)
        } catch (t: Throwable) {
            HookRuntime.log("replaceHookedMethod failed for ${chain.executable}", t)
            chain.proceed(param.args)
        }
    }
}
