package chanhnh.alltrans

import java.lang.reflect.Member

/**
 * Modern-API replacement for the legacy XC_MethodHook.MethodHookParam.
 *
 * Hooks keep their existing before/after logic, while the actual interception is
 * performed with libxposed API 102's Chain/Hooker model.
 */
class HookParam(
    val method: Member,
    val thisObject: Any?,
    val args: Array<Any?>
) {
    internal var result: Any? = null
    internal var resultSet: Boolean = false
    internal var throwable: Throwable? = null

    fun setResult(result: Any?) {
        this.result = result
        this.resultSet = true
    }

    fun getResult(): Any? = result

    fun hasThrowable(): Boolean = throwable != null

    fun getThrowable(): Throwable? = throwable

    fun setThrowable(throwable: Throwable?) {
        this.throwable = throwable
    }
}
