package chanhnh.alltrans

import io.github.libxposed.api.XposedInterface

/**
 * Adapter that preserves the legacy before/after hook style on top of API 102.
 */
abstract class SafeHook : XposedInterface.Hooker {

    open fun beforeHookedMethod(param: HookParam) {}

    open fun afterHookedMethod(param: HookParam) {}

    final override fun intercept(chain: XposedInterface.Chain): Any? {
        val param = HookParam(
            method = chain.executable,
            thisObject = chain.thisObject,
            args = chain.args.toArgsArray()
        )

        try {
            beforeHookedMethod(param)
        } catch (t: Throwable) {
            HookRuntime.log("beforeHookedMethod failed for ${chain.executable}", t)
        }

        if (!param.resultSet && param.throwable == null) {
            try {
                param.result = chain.proceed(param.args)
            } catch (t: Throwable) {
                param.throwable = t
            }
        }

        try {
            afterHookedMethod(param)
        } catch (t: Throwable) {
            HookRuntime.log("afterHookedMethod failed for ${chain.executable}", t)
        }

        if (param.resultSet) {
            return param.result
        }

        param.throwable?.let { throw it }
        return param.result
    }
}

internal fun List<Any>.toArgsArray(): Array<Any?> = Array(size) { index -> this[index] }
