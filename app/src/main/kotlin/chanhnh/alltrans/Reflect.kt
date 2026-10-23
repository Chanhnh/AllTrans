package chanhnh.alltrans

import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Small replacement for the reflective helpers that used to live in XposedHelpers.
 */
object Reflect {

    fun findField(clazz: Class<*>, name: String): Field {
        var current: Class<*>? = clazz
        while (current != null) {
            try {
                return current.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        throw NoSuchFieldException("${clazz.name}#$name")
    }

    fun findMethod(clazz: Class<*>, name: String, args: Array<out Any?>): Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            for (method in current.declaredMethods) {
                if (method.name != name || method.parameterTypes.size != args.size) continue
                if (matches(method.parameterTypes, args)) {
                    method.isAccessible = true
                    return method
                }
            }
            current = current.superclass
        }
        return null
    }

    fun getObjectField(obj: Any, name: String): Any? = findField(obj.javaClass, name).get(obj)

    fun setObjectField(obj: Any, name: String, value: Any?) {
        findField(obj.javaClass, name).set(obj, value)
    }

    fun getIntField(obj: Any, name: String): Int {
        val field = findField(obj.javaClass, name)
        return field.getInt(obj)
    }

    fun setIntField(obj: Any, name: String, value: Int) {
        findField(obj.javaClass, name).setInt(obj, value)
    }

    fun callMethod(obj: Any, name: String, vararg args: Any?): Any? {
        val method = findMethod(obj.javaClass, name, args)
            ?: throw NoSuchMethodException("${obj.javaClass.name}#$name(${args.joinToString { it?.javaClass?.name ?: "null" }})")
        try {
            return method.invoke(obj, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
    }

    fun callStaticMethod(clazz: Class<*>, name: String, vararg args: Any?): Any? {
        var current: Class<*>? = clazz
        while (current != null) {
            for (method in current.declaredMethods) {
                if (method.name != name || !Modifier.isStatic(method.modifiers)) continue
                if (method.parameterTypes.size == args.size && matches(method.parameterTypes, args)) {
                    method.isAccessible = true
                    try {
                        return method.invoke(null, *args)
                    } catch (e: InvocationTargetException) {
                        throw e.targetException ?: e
                    }
                }
            }
            current = current.superclass
        }
        throw NoSuchMethodException("${clazz.name}#$name")
    }

    private fun matches(parameterTypes: Array<Class<*>>, args: Array<out Any?>): Boolean {
        for (i in parameterTypes.indices) {
            if (!isAssignable(parameterTypes[i], args[i])) return false
        }
        return true
    }

    private fun isAssignable(parameterType: Class<*>, value: Any?): Boolean {
        if (value == null) return !parameterType.isPrimitive
        if (!parameterType.isPrimitive) return parameterType.isAssignableFrom(value.javaClass)

        return when (parameterType) {
            java.lang.Boolean.TYPE -> value is Boolean
            java.lang.Byte.TYPE -> value is Byte
            java.lang.Character.TYPE -> value is Char
            java.lang.Short.TYPE -> value is Short
            java.lang.Integer.TYPE -> value is Int
            java.lang.Long.TYPE -> value is Long
            java.lang.Float.TYPE -> value is Float
            java.lang.Double.TYPE -> value is Double
            else -> false
        }
    }
}
