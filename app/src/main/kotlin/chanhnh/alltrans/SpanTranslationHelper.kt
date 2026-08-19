package chanhnh.alltrans

import android.text.SpannableStringBuilder
import android.text.Spanned

data class SpanRecord(
    val span: Any,
    val flags: Int
)

data class SpanSegment(
    val sourceStart: Int,
    val sourceEnd: Int,
    val sourceText: String,
    val spanRecords: List<SpanRecord>
)

data class SpanTranslationPayload(
    val originalText: CharSequence,
    val plainText: String,
    val requestKey: String,
    val segments: List<SpanSegment>
) {
    fun rebuildTranslatedCharSequence(translatedSegments: List<String>): CharSequence {
        if (translatedSegments.size != segments.size) {
            return translatedSegments.joinToString("")
        }
        return SpanTranslationHelper.rebuildTranslatedSpannable(this, translatedSegments)
    }
}

object SpanTranslationHelper {
    fun createPayload(source: CharSequence): SpanTranslationPayload? {
        val spanned = source as? Spanned ?: return null
        val segments = parseSpannedText(spanned)
        if (segments.isEmpty() || segments.none { it.spanRecords.isNotEmpty() }) {
            return null
        }

        val plainText = source.toString()
        return SpanTranslationPayload(
            originalText = source,
            plainText = plainText,
            requestKey = buildRequestKey(segments),
            segments = segments
        )
    }

    fun parseSpannedText(source: Spanned): List<SpanSegment> {
        val length = source.length
        if (length == 0) return emptyList()

        val spanBoundaries = linkedSetOf(0, length)
        val spans = source.getSpans(0, length, Any::class.java)
        spans.forEach { span ->
            val start = source.getSpanStart(span)
            val end = source.getSpanEnd(span)
            if (start >= 0 && end >= 0 && start <= length && end <= length && start < end) {
                spanBoundaries.add(start)
                spanBoundaries.add(end)
            }
        }

        val sortedBoundaries = spanBoundaries.sorted()
        val segments = ArrayList<SpanSegment>(sortedBoundaries.size)

        for (index in 0 until sortedBoundaries.size - 1) {
            val segmentStart = sortedBoundaries[index]
            val segmentEnd = sortedBoundaries[index + 1]
            if (segmentStart >= segmentEnd) continue

            val coveringSpans = source.getSpans(segmentStart, segmentEnd, Any::class.java)
                .filter { span ->
                    val spanStart = source.getSpanStart(span)
                    val spanEnd = source.getSpanEnd(span)
                    spanStart <= segmentStart && spanEnd >= segmentEnd
                }
                .map { span ->
                    SpanRecord(
                        span = span,
                        flags = source.getSpanFlags(span)
                    )
                }

            segments.add(
                SpanSegment(
                    sourceStart = segmentStart,
                    sourceEnd = segmentEnd,
                    sourceText = source.subSequence(segmentStart, segmentEnd).toString(),
                    spanRecords = coveringSpans
                )
            )
        }

        return segments
    }

    fun rebuildTranslatedSpannable(
        payload: SpanTranslationPayload,
        translatedSegments: List<String>
    ): CharSequence {
        val builder = SpannableStringBuilder()

        payload.segments.zip(translatedSegments).forEach { (segment, translatedText) ->
            if (translatedText.isEmpty()) return@forEach

            val segmentStart = builder.length
            builder.append(translatedText)
            val segmentEnd = builder.length

            segment.spanRecords.forEach { spanRecord ->
                builder.setSpan(spanRecord.span, segmentStart, segmentEnd, spanRecord.flags)
            }
        }

        return builder
    }

    private fun buildRequestKey(segments: List<SpanSegment>): String {
        return buildString {
            append("spanned:")
            segments.forEach { segment ->
                append(segment.sourceStart)
                append('-')
                append(segment.sourceEnd)
                append(':')
                append(segment.sourceText.hashCode())
                append('|')
            }
        }
    }
}
