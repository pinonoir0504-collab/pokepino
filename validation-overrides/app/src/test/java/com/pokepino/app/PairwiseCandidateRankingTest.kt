package com.pokepino.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairwiseCandidateRankingTest {
    @Test fun higherLogitRanksFirstAndScoresSumToOne() {
        val ranked = PairwiseCandidateRanking.rank(mapOf(25 to -1.0, 54 to 2.0, 121 to 0.0))
        assertEquals(listOf(54, 121, 25), ranked.map { it.dex })
        assertTrue(kotlin.math.abs(ranked.sumOf { it.score.toDouble() } - 1.0) < 1e-6)
    }

    @Test fun tiesUseDexOrderAndLimitIsApplied() {
        val ranked = PairwiseCandidateRanking.rank(mapOf(54 to 3.0, 25 to 3.0, 121 to 2.0), limit = 2)
        assertEquals(listOf(25, 54), ranked.map { it.dex })
    }

    @Test fun extremeLogitsStayFinite() {
        val ranked = PairwiseCandidateRanking.rank(mapOf(1 to -1000.0, 2 to 1000.0))
        assertEquals(2, ranked.first().dex)
        assertTrue(ranked.all { it.score.isFinite() })
    }
}
