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

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.os.Message
import android.util.Log
import android.view.KeyEvent
import android.webkit.ClientCertRequest
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

class WebViewClientWrapper(private val oriClient: WebViewClient?) : WebViewClient(), Cloneable {
    init {
        Utils.debugLog("WebViewClientWrapper: Created wrapper for original client: " + (if (oriClient != null) oriClient.javaClass.getName() else "null"))
    }

    // --- Métodos delegados (sem alterações, exceto onPageFinished) ---
    override fun hashCode(): Int {
        super.hashCode()
        return if (oriClient != null) oriClient.hashCode() else 0
    }

    override fun equals(other: Any?): Boolean {
        super.equals(other)
        if (other is WebViewClientWrapper) {
            val otherOri = other.oriClient
            return if (oriClient == null) (otherOri == null) else (oriClient == otherOri)
        }
        return oriClient != null && oriClient == other
    }

    @Throws(CloneNotSupportedException::class)
    override fun clone(): Any {
        Utils.debugLog("WebViewClientWrapper: clone() called.")
        return WebViewClientWrapper(oriClient)
    }

    override fun toString(): String {
        super.toString()
        return if (oriClient != null) oriClient.toString() else "WebViewClientWrapper(null)"
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        super.shouldOverrideUrlLoading(view, url)
        return oriClient != null && oriClient.shouldOverrideUrlLoading(view, url)
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        super.shouldOverrideUrlLoading(view, request)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return oriClient != null && oriClient.shouldOverrideUrlLoading(view, request)
        } else {
            return false
        }
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (oriClient != null) oriClient.onPageStarted(view, url, favicon)
    }

    @SuppressLint("JavascriptInterface", "AddJavascriptInterface")
    override fun onPageFinished(view: WebView, url: String?) {
        Utils.debugLog("WebViewClientWrapper: onPageFinished START for URL: " + url + " WebView: " + view.hashCode())

        super.onPageFinished(view, url)
        if (oriClient != null) {
            try {
                oriClient.onPageFinished(view, url)
                Utils.debugLog("WebViewClientWrapper: Called oriClient.onPageFinished")
            } catch (t: Throwable) {
                Utils.debugLog(
                    "WebViewClientWrapper: Error calling oriClient.onPageFinished: " + Log.getStackTraceString(
                        t
                    )
                )
            }
        } else {
            Utils.debugLog("WebViewClientWrapper: oriClient is null in onPageFinished.")
        }

        // --- INÍCIO DA MODIFICAÇÃO: Recuperar do Mapa Estático ---
        try {
            Utils.debugLog("WebViewClientWrapper: Attempting to retrieve VirtWebViewOnLoad instance from map...")
            // Recupera a instância do mapa usando a WebView como chave
            val webViewHookInstance: VirtWebViewOnLoad? =
                Alltrans.Companion.webViewHookInstances.get(view)

            if (webViewHookInstance != null) {
                Utils.debugLog("WebViewClientWrapper: Found instance in map, attempting to call afterOnLoadMethod...")
                webViewHookInstance.afterOnLoadMethod(view) // Chama o método na instância correta
                Utils.debugLog("WebViewClientWrapper: Successfully called afterOnLoadMethod on instance.")
            } else {
                Utils.debugLog("WebViewClientWrapper: Error - Could not retrieve VirtWebViewOnLoad instance from map for WebView " + view.hashCode() + "!")
                // A instância pode não ter sido adicionada ainda (timing) ou já foi coletada pelo GC (se WeakHashMap)
            }
        } catch (e: Throwable) {
            Utils.debugLog(
                "WebViewClientWrapper: Error retrieving from map or executing afterOnLoadMethod in onPageFinished: " + Log.getStackTraceString(
                    e
                )
            )
        }

        // --- FIM DA MODIFICAÇÃO ---
        Utils.debugLog("WebViewClientWrapper: onPageFinished END for URL: " + url + " WebView: " + view.hashCode())
    }


    override fun onLoadResource(view: WebView?, url: String?) {
        super.onLoadResource(view, url)
        if (oriClient != null) oriClient.onLoadResource(view, url)
    }

    override fun onPageCommitVisible(view: WebView?, url: String?) {
        super.onPageCommitVisible(view, url)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (oriClient != null) oriClient.onPageCommitVisible(view, url)
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
        super.shouldInterceptRequest(view, url)
        return if (oriClient != null) oriClient.shouldInterceptRequest(view, url) else null
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        super.shouldInterceptRequest(view, request)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return if (oriClient != null) oriClient.shouldInterceptRequest(view, request) else null
        } else {
            return null
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onTooManyRedirects(view: WebView?, cancelMsg: Message?, continueMsg: Message?) {
        super.onTooManyRedirects(view, cancelMsg, continueMsg)
        if (oriClient != null) oriClient.onTooManyRedirects(view, cancelMsg, continueMsg)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onReceivedError(
        view: WebView?,
        errorCode: Int,
        description: String?,
        failingUrl: String?
    ) {
        super.onReceivedError(view, errorCode, description, failingUrl)
        if (oriClient != null) oriClient.onReceivedError(view, errorCode, description, failingUrl)
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        super.onReceivedError(view, request, error)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (oriClient != null) oriClient.onReceivedError(view, request, error)
        }
    }

    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?
    ) {
        super.onReceivedHttpError(view, request, errorResponse)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (oriClient != null) oriClient.onReceivedHttpError(view, request, errorResponse)
        }
    }

    override fun onFormResubmission(view: WebView?, dontResend: Message?, resend: Message?) {
        super.onFormResubmission(view, dontResend, resend)
        if (oriClient != null) oriClient.onFormResubmission(view, dontResend, resend)
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        if (oriClient != null) oriClient.doUpdateVisitedHistory(view, url, isReload)
    }

    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
        super.onReceivedSslError(view, handler, error)
        if (oriClient != null) oriClient.onReceivedSslError(view, handler, error)
    }

    override fun onReceivedClientCertRequest(view: WebView?, request: ClientCertRequest?) {
        super.onReceivedClientCertRequest(view, request)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (oriClient != null) oriClient.onReceivedClientCertRequest(view, request)
        }
    }

    override fun onReceivedHttpAuthRequest(
        view: WebView?,
        handler: HttpAuthHandler?,
        host: String?,
        realm: String?
    ) {
        super.onReceivedHttpAuthRequest(view, handler, host, realm)
        if (oriClient != null) oriClient.onReceivedHttpAuthRequest(view, handler, host, realm)
    }

    override fun shouldOverrideKeyEvent(view: WebView?, event: KeyEvent?): Boolean {
        super.shouldOverrideKeyEvent(view, event)
        return oriClient != null && oriClient.shouldOverrideKeyEvent(view, event)
    }

    override fun onUnhandledKeyEvent(view: WebView?, event: KeyEvent?) {
        super.onUnhandledKeyEvent(view, event)
        if (oriClient != null) oriClient.onUnhandledKeyEvent(view, event)
    }

    override fun onScaleChanged(view: WebView?, oldScale: Float, newScale: Float) {
        super.onScaleChanged(view, oldScale, newScale)
        if (oriClient != null) oriClient.onScaleChanged(view, oldScale, newScale)
    }

    override fun onReceivedLoginRequest(
        view: WebView?,
        realm: String?,
        account: String?,
        args: String?
    ) {
        super.onReceivedLoginRequest(view, realm, account, args)
        if (oriClient != null) oriClient.onReceivedLoginRequest(view, realm, account, args)
    }
}