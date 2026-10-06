package com.pokepino.app

import org.junit.Assert.*
import org.junit.Test

class CandidateFusionTest {
    private fun c(dex: Int, score: Float) = CandidateFusion.Candidate(dex, score)
    @Test fun concentratedClassifierEvidenceCanRecoverCloseFigureMatch() {
        val ranked = CandidateFusion.rank(listOf(c(390,.710f),c(80,.700f),c(25,.600f)), listOf(c(80,.90f),c(390,.01f),c(25,.01f)))
        assertEquals(80, ranked.first().dex)
    }
    @Test fun strongFigureEvidenceSurvivesDiffuseClassifierGuess() {
        val ranked = CandidateFusion.rank(listOf(c(183,.90f),c(80,.60f),c(25,.60f)), listOf(c(80,.02f),c(183,.01f),c(25,.01f)))
        assertEquals(183, ranked.first().dex)
    }
    @Test fun missingClassifierPreservesFigureOrder() {
        val ranked = CandidateFusion.rank(listOf(c(25,.7f),c(80,.6f)), emptyList())
        assertEquals(listOf(25,80), ranked.map { it.dex })
        assertTrue(ranked.all { it.score.isFinite() })
    }
    @Test fun invalidInputsDoNotCorruptRanking() {
        val ranked = CandidateFusion.rank(listOf(c(25,.7f),c(80,Float.NaN)), listOf(c(25,Float.NaN),c(80,Float.POSITIVE_INFINITY)))
        assertEquals(25,ranked.single().dex)
        assertEquals(1f,ranked.single().score,0.0001f)
    }
    @Test fun unknownClassifierSpeciesCannotHijackFigureCatalog() {
        val ranked = CandidateFusion.rank(listOf(c(25,.7f),c(80,.6f)), listOf(c(9999,1f)))
        assertEquals(25,ranked.first().dex)
    }
    @Test fun largeLogitDifferencesRemainNumericallyStable() {
        val ranked = CandidateFusion.rank(listOf(c(25,1e20f),c(80,-1e20f)), listOf(c(25,1f)))
        assertEquals(25,ranked.first().dex)
        assertTrue(ranked.all { it.score.isFinite() })
    }
}
