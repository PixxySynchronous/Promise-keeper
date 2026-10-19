package com.satyam.whatsappbot.domain

import java.text.Normalizer

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
        val phrase = phrases.firstOrNull { normalized.contains(normalize(it)) }
            ?: SELF_TODO.find(normalized)?.value?.trim()
            ?: return null
        return CommitmentMatch(message, phrase)
    }

    /**
     * True when the message is clearly not the sender's own promise: a question or request, something already done,
     * someone else's promise, a maybe or a refusal. Also used to overrule the semantic model, which was measured to
     * cut its false alarms without losing promises (tools/commitment_examples.py).
     */
    fun rejects(message: String): Boolean {
        val normalized = normalize(message)
        return hasClearNegativeSignal(message, normalized) || START_QUESTION_OR_UNCERTAINTY.containsMatchIn(normalized)
    }

    private fun hasClearNegativeSignal(message: String, normalized: String): Boolean =
        message.trimEnd().endsWith("?") || NEGATIVE_SIGNALS.any { it.containsMatchIn(normalized) } ||
            HINDI_NEGATIVE_SIGNALS.any { normalized.contains(it) } || hasOtherSubjectWill(message)

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
        .trim()

    private companion object {
        // Rejoins Hinglish verbs typed with a split ending: "bhej ta hu" -> "bhejta hu", "kar unga" -> "karunga".
        val SPLIT_VERB_ENDING = Regex("\\b(bhej|bhj|bata|bta|kar|kr|dekh|bol) (ta|ti|unga|ungi)\\b")
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
            Regex("\\b(nahi|nhi) (karunga|karungi|bhejunga|bhejungi|dunga|dungi)\\b"),
            // "I'll think and tell you", "if I get time": not a commitment yet
            Regex("\\b(soch ke|soch kar|sochta hu|sochti hu|agar|if i get time|if i find time)\\b")
        )
        val WORD_BEFORE_WILL = Regex("([a-z']+) +will\\b")
        val SELF_OR_FILLER = setOf(
            "i", "we", "ok", "okay", "k", "kk", "sure", "yes", "yeah", "ya", "yup", "yep", "fine", "alright", "cool",
            "np", "bro", "bhai", "yaar", "yr", "brb", "haan", "han", "ha", "hn", "pakka", "and", "then", "also", "so",
            "definitely", "surely", "really", "just", "def", "defo", "totally", "promise"
        )
        // Plain substrings: Java's \b treats Devanagari vowel signs as non-word characters.
        val HINDI_NEGATIVE_SIGNALS = listOf("शायद", "नहीं", "पता नहीं", "सोच", "अगर", "मैंने", "सकते", "सकती")
    }
}
