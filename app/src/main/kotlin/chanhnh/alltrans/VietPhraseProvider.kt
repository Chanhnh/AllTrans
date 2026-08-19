package chanhnh.alltrans

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import java.util.concurrent.TimeUnit

class VietPhraseProvider : ContentProvider() {
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

        val fromLanguage = uri.getQueryParameter(KEY_FROM_LANGUAGE) ?: PreferenceList.VIETPHRASE_SOURCE_LANGUAGE
        val toLanguage = uri.getQueryParameter(KEY_TO_LANGUAGE) ?: PreferenceList.VIETPHRASE_TARGET_LANGUAGE
        val textToTranslate = uri.getQueryParameter(KEY_TEXT_TO_TRANSLATE)

        if (textToTranslate.isNullOrEmpty()) {
            Log.w(TAG, "Text to translate is null or empty.")
            return createResultCursor(null, projection, startTime)
        }

        if (!PreferenceList.isVietPhraseLanguagePair(fromLanguage, toLanguage)) {
            Utils.debugLog("$TAG: Unsupported language pair for VietPhrase: $fromLanguage -> $toLanguage. Returning original text.")
            return createResultCursor(textToTranslate, projection, startTime)
        }

        val safeContext = context
        if (safeContext == null) {
            Log.w(TAG, "Provider context is null.")
            return createResultCursor(textToTranslate, projection, startTime)
        }

        val translatedText = VietPhraseTranslator.translate(safeContext, textToTranslate)
        return createResultCursor(translatedText, projection, startTime)
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
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw UnsupportedOperationException("Insert not supported by AllTrans VietPhraseProvider")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String?>?): Int {
        throw UnsupportedOperationException("Delete not supported by AllTrans VietPhraseProvider")
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String?>?): Int {
        throw UnsupportedOperationException("Update not supported by AllTrans VietPhraseProvider")
    }

    companion object {
        const val TAG = "AllTrans:VietPhraseProv"
        const val COLUMN_TRANSLATE: String = "translate"
        const val KEY_TEXT_TO_TRANSLATE: String = "text"
        const val KEY_FROM_LANGUAGE: String = "from"
        const val KEY_TO_LANGUAGE: String = "to"
    }
}
