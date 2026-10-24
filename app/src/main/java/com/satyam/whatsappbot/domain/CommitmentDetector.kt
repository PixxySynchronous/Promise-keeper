package com.satyam.whatsappbot.domain

import java.text.Normalizer
import java.time.LocalDateTime

interface CommitmentDetector {
    fun detect(message: String): CommitmentMatch?
}

data class CommitmentMatch(
    val originalMessage: String,
    val matchedPhrase: String
)

class RuleBasedCommitmentDetector(
    private val phrases: List<String> = CommitmentPhrases.all
) : CommitmentDetector {
    override fun detect(message: String): CommitmentMatch? {
        val normalized = normalize(message)
        val startsAsQuestionOrUncertainty = START_QUESTION_OR_UNCERTAINTY.containsMatchIn(normalized)
        if (hasClearNegativeSignal(message, normalized) || (startsAsQuestionOrUncertainty && phrases.none { normalized.contains(normalize(it)) && normalized.indexOf(normalize(it)) > 20 })) {
            return null
        }
        // Computed once, only if a rule below needs it. A part of the day or "weekend" alone parses with a default time;
        // with "-ta hu" that is usually a habit ("subah chai peeta hu"), so the present tense needs a clock time, a
        // duration or a date.
        val parsed by lazy { timeParser.parse(message, LocalDateTime.now()) }
        val hasClockOrDate by lazy { TIME_WORD.containsMatchIn(normalized) || parsed?.usedDefault == false }
        val phrase = phrases.firstOrNull { normalized.contains(normalize(it)) }
            ?: SELF_TODO.find(normalized)?.value?.trim()
            // Hinglish uses the present for the near future: "kal karta hu", "5 baje bhejti hu".
            ?: FIRST_PERSON_PRESENT.find(normalized)?.takeIf { hasClockOrDate }?.value
            // Any first-person future verb with any time: "subah rakhlunga", "shaam ko le jaungi", "kal nikalunga",
            // "1 o clock call karunga", or a repeat or Hindi weekday: "har somvar mandir jaunga".
            ?: HINGLISH_OWN_FUTURE.find(normalized)
                ?.takeIf { hasClockOrDate || parsed != null || PART_OF_DAY.containsMatchIn(normalized) || REPEAT_OR_HINDI_DAY.containsMatchIn(normalized) }?.value
            // The same in English: the sender's own future with any time or repeat, "I'll wish her good morning every
            // day at 10 am", "I'm going to call you at 5". Someone else's "will" was already rejected above.
            ?: ENGLISH_OWN_FUTURE.find(normalized)
                ?.takeIf { hasClockOrDate || parsed != null || REPEAT_OR_HINDI_DAY.containsMatchIn(normalized) }?.value
            // Hinglish to-dos with a time or repeat: "daily leetcode karna hai", "kal bank jaana hai".
            ?: HINGLISH_TODO.find(normalized)
                ?.takeIf { isHinglishToDo(normalized) && (hasClockOrDate || parsed != null || REPEAT_OR_HINDI_DAY.containsMatchIn(normalized)) }?.value
            ?: return null
        return CommitmentMatch(message, phrase)
    }

    // Any time the parser understands counts as a time here too, so new time formats need no change in this class.
    private val timeParser = TimeParser()

    /**
     * True when the message is clearly not the sender's own promise: a question or request, something already done,
     * someone else's promise, a maybe or a refusal. Also used to overrule the semantic model, which was measured to
     * cut its false alarms without losing promises (tools/commitment_examples.py).
     */
    fun rejects(message: String): Boolean {
        val normalized = normalize(message)
        return hasClearNegativeSignal(message, normalized) || START_QUESTION_OR_UNCERTAINTY.containsMatchIn(normalized)
    }

    /**
     * Whether a message has any sign of the sender committing to something, so the model is worth asking. Without
     * one ("ok fatty", "haha sahi hai") the model over-fires on short chatter. Time expressions are checked by the
     * caller with [TimeParser], so a cue-less "kal tak" still reaches the model.
     */
    fun hasCommitmentCue(message: String): Boolean {
        val normalized = normalize(message)
        return COMMITMENT_CUE.containsMatchIn(normalized) || DEVANAGARI_CUES.any { normalized.contains(it) }
    }

    private fun hasClearNegativeSignal(message: String, normalized: String): Boolean =
        message.trimEnd().endsWith("?") || NEGATIVE_SIGNALS.any { it.containsMatchIn(normalized) } ||
            HINDI_NEGATIVE_SIGNALS.any { normalized.contains(it) } || hasOtherSubjectWill(message) ||
            // A request ("wait karo") only rejects when the sender isn't also promising ("main bhej dunga").
            (HINGLISH_REQUEST.containsMatchIn(normalized) && !HINGLISH_OWN_FUTURE.containsMatchIn(normalized)) ||
            // A story about the past ("aaj mandir gaya tha"), unless the message also promises something now or later
            // ("aaj kaam tha isliye nahi kiya, kal bhej dunga").
            (isPastTense(normalized) && !hasFuture(normalized) && !FIRST_PERSON_PRESENT.containsMatchIn(normalized)) ||
            // A habit ("main roz gym jaata hu"): "-ta hu" here describes what usually happens, so only a real future counts.
            // The sender's own to-do ("need to do leetcode daily", "daily leetcode karna hai") looks ahead, so it isn't a
            // habit either.
            (HABIT.containsMatchIn(normalized) && !hasFuture(normalized) && !SELF_TODO.containsMatchIn(normalized) && !isHinglishToDo(normalized)) ||
            // English negation ("i don't get aise messages") unless there's also a promise ("don't worry, I'll send it").
            (ENGLISH_NEGATION.containsMatchIn(normalized) && !hasFuture(normalized))

    // "<verb>na hai" is "I have to", unless it's a question ("kya karna hai") or someone else's ("tumhe karna hai").
    private fun isHinglishToDo(normalized: String) =
        HINGLISH_TODO.containsMatchIn(normalized) && !HINGLISH_TODO_NOT_MINE.containsMatchIn(normalized)

    private fun isPastTense(normalized: String) =
        HINGLISH_PAST.containsMatchIn(normalized) || DEVANAGARI_PAST.any { normalized.contains(it) } ||
            ENGLISH_PAST.find(normalized)?.let { it.groupValues[3] !in NOT_PAST_ED } == true

    private fun hasFuture(normalized: String) =
        HINGLISH_OWN_FUTURE.containsMatchIn(normalized) || ENGLISH_FUTURE.containsMatchIn(normalized) ||
            DEVANAGARI_FUTURE.any { normalized.contains(it) }

    // "Priya will send", "the bank will call": a word directly before "will" (no punctuation between) that is not
    // the sender or a filler like "ok"/"sure" means someone else is promising. "on it, will update" still counts.
    private fun hasOtherSubjectWill(message: String): Boolean =
        WORD_BEFORE_WILL.findAll(message.lowercase().replace("’", "'")).any { it.groupValues[1] !in SELF_OR_FILLER }

    // Keeps Latin letters, digits, apostrophes and Devanagari. NFC makes precomposed and decomposed forms (e.g. nukta
    // letters) compare equal; anusvara vs chandrabindu spellings are different letters, so the phrase list has both.
    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)
        .lowercase()
        .replace("’", "'")
        .replace(Regex("[^a-z0-9'\\u0900-\\u097F]+"), " ")
        .replace(SPLIT_VERB_ENDING, "$1$2")
        .replace(JOINED_HELPER_VERB, "$1 $2")
        .trim()

    private companion object {
        // Rejoins Hinglish verbs typed with a split ending: "bhej ta hu" -> "bhejta hu", "kar unga" -> "karunga".
        val SPLIT_VERB_ENDING = Regex("\\b(bhej|bhj|bata|bta|kar|kr|dekh|bol) (ta|ti|unga|ungi)\\b")
        // Splits helper verbs typed as one word: "kardunga" -> "kar dunga", "bhejdungi" -> "bhej dungi", "karlunga".
        val JOINED_HELPER_VERB = Regex("\\b(bhej|bhj|bata|bta|kar|kr|de|rakh|sambhal)(dunga|dungi|doonga|doongi|lunga|lungi)\\b")
        // The sender's own to-dos: "need to order paneer by 5", "I have to submit it tomorrow". Only at the start
        // (after an optional greeting/filler) or with "I" as the subject, so "you have to try this" does not match.
        val SELF_TODO = Regex(
            "^((hi+|hey+|hello|ok|okay|ugh|oh|bro|bhai|yaar|yr|so|also|btw)\\s+)*(need to|have to|must|gotta|got to)\\b" +
                "|\\bi (really |still |also |just )?(need to|have to|must|gotta|got to)\\b|\\bi'?ve (got )?to\\b"
        )
        val START_QUESTION_OR_UNCERTAINTY = Regex("^(can you|will you|could you|would you|maybe|i was going to)\\b")
        val NEGATIVE_SIGNALS = listOf(
            // someone else's promise: "he will", "she said she'll send"
            Regex("^(he|she|they|you) will\\b"),
            Regex("\\b(he|she|they) (said|told|promised)\\b"),
            // questions and requests: "did you send it", "please send it", "when will you share"
            Regex("^(did|do|does|have|has) (you|u|he|she|they)\\b"),
            Regex("^(please|pls|plz) (send|share|forward|call|reply|confirm|do|let)\\b"),
            Regex("\\b(when|what time|where|why|how) (will|would|can|do|did) (you|u)\\b"),
            // Questions typed without "?": "hi is gym open today", "kya kal office hai". Bare "do" is excluded because in
            // Hinglish it also means "two" ("do minute mein kar dunga").
            Regex(
                "^((hi+|hey+|hello|helo|yo|bro|bhai|yaar|yr|dude|ok|okay|acha|achha|sir|maam|ma'am|good morning|gm)\\s+)*" +
                    "(is|are|am|was|were|has|what|when|where|why|how|which|who|whose|kya|kab|kahan|kaha|kyu|kyun|kyon|kaise|kitna|kitne|kitni|kaun|kidhar" +
                    "|(do|does|did|can|could|would|should|shall) (you|u|we|i|he|she|they|it))\\b"
            ),
            // already done
            Regex("\\balready\\b"),
            // maybes and refusals, English and Hinglish
            Regex("\\b(maybe|might|probably|shayad|shyd|not sure|no promises|pakka nahi|pata nahi|pta ni|try karunga|try karungi)\\b"),
            Regex("\\b(won't|wont|can't|cant|cannot|couldn't|should|would have) "),
            Regex("\\b(nahi|nhi|ni) (ho|bhej|kar|kr|de|aa) ?(paunga|paungi|payega|payegi|pauga|sakta|sakti|saka)\\b"),
            // "nahi aaunga", "nhi kar dunga": a refusal with any first-person future verb.
            Regex("\\b(nahi|nhi|ni) (\\w+ )?\\w*(unga|ungi|oonga|oongi)\\b"),
            // "I'll think and tell you", "if I get time": not a commitment yet
            Regex("\\b(soch ke|soch kar|sochta hu|sochti hu|agar|if i get time|if i find time)\\b"),
            // The English equivalent: "I'll think about it", "let me think", "will think it over".
            Regex("\\b(think about it|think it over|think and tell|let me think|lemme think)\\b")
        )
        val WORD_BEFORE_WILL = Regex("([a-z']+) +will\\b")
        val SELF_OR_FILLER = setOf(
            "i", "we", "ok", "okay", "k", "kk", "sure", "yes", "yeah", "ya", "yup", "yep", "fine", "alright", "cool",
            "np", "bro", "bhai", "yaar", "yr", "brb", "haan", "han", "ha", "hn", "pakka", "and", "then", "also", "so",
            "definitely", "surely", "really", "just", "def", "defo", "totally", "promise", "worry", "tension"
        )
        // Hinglish requests to the other person ("le aana", "bhej dena", "kar do"). Whole words, so the sender's
        // own "bhej dunga" / "kar dunga" never match.
        val HINGLISH_REQUEST = Regex(
            "\\b(le aana|le ana|le aao|le ao|lete aana|leke aana|lekar aana|bhej dena|bhej dijiye|kar dena|kr dena|" +
                "kar dijiye|de dena|de dijiye|bata dena|bta dena|bata do|bta do|yaad dila dena|dila dena|bhej do|bhj do|" +
                "kar do|kr do|de do|le lo|lao|laana|bhejo|bhejna|batao|btao|karo|kro|dedo|krdo|kardo|bhejdo|" +
                "aajao|aa jao|aaja|aa ja|aa jana|aajana|chale aao|chale aana|aao|jao|chale jao)\\b"
        )
        val COMMITMENT_CUE = Regex(
            // English: first person, future, sending. Not "let me"/"lemme": without a time it is usually about right
            // now ("k lemme play"); with one, the time already sends the message to the model.
            "\\b(i|i'll|ill|i'm|im|i've|ive|i'd|we'll|will|wil|gonna|going to|on it|sending|send|need to|have to|must|gotta)\\b" +
                // Hinglish: first-person future (-unga/-ungi) or present ("bhejta hu"), and main/mai
                "|\\b\\w*(unga|ungi|oonga|oongi|lunga|lungi)\\b|\\b(hu|hoon|hun|main|mai)\\b"
        )
        val DEVANAGARI_CUES = listOf("ूंगा", "ूंगी", "ूँगा", "ूँगी", "ऊंगा", "ऊंगी", "ऊँगा", "ऊँगी", "हूं", "हूँ", "मैं")
        // Hinglish past tense: "gaya tha", "nahi hui", "bacha liya", "kar diya".
        val HINGLISH_PAST = Regex("\\b(tha|thi|thhe|hua|hui|huyi|huye|liya|liye|diya|diye|kiya|kiye|gaya|gayi|gaye|chuka|chuki|bachaliya)\\b")
        // English past with the sender as subject: "i completed", "we just finished", "i already sent". Any "-ed" verb
        // or a common irregular one; "-ed" words that aren't past tense are in NOT_PAST_ED.
        val ENGLISH_PAST = Regex(
            "\\b(i|we)\\s+((?:just|already|also|finally|actually|literally|totally|even|never|recently)\\s+)?" +
                "(\\w+ed|did|went|sent|got|saw|made|took|gave|told|bought|brought|paid|ate|came|left|wrote|forgot|thought|" +
                "met|had|was|were|found|kept|put|said|spoke|slept|won|lost|heard|understood|caught|began|drank|drove|felt|" +
                "knew|ran|sat|sold|spent|taught|woke|wore|binged)\\b"
        )
        val NOT_PAST_ED = setOf("need", "feed", "proceed", "exceed", "succeed", "bleed", "seed", "breed", "speed", "heed", "weed", "red", "bed", "shed", "embed")
        val DEVANAGARI_PAST = listOf("था", "थी", "हुआ", "हुई", "लिया", "दिया", "किया", "गया", "गई", "चुका", "चुकी")
        // Hinglish "have to": an infinitive (-na/-ni) followed by hai, "karna hai", "jaana hai", "padhni hai".
        val HINGLISH_TODO = Regex("\\b\\w+(na|ni)\\s+(hai|h|he|hain)\\b")
        val HINGLISH_TODO_NOT_MINE = Regex(
            "\\b(kya|kab|kaise|kahan|kaha|kyu|kyun|kitna|kitne|kaun|tumhe|tumhein|tujhe|aapko|apko|usko|use|unko|inko|isko|tumko|tuje)\\b"
        )
        // First person only; bare "will" is left out because "the shop will open at 10" isn't the sender's promise.
        val ENGLISH_OWN_FUTURE = Regex("\\b(i'll|ill|i will|i wil|i'm going to|im going to|i am going to|i'm gonna|im gonna|we'll|we will)\\b")
        val ENGLISH_FUTURE = Regex("\\b(will|wil|i'll|ill|gonna|going to|i'm going to)\\b")
        val DEVANAGARI_FUTURE = listOf("ूंगा", "ूंगी", "ूँगा", "ूँगी", "ऊंगा", "ऊंगी", "ऊँगा", "ऊँगी")
        // "abhi bhejta hu", "karti hoon": in Hinglish this is often "I'll do it now".
        val FIRST_PERSON_PRESENT = Regex("\\b\\w+(ta|ti) (hu|hoon|hun)\\b")
        // Time words the parser doesn't read as a time ("abhi", "baad mein", "5 min" without "mein"); everything it
        // does read counts too (see hasTime in detect). One-off times only: "subah"/"shaam"/"raat" with -ta hu are
        // often habits ("subah chai peeta hu").
        val TIME_WORD = Regex(
            "\\b(kal|kl|tomorrow|tom|tmrw|aaj|abhi|parso|baad mein|baad me|baad mai|thodi der|thodi der mein|" +
                "monday|tuesday|wednesday|thursday|friday|saturday|sunday|\\d+\\s*(min|mins|minute|minutes|ghante|hours?|baje|bje))\\b"
        )
        // Safe with a future verb: "subah karunga" is a plan, unlike the habit "subah karta hu".
        val PART_OF_DAY = Regex("\\b(subah|subha|shaam|sham|raat|dopahar|dopehar|morning|evening|tonight)\\b")
        // Only with a future verb: with "-ta hu" these describe habits ("har hafte jaata hu").
        val REPEAT_OR_HINDI_DAY = Regex(
            "\\b(har|every|each|daily|weekly|monthly|yearly|roz|rozana|somvar|somwar|mangalvar|mangalwar|budhvar|budhwar|" +
                "guruvar|guruwar|shukravar|shukrawar|shanivar|shaniwar|ravivar|raviwar|itwar|itvar)\\b"
        )
        val ENGLISH_NEGATION =Regex("\\b(don't|dont|do not|didn't|didnt|doesn't|doesnt|haven't|havent|hasn't|hasnt|never)\\b")
        val HABIT = Regex("\\b(roz|rozana|daily|har din|har roz|hamesha|humesha|usually|always|generally|aksar|jab bhi|every day|everyday|every morning|every night)\\b")
        // First-person future verbs: "bhej dunga", "karungi", "le aaunga", "krunga".
        val HINGLISH_OWN_FUTURE = Regex("\\b\\w*(unga|ungi|oonga|oongi|ungaa)\\b")
        // Plain substrings: Java's \b treats Devanagari vowel signs as non-word characters.
        val HINDI_NEGATIVE_SIGNALS = listOf("शायद", "नहीं", "पता नहीं", "सोच", "अगर", "मैंने", "सकते", "सकती")
    }
}
