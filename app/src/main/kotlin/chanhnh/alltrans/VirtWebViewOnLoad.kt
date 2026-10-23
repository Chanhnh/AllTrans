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
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.lang.ref.WeakReference

class VirtWebViewOnLoad(webView: WebView?) : OriginalCallable {
    private val webViewRef = WeakReference(webView)
    private val webViewInstance: WebView?
        get() = webViewRef.get()

    init {
        Utils.debugLog("VirtWebViewOnLoad: Instance created for WebView: " + (if (webView != null) webView.hashCode() else "null"))
    }

    override fun callOriginalMethod(translatedString: CharSequence?, userData: Any?) {
        if (userData !is WebHookUserData2) {
            Utils.debugLog("VirtWebViewOnLoad: Error in callOriginalMethod - userData is not WebHookUserData2")
            return
        }
        val webHookUserData2 = userData
        val webView = webHookUserData2.webView
        if (webView == null) {
            Utils.debugLog("VirtWebViewOnLoad: Error in callOriginalMethod - webView from userData is null")
            return
        }
        if (!PreferenceManager.isWebViewTranslationEnabled(webView.context)) {
            Utils.debugLog("VirtWebViewOnLoad: WebView translation disabled; skipping async injection.")
            return
        }

        val originalUnescaped = webHookUserData2.stringArgs
        val translatedValue = translatedString?.toString() ?: run {
            Utils.debugLog("VirtWebViewOnLoad: translated string is null.")
            markNodeAsTranslated(webView, originalUnescaped)
            return
        }

        if (originalUnescaped == null || translatedValue == originalUnescaped) {
            Utils.debugLog("VirtWebViewOnLoad: Skipping JS injection in callOriginalMethod - translated string is null or same as original.")
            markNodeAsTranslated(webView, originalUnescaped)
            return
        }

        Utils.debugLog("VirtWebViewOnLoad: callOriginalMethod - Injecting JS to replace [" + originalUnescaped + "] with [" + translatedValue + "] in WebView " + webView.hashCode())

        // Escapa as strings *apenas* para inserção segura no literal JS
        val originalEscapedForJS = escapeJsString(originalUnescaped)
        val translatedEscapedForJS = escapeJsString(translatedValue)

        val script = """
            var originalToMatch = $originalEscapedForJS;
            var translatedValue = $translatedEscapedForJS;
            
            function allTransReplaceText() {
                try {
                    var walker = document.createTreeWalker(
                        document.body,
                        NodeFilter.SHOW_TEXT,
                        {
                            acceptNode: function(node) {
                                if (node.parentElement && 
                                    (node.parentElement.hasAttribute('data-Alltrans-translated') || 
                                     node.parentElement.tagName === 'SCRIPT' || 
                                     node.parentElement.tagName === 'STYLE')) {
                                    return NodeFilter.FILTER_REJECT;
                                }
                                return NodeFilter.FILTER_ACCEPT;
                            }
                        },
                        false
                    );
                    
                    var node;
                    while (node = walker.nextNode()) {
                        if (node.nodeValue.trim() === originalToMatch.trim()) {
                            node.nodeValue = translatedValue;
                            if (node.parentElement) {
                                node.parentElement.setAttribute('data-Alltrans-translated', 'true');
                            }
                        }
                    }
                } catch (e) {
                    console.error('AllTrans: Error in text replacement:', e);
                }
            }
            
            // Execute immediately and in all frames
            allTransReplaceText();
            try {
                for (var i = 0; i < window.frames.length; i++) {
                    try {
                        window.frames[i].allTransReplaceText();
                    } catch (e) {}
                }
            } catch (e) {}
        """.trimIndent()

        myEvaluateJavaScript(webView, script)
    }

    private fun escapeJsString(value: String?): String {
        if (value == null) return "null"
        return "'" + value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace("\b", "\\b")
            .replace("\u000c", "\\f")
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029") + "'"
    }

    private fun markNodeAsTranslated(webView: WebView?, originalText: String?) {
        if (webView == null || originalText == null) return

        val originalEscapedForJS = escapeJsString(originalText)
        val script = """
            var originalToMatch = $originalEscapedForJS;
            function allTransMarkNodes() {
                try {
                    var walker = document.createTreeWalker(
                        document.body,
                        NodeFilter.SHOW_TEXT,
                        {
                            acceptNode: function(node) {
                                if (node.parentElement && 
                                    (node.parentElement.hasAttribute('data-Alltrans-translated') || 
                                     node.parentElement.tagName === 'SCRIPT' || 
                                     node.parentElement.tagName === 'STYLE')) {
                                    return NodeFilter.FILTER_REJECT;
                                }
                                return NodeFilter.FILTER_ACCEPT;
                            }
                        },
                        false
                    );
                    
                    var node;
                    while (node = walker.nextNode()) {
                        if (node.nodeValue.trim() === originalToMatch.trim()) {
                            if (node.parentElement) {
                                node.parentElement.setAttribute('data-Alltrans-translated', 'true');
                            }
                        }
                    }
                } catch (e) {
                    console.error('AllTrans: Error in marking nodes:', e);
                }
            }
            allTransMarkNodes();
        """.trimIndent()

        myEvaluateJavaScript(webView, script)
    }

    @SuppressLint("JavascriptInterface", "AddJavascriptInterface")
    @Throws(Throwable::class)
    fun afterOnLoadMethod(webView: WebView) {
        Utils.debugLog("VirtWebViewOnLoad: afterOnLoadMethod ENTERED for WebView: " + webView.hashCode())

        if (!PreferenceManager.isWebViewTranslationEnabled(webView.context)) {
            Utils.debugLog("VirtWebViewOnLoad: WebView translation disabled; skipping page script.")
            return
        }

        // Adiciona interface JS
        webView.addJavascriptInterface(this, "injectedObject")

        // Injeta script principal
        val delayMs = PreferenceList.DelayWebView.toLong() // CORREÇÃO: .toLong() em vez de .toLongOrNull()
        val script = """
            console.log('AllTrans: Initializing JS...');
            
            function allTransExtractText() {
                try {
                    var walker = document.createTreeWalker(
                        document.body,
                        NodeFilter.SHOW_TEXT,
                        {
                            acceptNode: function(node) {
                                if (node.parentElement && 
                                    (node.parentElement.hasAttribute('data-Alltrans-translated') || 
                                     node.parentElement.tagName === 'SCRIPT' || 
                                     node.parentElement.tagName === 'STYLE' || 
                                     node.parentElement.tagName === 'CODE' || 
                                     node.parentElement.tagName === 'PRE')) {
                                    return NodeFilter.FILTER_REJECT;
                                }
                                return NodeFilter.FILTER_ACCEPT;
                            }
                        },
                        false
                    );
                    
                    var texts = {};
                    var node;
                    while (node = walker.nextNode()) {
                        var text = node.nodeValue.trim();
                        if (text.length > 1 && 
                            !/^[+-]?\d+(\.\d+)?$/.test(text) && 
                            !/^(https?:\/\/\S+|\S+\.\S+)$/.test(text) && 
                            !/^[A-Z0-9_\-:]{1,10}$/.test(text)) {
                            texts[text] = true;
                        }
                    }
                    
                    for (var text in texts) {
                        if (texts.hasOwnProperty(text)) {
                            injectedObject.showLog(text);
                        }
                    }
                } catch (e) {
                    console.error('AllTrans: Error extracting text:', e);
                }
            }
            
            // Executa imediatamente
            setTimeout(allTransExtractText, $delayMs);
            
            // Observa mudanças na página
            if (!window.AllTransObserver) {
                window.AllTransObserver = new MutationObserver(function(mutations) {
                    if (window.AllTransDebounce) clearTimeout(window.AllTransDebounce);
                    window.AllTransDebounce = setTimeout(allTransExtractText, 800);
                });
                window.AllTransObserver.observe(document, {
                    subtree: true,
                    childList: true,
                    characterData: true
                });
            }
        """.trimIndent()

        myEvaluateJavaScript(webView, script)
        Utils.debugLog("VirtWebViewOnLoad: afterOnLoadMethod EXITED for WebView: " + webView.hashCode())
    }

    @Suppress("unused")
    @JavascriptInterface
    fun showLog(stringArgs: String?) {
        if (stringArgs == null || stringArgs.trim { it <= ' ' }.isEmpty()) return

        val webView = this.webViewInstance ?: return
        val context = webView.context ?: return
        val packageName = context.packageName ?: return

        if (!PreferenceManager.isWebViewTranslationEnabled(context)) {
            Utils.debugLog("VirtWebViewOnLoad: WebView translation disabled for this app ($packageName). Skipping [$stringArgs]")
            return
        }

        if (!SetTextHookHandler.isNotWhiteSpace(stringArgs)) return

        Utils.debugLog("VirtWebViewOnLoad: showLog received: [$stringArgs] for WebView ${webView.hashCode()}")

        // CORREÇÃO: Renomear variável local para evitar conflito
        val translationRequest = GetTranslate().apply {
            stringToBeTrans = stringArgs
            originalCallable = this@VirtWebViewOnLoad
            userData = WebHookUserData2(webView, stringArgs)
            canCallOriginal = true
        }

        var translationNeeded = true
        if (PreferenceList.Caching) {
            Alltrans.cacheAccess.acquireUninterruptibly()
            var cachedValue: String? = null
            try {
                cachedValue = Alltrans.cache?.get(stringArgs)
            } finally {
                if (Alltrans.cacheAccess.availablePermits() == 0) {
                    Alltrans.cacheAccess.release()
                }
            }

            if (cachedValue != null) {
                if (cachedValue == stringArgs || SetTextHookHandler.isNoTranslationMarker(cachedValue)) {
                    markNodeAsTranslated(webView, stringArgs)
                    translationNeeded = false
                } else {
                    Handler(Looper.getMainLooper()).postDelayed({
                        callOriginalMethod(cachedValue, WebHookUserData2(webView, stringArgs))
                    }, PreferenceList.Delay.toLong())
                    translationNeeded = false
                }
            }
        }

        if (translationNeeded) {
            Utils.debugLog("VirtWebViewOnLoad: showLog - Requesting translation for: [$stringArgs]")
            GetTranslateToken().apply { getTranslate = translationRequest }.doAll()
        }
    }

    fun myEvaluateJavaScript(webView: WebView?, script: String) {
        if (webView == null) {
            Utils.debugLog("VirtWebViewOnLoad: myEvaluateJavaScript - WebView is null!")
            return
        }
        webView.post {
            try {
                webView.evaluateJavascript(script, null)
            } catch (e: Exception) {
                Utils.debugLog("VirtWebViewOnLoad: Exception during evaluateJavascript: ${e.message}")
            }
        }
    }
}

internal class WebHookUserData2(val webView: WebView?, val stringArgs: String?)