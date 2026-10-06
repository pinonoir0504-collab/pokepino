package com.pokepino.app

/** Real-photo prototypes only supplement species ranking, never variant identity. */
internal object SpeciesPhotoRanking {
    data class Candidate(val dex: Int, val score: Float)

    fun rank(base: List<Candidate>, photos: List<Candidate>): List<Candidate> {
        val scores = base.filter { it.dex > 0 && it.score.isFinite() }
            .groupBy { it.dex }.mapValues { (_, cs) -> cs.maxOf { it.score } }.toMutableMap()
        for (p in photos) {
            val old = scores[p.dex] ?: continue
            if (p.score.isFinite()) scores[p.dex] = maxOf(old, p.score)
        }
        return scores.map { Candidate(it.key, it.value) }
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.dex })
    }

    fun preservesTrustedSpecies(chosenDex: Int?, baseDex: Int?): Boolean =
        chosenDex != null && baseDex != null && chosenDex == baseDex
}
