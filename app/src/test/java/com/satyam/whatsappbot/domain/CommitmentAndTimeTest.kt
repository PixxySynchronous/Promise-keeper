package com.satyam.whatsappbot.domain

import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class CommitmentAndTimeTest {
    private lateinit var detector: CommitmentDetector
    private val now = LocalDateTime.of(2026, 10, 18, 14, 0)

    @Before
    fun setUp() {
        detector = RuleBasedCommitmentDetector()
    }

    @Test
    fun commitmentExamplesAndNegatives() {
        val positives = listOf(
            "I'll send it tomorrow", "I will send the file", "will share by EOD", "I will do it tonight",
            "let me check and revert", "I will get back to you", "bhej dunga kal", "bhej dungi tomorrow",
            "send kar dunga", "send kar dungi by 5", "share kar dunga", "kar dungi evening",
            "bata dunga after checking", "dekh ke batata hoon", "dekh kar bataunga", "karke bataungi",
            "forward kar dunga", "call kar dungi", "reply kar dunga", "confirm kar dungi",
            "I'll share it", "I will get back soon", "will revert tomorrow", "revert back by EOD",
            "I’ll send the details", "Please wait, I will do it"
            ,"Long message before this: I will send the report tomorrow after I finish the call"
            ,"bhej dungs in 2 mins"
        )
        val negatives = listOf(
            "I'll think about it lol", "kal kya hua tha", "what did you share yesterday?",
            "I sent it already", "can you send it?", "will you share it?", "maybe I will send it",
            "I don't know", "aaj kya plan hai", "I was going to send it", "he will do it",
            "send me the file", "share this with me", "I like this", "kal ka weather kaisa tha"
        )
        positives.forEach { message -> assertNotNull("Expected match: $message", detector.detect(message)) }
        negatives.forEach { message -> assertNull("Expected no match: $message", detector.detect(message)) }
        assertEquals(43, positives.size + negatives.size)
    }

    @Test
    fun parsesTomorrowDefault() {
        val result = TimeParser().parse("I'll send it tomorrow", now)
        assertNotNull(result)
        assertEquals(LocalDateTime.of(2026, 10, 19, 10, 0), result!!.dateTime)
        assertEquals(true, result.usedDefault)
    }

    @Test
    fun parsesKalAsTomorrowWhenFuture() {
        val result = TimeParser().parse("kal bhej dunga", now)
        assertEquals(LocalDateTime.of(2026, 10, 19, 10, 0), result!!.dateTime)
    }

    @Test
    fun rejectsPastKalStatement() {
        assertNull(TimeParser().parse("kal kya hua tha", now))
    }

    @Test
    fun parsesNamedDefaults() {
        val parser = TimeParser()
        assertEquals(LocalTime.of(18, 0), parser.parse("will share by EOD", now)!!.dateTime.toLocalTime())
        assertEquals(LocalTime.of(21, 0), parser.parse("I'll call tonight", now)!!.dateTime.toLocalTime())
        assertEquals(LocalTime.of(9, 30), parser.parse("send it in the morning", now)!!.dateTime.toLocalTime())
        assertEquals(LocalTime.of(18, 0), parser.parse("share it in the evening", now)!!.dateTime.toLocalTime())
        // 09:30 has passed at 14:00, so "aaj" falls through to end of day.
        assertEquals(LocalDateTime.of(2026, 10, 18, 18, 0), parser.parse("bata dunga aaj", now)!!.dateTime)
        assertEquals(LocalDateTime.of(2026, 10, 25, 10, 0), parser.parse("I'll let you know by next week", now)!!.dateTime)
        assertEquals(LocalDateTime.of(2026, 10, 21, 10, 0), parser.parse("will let u know by Wednesday", now)!!.dateTime)
        assertNotNull(detector.detect("I'll let you know by next week"))
        assertNotNull(detector.detect("will let u know by Wednesday"))
    }

    @Test
    fun parsesHours() {
        assertEquals(now.plusHours(2), TimeParser().parse("I'll send it in 2 hours", now)!!.dateTime)
        assertEquals(now.plusHours(1), TimeParser().parse("will share after 1 hour", now)!!.dateTime)
        assertEquals(now.plusHours(2), TimeParser().parseOrDefault("I'll do it", now).dateTime)
        assertEquals(now.plusMinutes(2), TimeParser().parse("bhej dungs in 2 mins", now)!!.dateTime)
        assertEquals(now.plusMinutes(15), TimeParser().parse("will share in 15 minutes", now)!!.dateTime)
        assertEquals(now.plusMinutes(5), TimeParser().parse("will send in 5 mins", now)!!.dateTime)
        assertEquals(now.plusMinutes(5), TimeParser().parse("will send in 5 min", now)!!.dateTime)
        assertEquals(now.plusMinutes(5), TimeParser().parse("will send in 5m", now)!!.dateTime)
        assertEquals(now.plusMinutes(5), TimeParser().parse("5 min ma bhejta hu", now)!!.dateTime)
        assertEquals(now.plusMinutes(5), TimeParser().parse("5 minutes mein bhejta hu", now)!!.dateTime)
        assertNotNull(detector.detect("5 min ma bhejta hu"))
    }

    @Test
    fun parsesClockForms() {
        val parser = TimeParser()
        assertEquals(LocalTime.of(17, 0), parser.parse("will share by 5", now)!!.dateTime.toLocalTime())
        assertEquals(LocalTime.of(17, 0), parser.parse("send kar dunga 5 baje", now)!!.dateTime.toLocalTime())
        assertEquals(LocalTime.of(17, 30), parser.parse("I'll send by 5:30 pm", now)!!.dateTime.toLocalTime())
        assertEquals(LocalTime.of(9, 0), parser.parse("will share at 9 am", now)!!.dateTime.toLocalTime())
        assertEquals(LocalTime.of(23, 0), parser.parse("I'll do it by 23:00", now)!!.dateTime.toLocalTime())
    }

    @Test
    fun parsesWeekdaysAndNextWeek() {
        val parser = TimeParser()
        assertEquals(LocalDateTime.of(2026, 10, 19, 10, 0), parser.parse("Monday tak kar dunga", now)!!.dateTime)
        assertEquals(LocalDateTime.of(2026, 10, 25, 10, 0), parser.parse("will share next week", now)!!.dateTime)
        assertEquals(LocalDateTime.of(2026, 12, 5, 10, 0), parser.parse("send it on 5/12", now)!!.dateTime)
        assertEquals(LocalDateTime.of(2027, 1, 6, 10, 0), parser.parse("I'll send it on 6-1-2027", now)!!.dateTime)
    }

    @Test
    fun namedTimesNeverLandInThePast() {
        val parser = TimeParser()
        val evening = LocalDateTime.of(2026, 10, 18, 19, 0)
        assertEquals(LocalDateTime.of(2026, 10, 19, 18, 0), parser.parse("will send by eod", evening)!!.dateTime)
        assertEquals(LocalDateTime.of(2026, 10, 19, 9, 30), parser.parse("send it in the morning", now)!!.dateTime)
        assertEquals(LocalDateTime.of(2026, 10, 18, 21, 0), parser.parse("aaj raat bhej dunga", now)!!.dateTime)
        assertEquals(evening.plusHours(2), parser.parse("aaj bata dunga", evening)!!.dateTime)
    }

    @Test
    fun bareNumbersAreNotClockTimes() {
        val parser = TimeParser()
        assertEquals(LocalDateTime.of(2026, 10, 19, 10, 0), parser.parse("I'll send 2 files tomorrow", now)!!.dateTime)
        assertEquals(LocalTime.of(17, 0), parser.parse("will send 2 files by 5", now)!!.dateTime.toLocalTime())
    }

    @Test
    fun questionsRequestsAndDoneMessagesAreNotCommitments() {
        listOf("did you send it", "please send it", "I already let you know", "do you share it?")
            .forEach { assertNull("Expected no match: $it", detector.detect(it)) }
    }

    @Test
    fun nextWeekIsTheSameDayNextWeekAndKeepsTheTime() {
        val parser = TimeParser()
        val sunday = LocalDateTime.of(2026, 10, 18, 17, 30)
        val wednesday = LocalDateTime.of(2026, 10, 21, 9, 0)
        assertEquals(LocalDateTime.of(2026, 10, 25, 10, 0), parser.parse("ill tell you more referneces next week", sunday)!!.dateTime)
        assertEquals(LocalDateTime.of(2026, 10, 28, 10, 0), parser.parse("will send it next week", wednesday)!!.dateTime)
        assertEquals(LocalDateTime.of(2026, 10, 25, 17, 0), parser.parse("will send next week by 5", sunday)!!.dateTime)
        assertEquals(LocalDateTime.of(2026, 10, 25, 10, 0), parser.parse("I'll call you next week at 10", sunday)!!.dateTime)
        assertEquals(LocalDateTime.of(2026, 10, 25, 9, 0), parser.parse("will call next week at 9 am", sunday)!!.dateTime)
        assertEquals(LocalDateTime.of(2026, 10, 25, 10, 0), parser.parse("agle hafte bhej dunga", sunday)!!.dateTime)
        assertEquals(LocalDateTime.of(2026, 10, 25, 10, 0), parser.parse("अगले हफ्ते भेज दूंगा", sunday)!!.dateTime)
        // A weekday picks that day in the week starting next Monday.
        assertEquals(LocalDateTime.of(2026, 10, 30, 10, 0), parser.parse("will send it friday next week", wednesday)!!.dateTime)
        assertEquals(LocalDateTime.of(2026, 10, 26, 10, 0), parser.parse("monday next week bhej dunga", wednesday)!!.dateTime)
    }

    @Test
    fun tellYouPromisesAreDetected() {
        listOf("ill tell you more referneces next week", "I'll tell you tomorrow", "will tell you the details later", "I'll inform you once it's done")
            .forEach { assertNotNull("Expected match: $it", detector.detect(it)) }
        listOf("he will tell you", "can you tell me?", "did she tell you", "you will tell me")
            .forEach { assertNull("Expected no match: $it", detector.detect(it)) }
    }

    @Test
    fun ownToDosAreDetectedButNotOtherPeoples() {
        val rules = RuleBasedCommitmentDetector()
        listOf("Need to order paneer by 5", "I have to submit the form tomorrow", "must pay the bill by friday",
            "ugh need to finish the slides tonight", "I've got to renew my passport", "ok gotta call mom at 6")
            .forEach { assertNotNull("Expected match: $it", rules.detect(it)) }
        listOf("you have to try this place", "he needs to call me back", "do I need to bring anything",
            "you must watch this movie", "they have to approve it first")
            .forEach { assertNull("Expected no match: $it", rules.detect(it)) }
        assertEquals(java.time.LocalTime.of(17, 0), TimeParser().parse("Need to order paneer by 5", now)!!.dateTime.toLocalTime())
    }

    @Test
    fun presentTenseWithATimeIsAPromiseInAnyCapitalisation() {
        val rules = RuleBasedCommitmentDetector()
        listOf("kal karta hu", "Kal karta hu", "KAL KARTA HU", "kal shaam ko karti hu", "parso bhejta hoon", "10 min mein karta hu")
            .forEach { assertNotNull("Expected match: $it", rules.detect(it)) }
        listOf("office mein main laptop use karta hu", "main roz office jaata hu", "Kal karta hu kya?", "mai subah chai peeta hu", "nhi krunga")
            .forEach { assertNull("Expected no match: $it", rules.detect(it)) }
    }

    @Test
    fun englishNegationIsNotAPromiseUnlessItAlsoPromises() {
        val rules = RuleBasedCommitmentDetector()
        listOf("Sadly, i don't get aise messages", "i didn't see your message", "i never said that")
            .forEach { assertEquals("Expected rejected: $it", true, rules.rejects(it)) }
        listOf("don't worry, I'll send it tomorrow", "dont worry will send by 5")
            .forEach { assertNotNull("Expected match: $it", rules.detect(it)) }
    }

    @Test
    fun pastStoriesAndHabitsAreRejectedUnlessTheyAlsoPromise() {
        val rules = RuleBasedCommitmentDetector()
        listOf(
            "Puri nahi hui cuz aaj mandir gaya tha toh bhagwan ne bachaliya", "aaj gym nahi gaya",
            "main roz gym jaata hu", "jab bhi bahar jaata hu toh mask pehenta hu", "मैं कल मंदिर गया था"
        ).forEach { assertEquals("Expected rejected: $it", true, rules.rejects(it)) }
        listOf(
            "aaj kaam tha isliye reply nahi kiya, kal bhej dunga", "late ho gaya sorry, abhi bhejta hu",
            "main nikalta hu toh call karta hu", "kal bhool gaya tha, aaj pakka kar dunga", "kal bhej dunga"
        ).forEach { assertEquals("Expected allowed: $it", false, rules.rejects(it)) }
    }

    @Test
    fun hinglishRequestsToOthersAreRejected() {
        val rules = RuleBasedCommitmentDetector()
        listOf("collar mic use nahi karrahe tph le ana", "kal file bhej dena", "notes bhej do", "charger le aao", "bread lao")
            .forEach { assertEquals("Expected rejected: $it", true, rules.rejects(it)) }
        listOf("kal bhej dunga", "main kar dunga", "de dunga kal", "le aaunga shaam ko", "bata dunga", "thoda wait karo main bhej dunga")
            .forEach { assertEquals("Expected allowed: $it", false, rules.rejects(it)) }
    }

    @Test
    fun questionsWithoutAQuestionMarkAreRejected() {
        val rules = RuleBasedCommitmentDetector()
        listOf("hi is gym open today or tomorrow", "kya kal office hai", "hey are you free tomorrow", "bro when is the class tomorrow", "do you have the file")
            .forEach { assertEquals("Expected rejected: $it", true, rules.rejects(it)) }
        listOf("do minute mein kar dunga", "hi, will send it tomorrow", "hey ill call you tomorrow", "kal bhej dunga")
            .forEach { assertEquals("Expected allowed: $it", false, rules.rejects(it)) }
    }

    @Test
    fun splitVerbEndingsStillMatch() {
        listOf("kal bhej ta hu", "abhi bhej ti hu", "bata ta hu", "kal bhej unga", "bhej ta hoon")
            .forEach { assertNotNull("Expected match: $it", detector.detect(it)) }
    }

    @Test
    fun joinedHelperVerbsStillMatch() {
        val rules = RuleBasedCommitmentDetector()
        listOf("kal subah kardunga", "kal bhejdunga", "shaam ko batadungi", "kal dedunga", "main karlunga")
            .forEach { assertNotNull("Expected match: $it", rules.detect(it)) }
        listOf("nahi kardunga", "main nahi kar dunga", "nhi bhejdungi")
            .forEach { assertNull("Expected no match: $it", rules.detect(it)) }
    }

    @Test
    fun anyFutureVerbWithATimeMatches() {
        val rules = RuleBasedCommitmentDetector()
        listOf("subah rakhlunga", "kal subah rakh lunga", "shaam ko le jaungi", "kal nikalunga", "raat ko dhoondhunga")
            .forEach { assertNotNull("Expected match: $it", rules.detect(it)) }
        listOf("shayad subah rakhlunga", "subah nahi aaunga", "kya kal aaunga")
            .forEach { assertNull("Expected no match: $it", rules.detect(it)) }
    }

    @Test
    fun maybesRefusalsAndOtherPeoplesPromisesAreRejected() {
        val rules = RuleBasedCommitmentDetector()
        listOf(
            "what time will you share it", "let u know?", "he said he will send", "shayad bhej dunga",
            "I won't be able to send it today", "try karunga par pakka nahi", "aaj nahi ho payega", "when will you send it"
        ).forEach {
            assertNull("Expected no match: $it", rules.detect(it))
            assertEquals("Expected model to be skipped: $it", true, rules.rejects(it))
        }
        listOf("I'll send it tomorrow", "kal bhej dunga", "ok will do", "मैं कल भेज दूंगा", "Please wait, I will do it")
            .forEach { assertEquals("Expected model to be consulted: $it", false, rules.rejects(it)) }
    }

    @Test
    fun defaultsAreConfigurable() {
        val custom = DefaultTimes(day = LocalTime.of(11, 15), noTimeDelayHours = 3)
        val parser = TimeParser(custom)
        assertEquals(LocalTime.of(11, 15), parser.parse("tomorrow", now)!!.dateTime.toLocalTime())
        assertEquals(now.plusHours(3), parser.parseOrDefault("I'll send it", now).dateTime)
    }

    @Test
    fun oClockIsATime() {
        val parser = TimeParser()
        val morning = LocalDateTime.of(2026, 10, 24, 10, 2)
        assertEquals(LocalDateTime.of(2026, 10, 24, 12, 0), parser.parse("12 o'clock karunga fir se", morning)!!.dateTime)
        assertEquals(LocalDateTime.of(2026, 10, 24, 12, 0), parser.parse("12 o’clock karunga", morning)!!.dateTime)
        assertEquals(LocalDateTime.of(2026, 10, 18, 22, 0), parser.parse("10 o'clock call karunga", now)!!.dateTime)
        assertEquals(LocalDateTime.of(2026, 10, 24, 10, 0), parser.parse("10 oclock call karunga", LocalDateTime.of(2026, 10, 24, 8, 0))!!.dateTime)
        assertEquals(LocalDateTime.of(2026, 10, 19, 10, 0), parser.parse("kal 10 o clock bhej dunga", now)!!.dateTime)
    }

    @Test
    fun spacingDotsAndShortFormsInTimesDoNotMatter() {
        val parser = TimeParser()
        val noon = LocalDateTime.of(2026, 10, 24, 12, 55)
        listOf("1 o clock", "1oclock", "1 o'clock", "1 o’clock", "1 O Clock", "1 o-clock", "1 oclk", "1 o clk", "1 o' clock", "1 p m", "1 P.M.", "1pm")
            .forEach { assertEquals("Wrong time: $it", LocalDateTime.of(2026, 10, 24, 13, 0), parser.parse("$it call karunga", noon)?.dateTime) }
        listOf("1 a.m.", "1 a m", "1AM").forEach { assertEquals("Wrong time: $it", LocalDateTime.of(2026, 10, 25, 1, 0), parser.parse("$it call karunga", noon)?.dateTime) }
        listOf("5 bje", "5 bajey", "5 baje", "5 बजे").forEach { assertEquals("Wrong time: $it", LocalTime.of(17, 0), parser.parse("$it call karunga", noon)?.dateTime?.toLocalTime()) }
        assertEquals(LocalTime.of(20, 30), parser.parse("will buy medicines at 8.30pm", noon)!!.dateTime.toLocalTime())
        assertEquals(LocalTime.of(20, 30), parser.parse("will buy medicines at 8.30 p.m.", noon)!!.dateTime.toLocalTime())
        // Detection uses the same parser, so every spelling is also caught as a promise.
        listOf("1 o clock call karunga", "1 oclk call karunga", "5 bje bhej dunga").forEach { assertNotNull("Expected match: $it", detector.detect(it)) }
        // A habit with a part of the day is still not a promise.
        assertNull(detector.detect("mai subah chai peeta hu"))
    }

    @Test
    fun pmWithoutASpaceStaysPm() {
        val parser = TimeParser()
        val evening = LocalDateTime.of(2026, 10, 24, 19, 0)
        assertEquals(LocalDateTime.of(2026, 10, 24, 20, 30), parser.parse("I'll buy medicines at 8:30PM", evening)!!.dateTime)
        assertEquals(LocalDateTime.of(2026, 10, 24, 20, 30), parser.parse("I'll buy medicines at 8:30 PM", evening)!!.dateTime)
        assertEquals(LocalDateTime.of(2026, 10, 25, 9, 0), parser.parse("will call at 9am", evening)!!.dateTime)
        assertEquals(LocalTime.of(17, 0), parser.parse("kal 5pm bhej dunga", now)!!.dateTime.toLocalTime())
    }

    @Test
    fun letMeWithoutATimeIsNotAPromise() {
        val rules = RuleBasedCommitmentDetector()
        listOf("k lemme play", "let me eat first", "lemme sleep").forEach {
            assertNull("Expected no match: $it", rules.detect(it))
            assertEquals("Expected model to be skipped: $it", false, rules.hasCommitmentCue(it) || TimeParser().parse(it, now) != null)
        }
        assertNotNull(TimeParser().parse("lemme call you at 5", now))
    }

    @Test
    fun englishPastTenseIsNotAPromiseUnlessItAlsoPromises() {
        val rules = RuleBasedCommitmentDetector()
        listOf("I completed all latest ep of Vision Quest", "I finished the report", "we just watched it", "I sent it yesterday", "I went to the gym", "I already paid")
            .forEach {
                assertNull("Expected no match: $it", rules.detect(it))
                assertEquals("Expected model to be skipped: $it", true, rules.rejects(it))
            }
        listOf("I finished part 1, will send the rest tomorrow", "I need to send it", "I'll send it tomorrow")
            .forEach { assertEquals("Expected model to be consulted: $it", false, rules.rejects(it)) }
    }

    @Test
    fun repeatingHinglishPromisesAreDetectedButHabitsAreNot() {
        listOf("har somvar mandir jaunga", "every monday call karunga", "har mahine rent bhejunga", "roz subah 7 baje walk karunga")
            .forEach { assertNotNull("Expected match: $it", detector.detect(it)) }
        listOf("main roz gym jaata hu", "har din chai peeta hu").forEach { assertNull("Expected no match: $it", detector.detect(it)) }
    }
}
