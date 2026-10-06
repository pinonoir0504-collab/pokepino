package com.pokepino.app

import org.junit.Assert.*
import org.junit.Test

class SpeciesPhotoRankingTest {
    private fun c(d: Int, s: Float) = SpeciesPhotoRanking.Candidate(d, s)

    @Test fun photoEvidenceCanRecoverSpeciesWhileRejectingInvalidSpecies() {
        val ranked = SpeciesPhotoRanking.rank(listOf(c(6,.74f), c(252,.73f)), listOf(c(252,.92f), c(9999,1f)))
        assertEquals(listOf(252,6),ranked.map { it.dex })
    }
    @Test fun verifiedPhotoCanSupplyMissingSpeciesWithoutInheritingTrust() {
        val ranked = SpeciesPhotoRanking.rank(listOf(c(25,.7f)), listOf(c(474,.9f)))
        assertEquals(474,ranked.first().dex)
        assertFalse(SpeciesPhotoRanking.preservesTrustedSpecies(474,25))
    }
    @Test fun weakPhotoDoesNotLowerExistingEvidence() {
        val ranked = SpeciesPhotoRanking.rank(listOf(c(25,.9f), c(80,.6f)), listOf(c(25,.7f)))
        assertEquals(.9f,ranked.first().score,0f)
    }
    @Test fun changedSpeciesDoesNotInheritAutomaticAcceptance() {
        assertFalse(SpeciesPhotoRanking.preservesTrustedSpecies(252,6))
        assertTrue(SpeciesPhotoRanking.preservesTrustedSpecies(25,25))
        assertFalse(SpeciesPhotoRanking.preservesTrustedSpecies(null,null))
    }
    @Test fun duplicateInvalidAndMissingPhotoInputsAreHandled() {
        val ranked = SpeciesPhotoRanking.rank(listOf(c(25,.5f),c(25,.7f),c(80,Float.NaN)), listOf(c(25,Float.NaN)))
        assertEquals(listOf(c(25,.7f)),ranked)
        assertEquals(listOf(c(25,.7f)),SpeciesPhotoRanking.rank(ranked,emptyList()))
    }
}
