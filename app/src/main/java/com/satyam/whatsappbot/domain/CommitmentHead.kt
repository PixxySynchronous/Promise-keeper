package com.satyam.whatsappbot.domain

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Logistic-regression classifier over the sentence model's token vectors, trained offline by
 * tools/train_semantic_head.py. Pooling must stay identical to that script's `pool`.
 */
class CommitmentHead(private val weights: FloatArray, private val bias: Float, val threshold: Float) {

    /** [hidden] is the model's `last_hidden_state` for one message, including the `<s>` and `</s>` positions. */
    fun probability(hidden: List<FloatArray>): Float {
        val features = pool(hidden)
        require(features.size == weights.size) { "head expects ${weights.size} features, got ${features.size}" }
        var logit = bias.toDouble()
        for (i in features.indices) logit += features[i] * weights[i].toDouble()
        return (1.0 / (1.0 + exp(-logit))).toFloat()
    }

    // [L2(mean), L2(max)] over the token vectors, leaving out <s> and </s> when there is anything between them.
    private fun pool(hidden: List<FloatArray>): DoubleArray {
        val tokens = if (hidden.size > 2) hidden.subList(1, hidden.size - 1) else hidden
        val dimensions = tokens.first().size
        val mean = DoubleArray(dimensions)
        val peak = DoubleArray(dimensions) { Double.NEGATIVE_INFINITY }
        for (vector in tokens) {
            for (d in 0 until dimensions) {
                mean[d] += vector[d]
                if (vector[d] > peak[d]) peak[d] = vector[d].toDouble()
            }
        }
        for (d in 0 until dimensions) mean[d] /= tokens.size
        return normalized(mean) + normalized(peak)
    }

    private fun normalized(vector: DoubleArray): DoubleArray {
        val norm = sqrt(vector.sumOf { it * it }).coerceAtLeast(1e-12)
        return DoubleArray(vector.size) { vector[it] / norm }
    }
}
