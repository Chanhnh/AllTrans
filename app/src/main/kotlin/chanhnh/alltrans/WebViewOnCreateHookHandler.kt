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
import android.webkit.WebView
import de.robv.android.xposed.XC_MethodHook

internal class WebViewOnCreateHookHandler : XC_MethodHook() {
    @SuppressLint(
        "JavascriptInterface",
        "AddJavascriptInterface",
        "SetJavaScriptEnabled"
    )  // --- MUDANÇA: Hook onAttachedToWindow em vez do construtor ---
    // Isso garante que a WebView esteja mais inicializada
    override fun afterHookedMethod(methodHookParam: MethodHookParam) {
        Utils.debugLog("WebViewOnCreateHookHandler: after onAttachedToWindow!")

        val webView = methodHookParam.thisObject as WebView
        val webSettings = webView.getSettings()

        // Habilita JavaScript (essencial)
        webSettings.setJavaScriptEnabled(true)

        // --- INÍCIO DA MODIFICAÇÃO: Usar Mapa Estático ---
        // Cria uma nova instância de VirtWebViewOnLoad para esta WebView específica
        val webViewHookInstance = VirtWebViewOnLoad(webView)

        // Armazena a instância no mapa estático usando a WebView como chave
        Alltrans.Companion.webViewHookInstances.put(webView, webViewHookInstance)
        Utils.debugLog("WebViewOnCreateHookHandler: Stored hook instance for WebView " + webView.hashCode() + " in map.")

        // Adiciona a *instância específica* como interface JavaScript
        webView.addJavascriptInterface(webViewHookInstance, "injectedObject")
        Utils.debugLog("WebViewOnCreateHookHandler: Added JS interface for WebView " + webView.hashCode())

        // --- FIM DA MODIFICAÇÃO ---

        // Linhas removidas (setTag, addJavascriptInterface(webView), setWebViewClient)
    } // Fim do afterHookedMethod
} // Fim da classe WebViewOnCreateHookHandler
