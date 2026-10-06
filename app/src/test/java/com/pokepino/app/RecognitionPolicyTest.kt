package com.pokepino.app

import org.junit.Assert.*
import org.junit.Test

class RecognitionPolicyTest {
    private fun c(dex: Int, accepted: Boolean, strong: Boolean = false) = RecognitionPolicy.Candidate(dex, accepted, strong)

    @Test fun weakClassifierCannotOverrideFigureMatch() {
        val d = RecognitionPolicy.decide(c(25, true), c(1, false))
        assertEquals(25, d.dex)
        assertFalse(d.speciesTrusted)
    }
    @Test fun strongFigureSurvivesWeakConflictingClassifier() {
        val d = RecognitionPolicy.decide(c(25, true, true), c(1, false))
        assertEquals(25, d.dex)
        assertTrue(d.speciesTrusted)
        assertTrue(d.variantEligible)
    }
    @Test fun twoConfidentConflictingModelsRequireConfirmation() {
        val d = RecognitionPolicy.decide(c(25, true, true), c(1, true, true))
        assertEquals(25, d.dex)
        assertFalse(d.speciesTrusted)
        assertFalse(d.variantEligible)
    }
    @Test fun weakAgreementDoesNotAuthorizeRegistration() {
        val d = RecognitionPolicy.decide(c(25, false), c(25, false))
        assertEquals(25, d.dex)
        assertFalse(d.speciesTrusted)
        assertFalse(d.variantEligible)
    }
    @Test fun acceptedAgreementAuthorizesVariantChecking() {
        val d = RecognitionPolicy.decide(c(25, true), c(25, false))
        assertTrue(d.speciesTrusted)
        assertTrue(d.variantEligible)
    }
    @Test fun acceptedClassifierCanReplaceWeakFigureGuess() {
        val d = RecognitionPolicy.decide(c(25, false), c(1, true, true))
        assertEquals(1, d.dex)
        assertTrue(d.speciesTrusted)
        assertFalse(d.variantEligible)
    }
    @Test fun unavailableModelsProduceNoCandidate() {
        assertNull(RecognitionPolicy.decide(null, null).dex)
    }
}
