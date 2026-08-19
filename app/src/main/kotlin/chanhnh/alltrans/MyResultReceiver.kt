package chanhnh.alltrans

import android.os.Bundle
import android.os.Handler
import android.os.ResultReceiver
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import okhttp3.ResponseBody.Companion.toResponseBody

internal class MyResultReceiver(
    handler: Handler?,
    private val originalCallback: GetTranslate?,
    private val originalText: String?
) : ResultReceiver(handler) {
    override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
        if (originalCallback == null) {
            Log.e(MyResultReceiver.Companion.TAG, "Original GetTranslate callback is null!")
            return
        }

        // Crie um Request simulado para usar na criação do Call simulado
        val mockRequest = Request.Builder().url("https://mock.google.provider.call").build()
        // CORREÇÃO: Crie um Call simulado para passar ao callback (requerido por onResponse/onFailure)
        // Este Call simulado é a solução para o erro "Null cannot be... Call" na chamadas de onResponse/onFailure aqui.
        val mockCall = OkHttpClient().newCall(mockRequest)

        if (resultCode == RESULT_CODE_OK && resultData != null) {
            val translatedString = resultData.getString(KEY_RESULT_STRING, originalText)
            Utils.debugLog(MyResultReceiver.Companion.TAG + ": Received OK result: [" + translatedString + "]")

            // CORREÇÃO: Usando a sintaxe correta para criar ResponseBody com a extensão toResponseBody
            val response = Response.Builder()
                .request(mockRequest)
                .code(200).message("OK (from ResultReceiver)")
                .protocol(Protocol.HTTP_2)
                .body((translatedString ?: "").toResponseBody(null))
                .build()
            try {
                // CORREÇÃO: Passe o Call simulado como primeiro argumento (Erro 36, 43 no MyResultReceiver original)
                originalCallback.onResponse(mockCall, response)
            } catch (t: Throwable) {
                Log.e(
                    MyResultReceiver.Companion.TAG,
                    "Error calling onResponse from ResultReceiver",
                    t
                )
                // CORREÇÃO: Passe o Call simulado como primeiro argumento (Erro na chamada original onFailure)
                originalCallback.onFailure(mockCall, IOException("Error in onResponse callback", t))
            }
        } else {
            var errorMessage = "Unknown error"
            if (resultData != null) {
                errorMessage = resultData.getString(
                    KEY_ERROR_MESSAGE,
                    "Error data received, but no message key"
                )
            }
            Log.e(
                MyResultReceiver.Companion.TAG,
                "Received ERROR result code (" + resultCode + ") or null data. Error: " + errorMessage
            )
            // CORREÇÃO: Passe o Call simulado como primeiro argumento (Erro 57 no MyResultReceiver original)
            originalCallback.onFailure(mockCall, IOException("GtransProvider failed: " + errorMessage))
        }
    }

    companion object {
        const val RESULT_CODE_OK: Int = 1
        const val RESULT_CODE_ERROR: Int = 0
        const val KEY_RESULT_STRING: String = "translationResult"
        const val KEY_ERROR_MESSAGE: String = "errorMessage"
        private const val TAG = "AllTrans:MyResultReceiver"
    }
}