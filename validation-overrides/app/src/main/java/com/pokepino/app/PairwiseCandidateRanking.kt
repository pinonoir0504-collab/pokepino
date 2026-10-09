package com.pokepino.app

import kotlin.math.exp

/** Turns pairwise ranking logits into stable, relative candidate scores. */
internal object PairwiseCandidateRanking {
    fun rank(logits: Map<Int, Double>, limit: Int = 7): List<ShapeFusion.Candidate> {
        if (logits.isEmpty() || limit <= 0) return emptyList()
        require(logits.values.all { it.isFinite() })
        val maximum = logits.values.maxOrNull() ?: return emptyList()
        val exponentials = logits.mapValues { exp(it.value - maximum) }
        val total = exponentials.values.sum().coerceAtLeast(1e-12)
        return exponentials.map { ShapeFusion.Candidate(it.key, (it.value / total).toFloat()) }
            .sortedWith(compareByDescending<ShapeFusion.Candidate> { it.score }.thenBy { it.dex })
            .take(limit)
    }
}
