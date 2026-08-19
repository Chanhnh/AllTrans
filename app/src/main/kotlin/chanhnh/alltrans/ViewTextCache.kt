package chanhnh.alltrans

import android.util.LruCache

object ViewTextCache {
    private const val CACHE_SIZE = 1000
    private val cache = LruCache<String, String>(CACHE_SIZE)

    fun get(text: String): String? = synchronized(cache) {
        when (val cached = cache.get(text)) {
            null -> null
            SetTextHookHandler.NO_TRANSLATION_MARKER -> text
            else -> cached
        }
    }

    fun put(text: String, translated: String) {
        synchronized(cache) {
            if (translated == text) {
                cache.put(text, SetTextHookHandler.NO_TRANSLATION_MARKER)
            } else {
                cache.put(text, translated)
            }
        }
    }

    fun markNoTranslation(text: String) {
        synchronized(cache) {
            cache.put(text, SetTextHookHandler.NO_TRANSLATION_MARKER)
        }
    }
}
