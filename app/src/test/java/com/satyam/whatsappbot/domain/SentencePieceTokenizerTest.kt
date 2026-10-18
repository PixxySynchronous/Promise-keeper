package com.satyam.whatsappbot.domain

import com.google.gson.JsonParser
import java.io.File
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/** Compares against ids produced by the Hugging Face `tokenizers` library (tools/make_tokenizer_reference.py). */
class SentencePieceTokenizerTest {
    companion object {
        private lateinit var tokenizer: SentencePieceTokenizer

        @BeforeClass
        @JvmStatic
        fun load() {
            val json = File("src/main/assets/semantic/tokenizer.json").reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
            val model = json.getAsJsonObject("model")
            val vocab = model.getAsJsonArray("vocab")
            val pieces = vocab.map { it.asJsonArray[0].asString }
            val scores = DoubleArray(vocab.size()) { vocab[it].asJsonArray[1].asDouble }
            val charsmap = Base64.getDecoder().decode(json.getAsJsonObject("normalizer").get("precompiled_charsmap").asString)
            val specials = json.getAsJsonArray("added_tokens").map { it.asJsonObject }
                .filter { it.get("special").asBoolean }
                .associate { it.get("content").asString to it.get("id").asInt }
            tokenizer = SentencePieceTokenizer(pieces, scores, model.get("unk_id").asInt, charsmap, specials)
        }
    }

    @Test
    fun matchesReferenceTokenizerOnEveryCase() {
        val cases = javaClass.classLoader!!.getResourceAsStream("tokenizer_reference.json")!!.reader(Charsets.UTF_8)
            .use { JsonParser.parseReader(it).asJsonObject.getAsJsonArray("cases") }
        assertTrue("fixture is empty", cases.size() > 3000)
        val mismatches = cases.map { it.asJsonObject }.mapNotNull { case ->
            val text = case.get("text").asString
            val expected = case.getAsJsonArray("ids").map { it.asInt }
            val actual = tokenizer.encode(text).toList()
            if (actual == expected) null else "${escape(text)}\n  expected $expected\n  actual   $actual"
        }
        assertEquals("${mismatches.size} of ${cases.size()} differ:\n" + mismatches.take(15).joinToString("\n"), 0, mismatches.size)
    }

    @Test
    fun truncatesToModelLimit() {
        assertEquals(128, tokenizer.encode("bhej dunga ".repeat(500)).size)
    }

    private fun escape(text: String) = text.codePoints().toArray().joinToString("") { cp ->
        if (cp in 0x20..0x7E) String(Character.toChars(cp)) else "\\u{%X}".format(cp)
    }
}
