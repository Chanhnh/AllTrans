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

class GtransProvider : ContentProvider() {

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
            // Build protobuf-like JSON body: [[[text], fromLang, toLang], "wt_lib"]
            val bodyArray = JSONArray()
            val innerArray = JSONArray()
            // Replace newlines with <br> to preserve formatting in translateHtml endpoint
            val htmlFormattedText = text.replace("\n", "<br>")
            val textsArray = JSONArray()
            textsArray.put(htmlFormattedText)
            innerArray.put(textsArray)
            innerArray.put(fromLang)
            innerArray.put(toLang)
            bodyArray.put(innerArray)
            bodyArray.put("wt_lib")
            val bodyString = bodyArray.toString()

            Utils.debugLog("$TAG: Calling translate-pa API for: [$text] $fromLang -> $toLang")

            val request = Request.Builder()
                .url(API_URL)
                .post(bodyString.toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("X-Goog-API-Key", API_KEY)
                .addHeader("Content-Type", "application/json+protobuf")
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful || responseBody.isNullOrEmpty()) {
                Log.e(TAG, "API error ${response.code}: $responseBody")
                return null
            }

            Utils.debugLog("$TAG: API response: $responseBody")

            // Response format: [["translated text"],["detected_lang"]]
            val jsonResponse = JSONArray(responseBody)
            val translationsArray = jsonResponse.getJSONArray(0)
            var translated = translationsArray.getString(0)

            // Replace <br> and variants back to newlines
            translated = translated.replace(Regex("(?i)<br\\s*/?>"), "\n")
            // Handle optional paragraph tags if returned
            translated = translated.replace(Regex("(?i)<p>"), "").replace(Regex("(?i)</p>"), "\n")
            translated = Utils.XMLUnescape(translated) ?: translated
            
            // Trim if it ends with extra newline from </p>
            if (translated.endsWith("\n") && !text.endsWith("\n")) {
                translated = translated.substring(0, translated.length - 1)
            }

            // Detect if source == target (same language detection)
            if (jsonResponse.length() > 1) {
                val detectedLang = jsonResponse.optJSONArray(1)?.optString(0)
                if (detectedLang != null && detectedLang == toLang && fromLang == "auto") {
                    Utils.debugLog("$TAG: Detected lang ($detectedLang) == target ($toLang), returning original.")
                    return text
                }
            }

            Utils.debugLog("$TAG: Translation result: [$text] -> [$translated]")
            translated
        } catch (e: Exception) {
            Log.e(TAG, "Error calling translate-pa API for: [$text]", e)
            null
        }
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

    override fun shutdown() {
        super.shutdown()
        Utils.debugLog("$TAG: shutdown.")
    }

    override fun getType(uri: Uri): String? = null

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw UnsupportedOperationException("Insert not supported by AllTrans GtransProvider")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String?>?): Int {
        throw UnsupportedOperationException("Delete not supported by AllTrans GtransProvider")
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String?>?): Int {
        throw UnsupportedOperationException("Update not supported by AllTrans GtransProvider")
    }

    companion object {
        const val TAG = "AllTrans:gtransProv"
        const val COLUMN_TRANSLATE: String = "translate"
        const val KEY_TEXT_TO_TRANSLATE: String = "text"
        const val KEY_FROM_LANGUAGE: String = "from"
        const val KEY_TO_LANGUAGE: String = "to"

        private const val API_URL = "https://translate-pa.googleapis.com/v1/translateHtml"
        private const val API_KEY = "AIzaSyATBXajvzQLTDHEQbcpq0Ihe0vWDHmO520"
        private val JSON_MEDIA_TYPE = "application/json+protobuf".toMediaType()
    }
}
