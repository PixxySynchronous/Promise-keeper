package com.satyam.whatsappbot.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rejection rules this copy adds to the original app's, each taken from a kind of real false alarm.
 * The messages here are made-up ones of the same shape (the public ones from the original repository's issue 2
 * are quoted as they are).
 */
class FalseAlarmRulesTest {
    private val rules = RuleBasedCommitmentDetector()

    private fun assertRejected(message: String) {
        assertNull("should not be a promise: $message", rules.detect(message))
        assertTrue("the model should not even be asked about: $message", rules.rejects(message))
    }

    @Test
    fun refusalsAreNotPromises() {
        listOf("I will not do it", "I'm not going to send it", "kabhi nahi karunga", "I shall not come tomorrow")
            .forEach(::assertRejected)
    }

    @Test
    fun aRequestAnywhereInTheMessageIsNotAPromise() {
        listOf("bro can you send it by tonight", "actually could u share it here", "ok so will you bring it tomorrow")
            .forEach(::assertRejected)
    }

    @Test
    fun aRequestTogetherWithOnesOwnPromiseStillCounts() {
        assertNotNull(rules.detect("I will send it, can you wait"))
        assertFalse(rules.rejects("main kal bhej dunga, can you wait till then"))
    }

    @Test
    fun howOrWhyQuestionsAboutOneselfAreNotPromises() {
        listOf("itna sab mai kaise karunga ab", "kyu bataunga tujhe").forEach(::assertRejected)
    }

    @Test
    fun thoriBeforeTheVerbMeansNo() {
        listOf("wo thori bataunga", "mai thodi na jaunga wahan").forEach(::assertRejected)
        assertNotNull("'thodi der mein' is about time, not a denial", rules.detect("thodi der mein bhej dunga"))
    }

    @Test
    fun quotingAPromiseIsNotMakingOne() {
        listOf("ab kal karunga mt bolio", "baad mein dunga mat bolna").forEach(::assertRejected)
    }

    @Test
    fun suggestingIsNotPromising() {
        // From issue 2 of the original repository.
        listOf("Haa, I will suggest use Dary instead of Kotlin", "I'd suggest we wait till Monday").forEach(::assertRejected)
    }

    @Test
    fun aQuestionTagIsNotAPromise() {
        // From issue 2 of the original repository.
        assertRejected("Monday 3 baje khatam hota haina")
        assertFalse("with the sender's own promise it still counts", rules.rejects("kal bhej dunga, thik hai na"))
    }

    @Test
    fun ordinaryPromisesAreUntouched() {
        listOf("kal pakka kar dunga", "I'll send it tonight", "main kal aaunga to bata dunga", "bhej dunga kal")
            .forEach { assertNotNull("still a promise: $it", rules.detect(it)) }
    }
}
