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
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class EdgeProvider : ContentProvider() {

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private var cachedToken: String? = null
    private var tokenExpiryTime: Long = 0

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

    private fun getAuthToken(): String? {
        val now = System.currentTimeMillis()
        if (cachedToken != null && now < tokenExpiryTime) {
            return cachedToken
        }

        Utils.debugLog("$TAG: Fetching new auth token from Edge API")
        val request = Request.Builder()
            .url(AUTH_URL)
            .header("User-Agent", USER_AGENT)
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            val token = response.body?.string()
            if (response.isSuccessful && !token.isNullOrEmpty()) {
                cachedToken = token
                // Edge tokens typically last for 10 minutes (600s), let's cache for 9 minutes to be safe
                tokenExpiryTime = now + TimeUnit.MINUTES.toMillis(9)
                token
            } else {
                Log.e(TAG, "Failed to fetch Edge auth token: ${response.code}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Edge auth token", e)
            null
        }
    }

    private fun translateOnline(text: String, fromLang: String, toLang: String): String? {
        val token = getAuthToken() ?: return null

        return try {
            val urlBuilder = Uri.parse(API_URL).buildUpon()
                .appendQueryParameter("api-version", "3.0")
                .appendQueryParameter("to", toLang)
            
            if (fromLang != "auto" && fromLang.isNotEmpty()) {
                urlBuilder.appendQueryParameter("from", fromLang)
            }
            
            val url = urlBuilder.toString()

            val bodyArray = JSONArray()
            val textObj = JSONObject()
            textObj.put("Text", text)
            bodyArray.put(textObj)
            val bodyString = bodyArray.toString()

            Utils.debugLog("$TAG: Calling Edge Translate API: $url")

            val request = Request.Builder()
                .url(url)
                .post(bodyString.toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful || responseBody.isNullOrEmpty()) {
                Log.e(TAG, "Edge API error ${response.code}: $responseBody")
                if (response.code == 401) {
                    // Token might have expired, clear it
                    cachedToken = null
                }
                return null
            }

            Utils.debugLog("$TAG: Edge API response: $responseBody")

            // Response format: [{"translations":[{"text":"translated_text","to":"lang"}]}]
            val jsonResponse = JSONArray(responseBody)
            val firstResult = jsonResponse.getJSONObject(0)
            val translations = firstResult.getJSONArray("translations")
            val translated = translations.getJSONObject(0).getString("text")

            Utils.debugLog("$TAG: Edge Translation result: [$text] -> [$translated]")
            translated
        } catch (e: Exception) {
            Log.e(TAG, "Error calling Edge Translate API for: [$text]", e)
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

        private const val AUTH_URL = "https://edge.microsoft.com/translate/auth"
        private const val API_URL = "https://api-edge.cognitive.microsofttranslator.com/translate"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36 Edg/121.0.0.0"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
