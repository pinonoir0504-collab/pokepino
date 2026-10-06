package com.pokepino.app

/** Real-photo prototypes only supplement species ranking, never variant identity. */
internal object SpeciesPhotoRanking {
    data class Candidate(val dex: Int, val score: Float)

    fun rank(base: List<Candidate>, photos: List<Candidate>): List<Candidate> {
        val scores = base.filter { it.dex in 1..1025 && it.score.isFinite() }
            .groupBy { it.dex }.mapValues { (_, cs) -> cs.maxOf { it.score } }.toMutableMap()
        for (p in photos) {
            if (p.dex !in 1..1025 || !p.score.isFinite()) continue
            val old = scores[p.dex]
            scores[p.dex] = if (old == null) p.score else maxOf(old, p.score)
        }
        return scores.map { Candidate(it.key, it.value) }
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.dex })
    }

    fun preservesTrustedSpecies(chosenDex: Int?, baseDex: Int?): Boolean =
        chosenDex != null && baseDex != null && chosenDex == baseDex
}
