package com.pokepino.app

/** Keeps untrusted classifier guesses from overriding accepted figure matches. */
internal object RecognitionPolicy {
    data class Candidate(val dex: Int, val accepted: Boolean, val veryStrong: Boolean = false)
    data class Decision(val dex: Int?, val speciesTrusted: Boolean, val variantEligible: Boolean)

    fun decide(figure: Candidate?, classifier: Candidate?): Decision {
        val chosen = when {
            figure?.accepted == true -> figure
            classifier?.accepted == true -> classifier
            figure != null -> figure
            else -> classifier
        }
        val agreement = figure != null && classifier != null && figure.dex == classifier.dex
        val confidentConflict = figure?.accepted == true && classifier?.accepted == true && !agreement
        val speciesTrusted = !confidentConflict && (
            (agreement && (figure?.accepted == true || classifier?.accepted == true)) ||
            (chosen === figure && figure?.veryStrong == true) ||
            (chosen === classifier && classifier?.veryStrong == true)
        )
        val variantEligible = !confidentConflict && (
            (agreement && (figure?.accepted == true || classifier?.accepted == true)) ||
            (chosen === figure && figure?.veryStrong == true)
        )
        return Decision(chosen?.dex, speciesTrusted, variantEligible)
    }
}
