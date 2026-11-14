package com.satyam.whatsappbot.domain

import com.google.gson.JsonParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks Kotlin pooling + scoring against scikit-learn on real model output (tools/train_semantic_head.py). */
class CommitmentHeadTest {
    private val headJson = File("src/main/assets/semantic/commitment_head.json").reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
    private val head = CommitmentHead(
        headJson.getAsJsonArray("weights").map { it.asFloat }.toFloatArray(),
        headJson.get("bias").asFloat,
        headJson.get("threshold").asFloat
    )

    @Test
    fun matchesScikitLearnProbabilities() {
        val cases = javaClass.classLoader!!.getResourceAsStream("commitment_head_reference.json")!!.reader(Charsets.UTF_8)
            .use { JsonParser.parseReader(it).asJsonArray }
        assertTrue(cases.size() >= 5)
        cases.map { it.asJsonObject }.forEach { case ->
            val hidden = case.getAsJsonArray("hidden").map { row -> row.asJsonArray.map { it.asFloat }.toFloatArray() }
            assertEquals(case.get("text").asString, case.get("probability").asDouble, head.probability(hidden).toDouble(), 1e-4)
        }
    }

    @Test
    fun thresholdIsSensible() {
        assertTrue(head.threshold in 0.3f..0.95f)
    }
}
