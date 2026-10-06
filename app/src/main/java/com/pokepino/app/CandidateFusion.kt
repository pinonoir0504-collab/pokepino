package com.pokepino.app

import kotlin.math.exp

/** Ranking scores are comparative evidence, not calibrated correctness probabilities. */
internal object CandidateFusion {
    data class Candidate(val dex: Int, val score: Float)

    fun rank(figure: List<Candidate>, classifier: List<Candidate>, supportedDex: Set<Int> = (1..1025).toSet()): List<Candidate> {
        val refs = figure.filter { it.dex in supportedDex && it.dex in 1..1025 && it.score.isFinite() }
            .groupBy { it.dex }.mapValues { (_, values) -> values.maxOf { it.score } }
        if (refs.isEmpty()) return classifier.filter { it.dex in supportedDex && it.dex in 1..1025 && it.score.isFinite() }.sortedByDescending { it.score }.take(7)
        val probs = classifier.filter { it.dex in supportedDex && it.dex in 1..1025 && it.score.isFinite() && it.score >= 0f }
            .groupBy { it.dex }.mapValues { (_, values) -> values.maxOf { it.score }.toDouble() }
        val peak = refs.values.maxOrNull()!!.toDouble()
        val weights = refs.mapValues { (_, value) -> exp((value.toDouble() - peak) / 0.04) }
        val figureTotal = weights.values.sum()
        val classifierTotal = probs.values.sum()
        val classifierWeight = if (classifierTotal > 0.0) 0.25 else 0.0
        return (refs.keys + probs.keys).map { dex ->
            val fs = (weights[dex] ?: 0.0) / figureTotal
            val cs = if (classifierTotal > 0.0) (probs[dex] ?: 0.0) / classifierTotal else 0.0
            Candidate(dex, ((1.0 - classifierWeight) * fs + classifierWeight * cs).toFloat())
        }.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.dex }).take(7)
    }
}
