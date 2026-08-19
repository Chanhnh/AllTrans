package chanhnh.alltrans

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

class ViewHierarchyHookHandler : XC_MethodHook() {

    companion object {
        private const val TAG = "AllTrans:ViewHierarchyHook"
        private const val FIELD_LAST_TEXT = "alltrans:last_view_text"
        private const val FIELD_LAST_HINT = "alltrans:last_view_hint"
        private const val MAX_DEPTH = 24
        private const val MAX_NODES = 3000
    }

    override fun afterHookedMethod(param: MethodHookParam) {
        val rootView = resolveRootView(param) ?: return
        val packageName = rootView.context?.packageName

        if (!PreferenceManager.isEnabledForPackage(rootView.context, packageName)) return

        rootView.post {
            try {
                traverseAndRefresh(rootView, 0, intArrayOf(0))
            } catch (t: Throwable) {
                Utils.debugLog("$TAG: Failed to process view tree: ${t.message}")
            }
        }
    }

    private fun resolveRootView(param: MethodHookParam): View? {
        return when {
            param.args.isNotEmpty() && param.args[0] is View -> param.args[0] as View
            param.thisObject is View -> param.thisObject as View
            else -> null
        }
    }

    private fun traverseAndRefresh(view: View, depth: Int, nodeCounter: IntArray) {
        if (depth > MAX_DEPTH || nodeCounter[0] >= MAX_NODES) return
        nodeCounter[0]++

        if (view is TextView) {
            refreshTextView(view)
        }

        if (view is ViewGroup) {
            val count = view.childCount
            for (i in 0 until count) {
                val child = view.getChildAt(i) ?: continue
                traverseAndRefresh(child, depth + 1, nodeCounter)
            }
        }
    }

    private fun refreshTextView(textView: TextView) {
        if (PreferenceList.SetText && textView !is EditText) {
            val text = textView.text
            if (!text.isNullOrEmpty()) {
                val current = text.toString()
                if (shouldRefresh(textView, FIELD_LAST_TEXT, current)) {
                    markProcessed(textView, FIELD_LAST_TEXT, current)
                    textView.setText(text)
                }
            }
        }

        if (PreferenceList.SetHint) {
            val hint = textView.hint
            if (!hint.isNullOrEmpty()) {
                val current = hint.toString()
                if (shouldRefresh(textView, FIELD_LAST_HINT, current)) {
                    markProcessed(textView, FIELD_LAST_HINT, current)
                    textView.hint = hint
                }
            }
        }
    }

    private fun shouldRefresh(target: Any, field: String, currentValue: String): Boolean {
        return try {
            val previous = XposedHelpers.getAdditionalInstanceField(target, field) as? String
            previous != currentValue
        } catch (t: Throwable) {
            true
        }
    }

    private fun markProcessed(target: Any, field: String, value: String) {
        try {
            XposedHelpers.setAdditionalInstanceField(target, field, value)
        } catch (t: Throwable) {
            Utils.debugLog("$TAG: Failed to mark processed field $field: ${t.message}")
        }
    }
}
