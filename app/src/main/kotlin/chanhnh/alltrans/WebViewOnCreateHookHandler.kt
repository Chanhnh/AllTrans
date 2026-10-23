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
import android.view.View
import android.webkit.WebView

internal class WebViewOnCreateHookHandler : SafeHook() {
    @SuppressLint(
        "JavascriptInterface",
        "AddJavascriptInterface",
        "SetJavaScriptEnabled"
    )
    override fun afterHookedMethod(methodHookParam: HookParam) {
        val webView = methodHookParam.thisObject as? WebView ?: return

        if (!PreferenceManager.isWebViewTranslationEnabled(webView.context)) {
            Utils.debugLog("WebViewOnCreateHookHandler: WebView translation disabled for ${webView.context.packageName}")
            return
        }

        Utils.debugLog("WebViewOnCreateHookHandler: after onAttachedToWindow!")

        val webSettings = webView.getSettings()
        webSettings.javaScriptEnabled = true

        val webViewHookInstance = Alltrans.webViewHookInstances[webView]
            ?: VirtWebViewOnLoad(webView).also { instance ->
                Alltrans.webViewHookInstances[webView] = instance

                // The View hierarchy owns this listener, so it is released together
                // with the WebView. The tag prevents duplicate listeners on reattach.
                if (webView.getTag(Alltrans.WEBVIEW_CLEANUP_TAG_KEY) != true) {
                    webView.setTag(Alltrans.WEBVIEW_CLEANUP_TAG_KEY, true)
                    webView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(v: View) {}

                        override fun onViewDetachedFromWindow(v: View) {
                            val detachedWebView = v as? WebView ?: return
                            Alltrans.webViewHookInstances.remove(detachedWebView)
                            detachedWebView.removeJavascriptInterface("injectedObject")
                        }
                    })
                }

                Utils.debugLog("WebViewOnCreateHookHandler: Stored hook instance for WebView ${webView.hashCode()} in map.")
            }

        webView.addJavascriptInterface(webViewHookInstance, "injectedObject")
        Utils.debugLog("WebViewOnCreateHookHandler: Added JS interface for WebView ${webView.hashCode()}")
    }
}
