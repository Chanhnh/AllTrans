/*
 * Copyright 2017 Akhil Kedia
 * This file is part of AllTrans.
 *
 * AllTrans is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AllTrans is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AllTrans. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package chanhnh.alltrans

import android.content.Context
import android.util.Log
import de.robv.android.xposed.XposedHelpers
import java.io.StringWriter
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.math.min

internal object Utils {
    var Debug: Boolean = true

    fun debugLog(str: String?) {
        var str = str
        if (!Debug) return
        if (str == null) str = "null"
        val logTag = "AllTrans"
        val maxLogSize = 4000

        if (str.length > maxLogSize) {
            var chunkCount = (str.length / maxLogSize)
            if (str.length % maxLogSize != 0) chunkCount++
            for (i in 0..<chunkCount) {
                val start = i * maxLogSize
                val end = min(((i + 1) * maxLogSize).toDouble(), str.length.toDouble()).toInt()
                Log.i(
                    logTag,
                    "Chunk " + (i + 1) + "/" + chunkCount + ": " + str.substring(start, end)
                )
            }
        } else {
            Log.i(logTag, str)
        }
    }

    fun tryHookMethod(
        clazz: Class<*>?,
        methodName: String?,
        vararg parameterTypesAndCallback: Any?
    ) {
        val className = if (clazz != null) clazz.getName() else "null Class"
        val methodDesc = className + "#" + methodName
        try {
            XposedHelpers.findAndHookMethod(clazz, methodName, *parameterTypesAndCallback)
            Log.i("AllTransHook", "Successfully hooked: " + methodDesc)
        } catch (e: Throwable) {
            Log.e("AllTransHookError", "Cannot hook method: " + methodDesc, e)
        }
    }

    fun tryHookMethod(
        className: String?,
        classLoader: ClassLoader?,
        methodName: String?,
        vararg parameterTypesAndCallback: Any?
    ) {
        val methodDesc = className + "#" + methodName
        try {
            XposedHelpers.findAndHookMethod(
                className,
                classLoader,
                methodName,
                *parameterTypesAndCallback
            )
            Log.i("AllTransHook", "Successfully hooked: " + methodDesc)
        } catch (e: Throwable) {
            Log.e("AllTransHookError", "Cannot hook method: " + methodDesc, e)
        }
    }

    private val numericEntityPattern = Pattern.compile("&#(x?[0-9A-Fa-f]+);")
    private val namedEntities = mapOf(
        "amp" to "&",
        "quot" to "\"",
        "apos" to "'",
        "lt" to "<",
        "gt" to ">",
        "nbsp" to " ",
        "ndash" to "-",
        "mdash" to "\u2014",
        "hellip" to "\u2026",
        "laquo" to "\u00AB",
        "raquo" to "\u00BB",
        "lsquo" to "\u2018",
        "rsquo" to "\u2019",
        "ldquo" to "\u201C",
        "rdquo" to "\u201D",
        "bull" to "\u2022"
    )

    fun XMLUnescape(s: String?): String? {
        if (s == null) return null
        var retVal: String = s
        namedEntities.forEach { (entity, value) ->
            retVal = retVal.replace("&$entity;", value)
        }

        val matcher = numericEntityPattern.matcher(retVal)
        val out = StringBuffer(retVal.length)
        while (matcher.find()) {
            val rawValue = matcher.group(1) ?: continue
            val codePoint = try {
                if (rawValue.startsWith("x", ignoreCase = true)) {
                    rawValue.substring(1).toInt(16)
                } else {
                    rawValue.toInt(10)
                }
            } catch (_: NumberFormatException) {
                continue
            }

            val replacement = try {
                String(Character.toChars(codePoint))
            } catch (_: IllegalArgumentException) {
                continue
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement))
        }
        matcher.appendTail(out)
        return out.toString()
    }

    fun javaScriptEscape(str: String?): String? {
        if (str == null) return null
        val writer = StringWriter(str.length * 2)
        val sz = str.length

        for (i in 0..<sz) {
            val ch = str.get(i)
            if (ch.code > 0xfff) {
                writer.write("\\u" + hex(ch))
            } else if (ch.code > 0xff) {
                writer.write("\\u0" + hex(ch))
            } else if (ch.code > 0x7f) {
                writer.write("\\u00" + hex(ch))
            } else if (ch.code < 32) {
                when (ch) {
                    '\b' -> writer.write("\\b")
                    '\n' -> writer.write("\\n")
                    '\t' -> writer.write("\\t")
                    '\u000C' -> writer.write("\\f") // Fixed escape sequence
                    '\r' -> writer.write("\\r")
                    else -> if (ch.code > 0xf) {
                        writer.write("\\u00" + hex(ch))
                    } else {
                        writer.write("\\u000" + hex(ch))
                    }
                }
            } else {
                when (ch) {
                    '\'' -> writer.write("\\'")
                    '"' -> writer.write("\\\"")
                    '\\' -> writer.write("\\\\")
                    '/' -> writer.write("\\/")
                    else -> writer.write(ch.code)
                }
            }
        }
        return writer.toString()
    }

    private fun hex(ch: Char): String {
        return Integer.toHexString(ch.code).uppercase()
    }
}
