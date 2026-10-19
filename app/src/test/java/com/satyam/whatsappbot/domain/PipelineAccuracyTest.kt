package com.satyam.whatsappbot.domain

import com.google.gson.JsonParser
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scores the app's detection pipeline (phrase rules, then the model unless the rules reject the message) on every
 * labelled message in tools/commitment_examples.py. Model probabilities are cross-validated, i.e. from a classifier
 * that never saw that message (written by tools/train_semantic_head.py), so these numbers estimate unseen messages.
 */
class PipelineAccuracyTest {
    private data class Case(val text: String, val label: Int, val group: String, val probability: Double)

    private val rules = RuleBasedCommitmentDetector()
    private val json = javaClass.classLoader!!.getResourceAsStream("commitment_eval.json")!!.reader(Charsets.UTF_8)
        .use { JsonParser.parseReader(it).asJsonObject }
    private val threshold = json.get("threshold").asDouble
    private val cases = json.getAsJsonArray("cases").map { it.asJsonObject }
        .map { Case(it.get("text").asString, it.get("label").asInt, it.get("group").asString, it.get("probability").asDouble) }

    // Mirrors WhatsappAccessibilityService.detectAndSuggest.
    private val now = java.time.LocalDateTime.of(2026, 10, 19, 12, 0)
    private fun flagged(case: Case) = rules.detect(case.text) != null || (
        !rules.rejects(case.text) &&
            (rules.hasCommitmentCue(case.text) || TimeParser().parse(case.text, now) != null) &&
            case.probability >= threshold
        )

    @Test
    fun catchesMostPromisesWithFewFalseAlarms() {
        val promises = cases.filter { it.label == 1 }
        val ordinary = cases.filter { it.label == 0 }
        val caught = promises.count(::flagged).toDouble() / promises.size
        val falseAlarms = ordinary.count(::flagged).toDouble() / ordinary.size
        println("pipeline on ${cases.size} messages: caught %.1f%% of promises, flagged %.1f%% of ordinary messages".format(caught * 100, falseAlarms * 100))
        for (language in listOf("English", "Hinglish", "Hindi", "mixed")) {
            val inLanguage = cases.filter { languageOf(it.group) == language }
            val p = inLanguage.filter { it.label == 1 }
            val o = inLanguage.filter { it.label == 0 }
            println("  %-9s caught %3.0f%%  flagged %3.0f%%".format(language, 100.0 * p.count(::flagged) / p.size, 100.0 * o.count(::flagged) / o.size))
        }
        println("missed: " + promises.filterNot(::flagged).map { it.text })
        println("false alarms: " + ordinary.filter(::flagged).map { it.text })
        assertTrue("caught only ${caught * 100}%", caught >= 0.85)
        assertTrue("flagged ${falseAlarms * 100}% of ordinary messages", falseAlarms <= 0.05)
    }

    private fun languageOf(group: String) = when {
        group == "hindi" -> "Hindi"
        group.startsWith("hinglish") -> "Hinglish"
        group == "mixed" -> "mixed"
        else -> "English"
    }
}
