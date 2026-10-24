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

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.util.concurrent.TimeUnit

class EdgeProvider : ContentProvider() {

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate(): Boolean {
        Utils.debugLog("$TAG: onCreate")
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<String?>?,
        selection: String?,
        selectionArgs: Array<String?>?,
        sortOrder: String?
    ): Cursor {
        val startTime = System.nanoTime()
        Utils.debugLog("$TAG: Received query URI: $uri")

        val fromLanguage = uri.getQueryParameter(KEY_FROM_LANGUAGE) ?: "auto"
        val toLanguage = uri.getQueryParameter(KEY_TO_LANGUAGE)
        val textToTranslate = uri.getQueryParameter(KEY_TEXT_TO_TRANSLATE)

        if (textToTranslate.isNullOrEmpty()) {
            Log.w(TAG, "Text to translate is null or empty.")
            return createResultCursor(null, projection, startTime)
        }
        if (toLanguage.isNullOrEmpty()) {
            Log.w(TAG, "Target language is null or empty.")
            return createResultCursor(textToTranslate, projection, startTime)
        }

        val translatedText = translateOnline(textToTranslate, fromLanguage, toLanguage)
        return createResultCursor(translatedText ?: textToTranslate, projection, startTime)
    }

    private fun translateOnline(text: String, fromLang: String, toLang: String): String? {
        return try {
            val urlBuilder = Uri.parse(API_URL).buildUpon()
                .appendQueryParameter("isEnterpriseClient", "false")
                .appendQueryParameter("to", mapLanguage(toLang))

            if (fromLang.isNotEmpty() && fromLang != "auto" && fromLang != "auto-detect") {
                urlBuilder.appendQueryParameter("from", mapLanguage(fromLang))
            }

            val url = urlBuilder.toString()
            val bodyString = JSONArray().put(text).toString()

            Utils.debugLog("$TAG: Calling Edge Translate API: $url")

            val request = Request.Builder()
                .url(url)
                .post(bodyString.toRequestBody(JSON_MEDIA_TYPE))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                if (!response.isSuccessful || responseBody.isNullOrBlank()) {
                    Log.e(
                        TAG,
                        "Edge API error ${response.code}: ${responseBody.orEmpty().take(MAX_ERROR_LOG_LENGTH)}"
                    )
                    return null
                }

                Utils.debugLog("$TAG: Edge API response: $responseBody")

                // Response format: [{"translations":[{"text":"translated_text","to":"lang"}]}]
                val jsonResponse = JSONArray(responseBody)
                val translations = jsonResponse.optJSONObject(0)?.optJSONArray("translations")
                if (translations == null || translations.length() == 0) {
                    Log.e(TAG, "Edge API returned no translations: ${responseBody.take(MAX_ERROR_LOG_LENGTH)}")
                    return null
                }

                val translated = translations.optJSONObject(0)?.optString("text").orEmpty()
                if (translated.isEmpty()) {
                    Log.e(TAG, "Edge API returned an empty translation: ${responseBody.take(MAX_ERROR_LOG_LENGTH)}")
                    return null
                }

                Utils.debugLog("$TAG: Edge Translation result: [$text] -> [$translated]")
                translated
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error calling Edge Translate API for: [$text]", e)
            null
        }
    }

    private fun mapLanguage(language: String): String = when (language) {
        "zh", "zh-CN" -> "zh-Hans"
        "zh-TW" -> "zh-Hant"
        "tl" -> "fil"
        "hmn" -> "mww"
        "ku" -> "kmr"
        "ckb" -> "ku"
        "mn" -> "mn-Cyrl"
        "no" -> "nb"
        "lg" -> "lug"
        "sr" -> "sr-Cyrl"
        "mni-Mtei" -> "mni"
        else -> language
    }

    private fun createResultCursor(result: String?, projection: Array<String?>?, startTimeNanos: Long): MatrixCursor {
        val columns = if (projection.isNullOrEmpty()) arrayOf(COLUMN_TRANSLATE) else projection
        val cursor = MatrixCursor(columns)
        val rowBuilder = cursor.newRow()
        for (col in columns) {
            if (COLUMN_TRANSLATE.equals(col, ignoreCase = true)) {
                rowBuilder.add(result)
            } else {
                rowBuilder.add(null)
            }
        }
        val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTimeNanos)
        Utils.debugLog("$TAG: Query completed in ${durationMs}ms. Result: [$result]")
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String?>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String?>?): Int = 0

    companion object {
        const val TAG = "AllTrans:EdgeProv"
        const val COLUMN_TRANSLATE: String = "translate"
        const val KEY_TEXT_TO_TRANSLATE: String = "text"
        const val KEY_FROM_LANGUAGE: String = "from"
        const val KEY_TO_LANGUAGE: String = "to"
        const val ENGINE_VERSION = 1

        private const val API_URL = "https://edge.microsoft.com/translate/translatetext"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36 Edg/121.0.0.0"
        private const val MAX_ERROR_LOG_LENGTH = 512
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
