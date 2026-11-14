package com.satyam.whatsappbot.domain

/**
 * Offline port of the Hugging Face `tokenizers` pipeline described by the bundled tokenizer.json:
 * special-token split -> Precompiled (SentencePiece) normalizer -> WhitespaceSplit + Metaspace ->
 * Unigram (Viterbi) -> `<s> $A </s>` with truncation. Pure Kotlin, no I/O: callers pass the parsed data.
 * Verified token-for-token against tokenizers 0.23.3 by SentencePieceTokenizerTest.
 */
class SentencePieceTokenizer(
    pieces: List<String>,
    private val scores: DoubleArray,
    private val unkId: Int,
    precompiledCharsmap: ByteArray,
    private val specialTokens: Map<String, Int>,
    private val maxLength: Int = 128,
    private val bosId: Int = specialTokens.getValue("<s>"),
    private val eosId: Int = specialTokens.getValue("</s>")
) {
    private val pieceIds = HashMap<String, Int>(pieces.size * 2).apply { pieces.forEachIndexed { id, piece -> put(piece, id) } }
    private val maxPieceCodePoints = pieces.maxOf { it.codePointCount(0, it.length) }
    private val unkScore = scores.min() - UNK_PENALTY
    private val normalizer = PrecompiledNormalizer(precompiledCharsmap)
    // Longest first, so "</s>" is not split as "<s>"-less text; ties do not occur in practice.
    private val specialsByLength = specialTokens.keys.sortedByDescending { it.length }

    fun encode(text: String): IntArray {
        val ids = ArrayList<Int>()
        for ((segment, specialId) in splitOnSpecialTokens(text)) {
            if (specialId != null) {
                ids += specialId
                continue
            }
            for (word in preTokenize(normalizer.normalize(segment))) viterbi(word, ids)
        }
        val body = if (ids.size > maxLength - 2) ids.subList(0, maxLength - 2) else ids
        return IntArray(body.size + 2).also { out ->
            out[0] = bosId
            body.forEachIndexed { index, id -> out[index + 1] = id }
            out[out.size - 1] = eosId
        }
    }

    private fun splitOnSpecialTokens(text: String): List<Pair<String, Int?>> {
        val result = ArrayList<Pair<String, Int?>>()
        var start = 0
        var index = 0
        while (index < text.length) {
            val special = specialsByLength.firstOrNull { text.startsWith(it, index) }
            if (special == null) { index++; continue }
            if (index > start) result += text.substring(start, index) to null
            result += special to specialTokens.getValue(special)
            index += special.length
            start = index
        }
        if (start < text.length) result += text.substring(start) to null
        return result
    }

    // WhitespaceSplit, then Metaspace(replacement="▁", prepend always, split on "▁").
    private fun preTokenize(text: String): List<String> {
        val words = ArrayList<String>()
        val current = StringBuilder()
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            if (isUnicodeWhitespace(codePoint)) {
                if (current.isNotEmpty()) { words += current.toString(); current.setLength(0) }
            } else {
                current.appendCodePoint(codePoint)
            }
            offset += Character.charCount(codePoint)
        }
        if (current.isNotEmpty()) words += current.toString()
        return words.flatMap { word ->
            val prefixed = if (word.startsWith(METASPACE)) word else METASPACE + word
            val parts = ArrayList<String>()
            var partStart = 0
            for (i in 1 until prefixed.length) {
                if (prefixed[i] == METASPACE[0]) { parts += prefixed.substring(partStart, i); partStart = i }
            }
            parts += prefixed.substring(partStart)
            parts
        }
    }

    // Unigram best path over code points, matching tokenizers' encode_optimized (ties keep the shorter piece,
    // unknown characters cost min_score - 10, consecutive unknowns fuse into one <unk>).
    private fun viterbi(word: String, out: MutableList<Int>) {
        val codePoints = word.codePoints().toArray()
        val size = codePoints.size
        val bestScore = DoubleArray(size + 1)
        val bestStart = IntArray(size + 1) { -1 }
        val bestId = IntArray(size + 1)
        bestStart[0] = 0
        for (start in 0 until size) {
            if (bestStart[start] < 0) continue
            val base = bestScore[start]
            var hasSingle = false
            val builder = StringBuilder()
            for (end in start + 1..minOf(size, start + maxPieceCodePoints)) {
                builder.appendCodePoint(codePoints[end - 1])
                val id = pieceIds[builder.toString()] ?: continue
                val candidate = base + scores[id]
                if (bestStart[end] < 0 || candidate > bestScore[end]) {
                    bestScore[end] = candidate; bestStart[end] = start; bestId[end] = id
                }
                if (end == start + 1) hasSingle = true
            }
            if (!hasSingle) {
                val candidate = base + unkScore
                if (bestStart[start + 1] < 0 || candidate > bestScore[start + 1]) {
                    bestScore[start + 1] = candidate; bestStart[start + 1] = start; bestId[start + 1] = unkId
                }
            }
        }
        val reversed = ArrayList<Int>()
        var end = size
        while (end > 0) {
            val id = bestId[end]
            if (id != unkId || reversed.lastOrNull() != unkId) reversed += id
            end = bestStart[end]
        }
        for (i in reversed.indices.reversed()) out += reversed[i]
    }

    private class PrecompiledNormalizer(charsmap: ByteArray) {
        private val trie: IntArray
        private val normalized: ByteArray

        init {
            val trieBytes = readUInt32(charsmap, 0)
            trie = IntArray(trieBytes / 4) { readUInt32(charsmap, 4 + it * 4) }
            normalized = charsmap.copyOfRange(4 + trieBytes, charsmap.size)
        }

        // Same order as the spm_precompiled crate: whole grapheme if under 6 UTF-8 bytes, else per code point.
        fun normalize(text: String): String {
            val result = StringBuilder(text.length)
            for (grapheme in graphemes(text)) {
                val whole = if (grapheme.toByteArray(Charsets.UTF_8).size < 6) transform(grapheme) else null
                if (whole != null) {
                    result.append(whole)
                } else {
                    var offset = 0
                    while (offset < grapheme.length) {
                        val length = Character.charCount(grapheme.codePointAt(offset))
                        val part = grapheme.substring(offset, offset + length)
                        result.append(transform(part) ?: part)
                        offset += length
                    }
                }
            }
            return result.toString()
        }

        /*
         * Extended grapheme clusters, limited to the UAX #29 rules that can produce a multi-code-point cluster
         * under 6 UTF-8 bytes (the only size whose grouping changes the output): GB3-5 (CR LF, controls),
         * GB9 (Extend, ZWJ), GB9a (SpacingMark), GB9b (Prepend). The rules left out (Hangul, emoji ZWJ,
         * regional indicators, Indic conjuncts) only join pieces into clusters of 6+ bytes, which are
         * normalized per code point either way. Written out rather than using BreakIterator, whose rules
         * differ between Android and the JVM the tests run on.
         */
        private fun graphemes(text: String): List<String> {
            val clusters = ArrayList<String>()
            var start = 0
            var previous = -1
            var offset = 0
            while (offset < text.length) {
                val codePoint = text.codePointAt(offset)
                if (previous != -1 && breaksBetween(previous, codePoint)) {
                    clusters += text.substring(start, offset)
                    start = offset
                }
                previous = codePoint
                offset += Character.charCount(codePoint)
            }
            if (start < text.length) clusters += text.substring(start)
            return clusters
        }

        private fun breaksBetween(previous: Int, next: Int): Boolean = when {
            previous == 0x0D && next == 0x0A -> false
            isControl(previous) || isControl(next) -> true
            isExtendOrSpacingMark(next) -> false
            isPrepend(previous) -> false
            else -> true
        }

        private fun isControl(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
            Character.CONTROL, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
            Character.FORMAT -> codePoint != 0x200C && codePoint != 0x200D && !isPrepend(codePoint) && codePoint !in 0xE0020..0xE007F
            else -> false
        }

        private fun isExtendOrSpacingMark(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
            Character.NON_SPACING_MARK, Character.ENCLOSING_MARK -> true
            Character.COMBINING_SPACING_MARK -> codePoint !in SPACING_MARK_EXCEPTIONS
            else -> codePoint == 0x200C || codePoint == 0x200D || codePoint == 0x0E33 || codePoint == 0x0EB3 ||
                codePoint == 0xFF9E || codePoint == 0xFF9F || codePoint in 0x1F3FB..0x1F3FF || codePoint in 0xE0020..0xE007F
        }

        private fun isPrepend(codePoint: Int): Boolean = codePoint in 0x0600..0x0605 || codePoint == 0x06DD ||
            codePoint == 0x070F || codePoint in 0x0890..0x0891 || codePoint == 0x08E2 || codePoint == 0x0D4E ||
            codePoint == 0x110BD || codePoint == 0x110CD || codePoint in 0x111C2..0x111C3

        // Mc characters that UAX #29 assigns neither Extend nor SpacingMark (mostly Myanmar).
        private val SPACING_MARK_EXCEPTIONS = setOf(
            0x102B, 0x102C, 0x1038, 0x1062, 0x1063, 0x1064, 0x1067, 0x1068, 0x1069, 0x106A, 0x106B, 0x106C, 0x106D,
            0x1083, 0x1087, 0x1088, 0x1089, 0x108A, 0x108B, 0x108C, 0x108F, 0x109A, 0x109B, 0x109C,
            0x1A61, 0x1A63, 0x1A64, 0xAA7B, 0xAA7D, 0x11720, 0x11721
        )

        // Darts-clone common prefix search; like the reference, the first (shortest) match wins.
        private fun transform(chunk: String): String? {
            var nodePos = 0
            nodePos = nodePos xor offset(trie[nodePos])
            for (byte in chunk.toByteArray(Charsets.UTF_8)) {
                val label = byte.toInt() and 0xFF
                if (label == 0) return null
                nodePos = nodePos xor label
                val unit = trie[nodePos]
                if ((unit and LABEL_MASK) != label) return null
                nodePos = nodePos xor offset(unit)
                if ((unit ushr 8) and 1 == 1) {
                    val start = trie[nodePos] and 0x7FFFFFFF
                    var stop = start
                    while (stop < normalized.size && normalized[stop] != 0.toByte()) stop++
                    return String(normalized, start, stop - start, Charsets.UTF_8)
                }
            }
            return null
        }

        private fun offset(unit: Int): Int = (unit ushr 10) shl ((unit and (1 shl 9)) ushr 6)

        private fun readUInt32(bytes: ByteArray, at: Int): Int =
            (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)
    }

    companion object {
        private const val METASPACE = "▁"
        private const val UNK_PENALTY = 10.0
        private const val LABEL_MASK = 0x800000FF.toInt()

        // Unicode White_Space, which is what Rust's char::is_whitespace uses (Java's isWhitespace differs).
        private fun isUnicodeWhitespace(codePoint: Int): Boolean = when (codePoint) {
            in 0x09..0x0D, 0x20, 0x85, 0xA0, 0x1680, in 0x2000..0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
            else -> false
        }
    }
}
