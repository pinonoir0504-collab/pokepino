package com.pokepino.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapePrototypeTest {
    @Test fun averagesSixViewsIntoCosineScores() {
        val model = ShapePrototype(2, intArrayOf(25, 258), floatArrayOf(1f, 0f, 0f, 1f), 0.02)
        val views = listOf(
            floatArrayOf(1f, 0f), floatArrayOf(1f, 0f), floatArrayOf(1f, 0f),
            floatArrayOf(1f, 0f), floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)
        )
        val result = model.scores(views).associateBy { it.dex }
        assertEquals(2, result.size)
        assertEquals(5f/6f, result.getValue(25).score, 1e-5f)
        assertEquals(1f/6f, result.getValue(258).score, 1e-5f)
        assertTrue(result.getValue(25).score > result.getValue(258).score)
    }

    @Test fun averagesTopThreeReferencesPerSpeciesAndUsesAllForSparseSpecies() {
        val refs = intArrayOf(25, 25, 25, 25, 26, 26)
        val vectors = floatArrayOf(
            1f, 0f,
            .8f, .6f,
            .6f, .8f,
            -1f, 0f,
            .4f, .9f,
            .2f, .98f
        )
        val model = ShapePrototype(2, refs, vectors, .02, maxExemplars = 3)
        val result = model.scores(listOf(floatArrayOf(1f, 0f))).associateBy { it.dex }
        assertEquals(.8f, result.getValue(25).score, 1e-5f)
        assertEquals(.3f, result.getValue(26).score, 1e-5f)
    }
}
