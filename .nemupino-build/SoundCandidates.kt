package com.oyasumi.recorder

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp

/** Lightweight offline signal heuristics, not a trained classifier or medical measurement. */
data class SoundFeature(val offsetMs: Long, val db: Float, val lowRatio: Float, val crossingRate: Float)
data class EnvelopeAnalysis(val samples: List<VolumeSample>, val features: List<SoundFeature>)

class SoundFeatureMeter(rate: Int) {
    private val alpha = 1.0 - exp(-2 * PI * 500 / rate)
    private var low = 0.0
    private var energy = 0.0
    private var lowEnergy = 0.0
    private var crossings = 0
    private var count = 0
    private var previous = 0
    fun add(value: Int) {
        low += alpha * (value - low)
        energy += value.toDouble() * value
        lowEnergy += low * low
        if (count > 0 && (value >= 0) != (previous >= 0)) crossings++
        previous = value; count++
    }
    fun finish(offset: Long, db: Float): SoundFeature {
        val result = SoundFeature(
            offset,
            db,
            if (energy > 0) (lowEnergy / energy).toFloat().coerceIn(0f, 1f) else 0f,
            if (count > 1) crossings.toFloat() / (count - 1) else 0f
        )
        energy = 0.0; lowEnergy = 0.0; crossings = 0; count = 0
        return result
    }
}

object SoundCandidates {
    fun refine(existing: List<SoundEvent>, features: List<SoundFeature>, durationMs: Long): List<SoundEvent> {
        if (features.isEmpty() || durationMs <= 0L) return existing
        val ordered = if (features.zipWithNext().all { (a, b) -> a.offsetMs <= b.offsetMs }) features else features.sortedBy { it.offsetMs }
        val detected = detectAdaptive(ordered, durationMs)
        val classified = classify(detected, ordered)
        return preserveManualLabels(classified, existing, durationMs)
    }

    fun detectAdaptive(features: List<SoundFeature>, durationMs: Long): List<SoundEvent> {
        if (features.size < 3 || durationMs <= 0L) return emptyList()
        val ordered = if (features.zipWithNext().all { (a, b) -> a.offsetMs <= b.offsetMs }) features else features.sortedBy { it.offsetMs }
        val floor = percentileDb(ordered, 0.20f)
        val openThreshold = maxOf(floor + 6f, -50f)
        val keepThreshold = maxOf(floor + 3f, -54f)
        val closeAfterMs = 650L
        val preRollMs = 350L
        val postRollMs = 650L

        val result = mutableListOf<SoundEvent>()
        var first: Long? = null
        var lastActive: Long? = null
        var peak = -60f

        fun close() {
            val startActive = first ?: return
            val endActive = lastActive ?: startActive
            val rawStart = (startActive - preRollMs).coerceAtLeast(0L)
            val rawEnd = (endActive + postRollMs).coerceAtMost(durationMs)
            if (rawEnd > rawStart && peak.isFinite()) {
                val event = SoundEvent(rawStart, rawEnd, peak)
                val previous = result.lastOrNull()
                if (previous != null && event.startMs <= previous.endMs) {
                    result[result.lastIndex] = previous.copy(
                        endMs = maxOf(previous.endMs, event.endMs),
                        peakDb = maxOf(previous.peakDb, event.peakDb)
                    )
                } else result += event
            }
            first = null; lastActive = null; peak = -60f
        }

        ordered.forEach { feature ->
            if (feature.offsetMs !in 0..durationMs || !feature.db.isFinite()) return@forEach
            val opening = feature.db >= openThreshold
            val sustaining = first != null && feature.db >= keepThreshold
            if (opening || sustaining) {
                if (first == null) first = feature.offsetMs
                lastActive = feature.offsetMs
                peak = maxOf(peak, feature.db)
            } else {
                val last = lastActive
                if (last != null && feature.offsetMs - last >= closeAfterMs) close()
            }
        }
        close()
        return result
    }

    fun classify(events: List<SoundEvent>, features: List<SoundFeature>): List<SoundEvent> = events.map { event ->
        val local = window(features, event.startMs, event.endMs)
        val localCandidate = classifyWindow(local)
        val neighbour = window(features, (event.startMs - 12_000L).coerceAtLeast(0L), event.endMs + 12_000L)
        // A short breath/snore event can be loud for its whole local window. Use the surrounding
        // context for the floor so the breath itself is not mistaken for background noise.
        val contextFloor = percentileDb(if (neighbour.size >= 20) neighbour else local, 0.20f)
        val audibleThreshold = maxOf(contextFloor + 6f, -50f)
        val audible = local.filter { it.db > audibleThreshold }

        val candidate = when {
            localCandidate == "環境音候補" -> localCandidate
            isImpact(local) -> "物音候補"
            isCough(local) -> "咳候補"
            isSleepTalk(local) -> "寝言候補"
            isVoiceOrBark(local) -> "声・犬候補"
            audible.size >= 3 && audible.map { it.lowRatio }.average() >= 0.25 &&
                audible.map { it.crossingRate }.average() <= 0.25 &&
                classifyWindow(neighbour) == "いびき候補" -> "いびき候補"
            else -> "不明"
        }
        event.copy(candidate = candidate)
    }

    private fun preserveManualLabels(refined: List<SoundEvent>, existing: List<SoundEvent>, durationMs: Long): List<SoundEvent> {
        val manual = existing.withIndex().filter { it.value.label.isNotBlank() }
        if (manual.isEmpty()) return refined
        val used = mutableSetOf<Int>()
        val labeled = refined.map { event ->
            val center = (event.startMs + event.endMs) / 2L
            val match = manual
                .filterNot { it.index in used }
                .map { indexed ->
                    val old = indexed.value
                    val overlap = (minOf(event.endMs, old.endMs) - maxOf(event.startMs, old.startMs)).coerceAtLeast(0L)
                    val oldCenter = (old.startMs + old.endMs) / 2L
                    val score = if (overlap > 0) 10_000_000L + overlap else (3_000L - abs(center - oldCenter)).coerceAtLeast(0L)
                    indexed to score
                }
                .maxByOrNull { it.second }
                ?.takeIf { it.second > 0L }
            if (match != null) {
                used += match.first.index
                event.copy(label = match.first.value.label)
            } else event
        }.toMutableList()

        manual.filterNot { it.index in used }.forEach { indexed ->
            val old = indexed.value
            val start = old.startMs.coerceIn(0L, durationMs)
            val end = old.endMs.coerceIn(start, durationMs)
            if (end > start) labeled += old.copy(startMs = start, endMs = end)
        }
        return labeled.sortedBy { it.startMs }
    }

    private fun window(features: List<SoundFeature>, start: Long, end: Long): List<SoundFeature> {
        if (features.isEmpty()) return emptyList()
        fun bound(time: Long, inclusive: Boolean): Int {
            var low = 0
            var high = features.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (features[middle].offsetMs < time || (inclusive && features[middle].offsetMs == time)) low = middle + 1
                else high = middle
            }
            return low
        }
        return features.subList(bound(start, false), bound(end, true))
    }

    private data class Burst(val start: Long, val end: Long, val peak: Float) {
        val duration get() = (end - start).coerceAtLeast(50L)
    }

    private fun percentileDb(window: List<SoundFeature>, percentile: Float): Float {
        if (window.isEmpty()) return -60f
        val values = window.asSequence().map { it.db }.filter { it.isFinite() }.sorted().toList()
        if (values.isEmpty()) return -60f
        val index = ((values.lastIndex) * percentile.coerceIn(0f, 1f)).toInt().coerceIn(0, values.lastIndex)
        return values[index]
    }

    private fun bursts(window: List<SoundFeature>): Pair<Float, List<Burst>> {
        if (window.size < 3) return -60f to emptyList()
        val floor = percentileDb(window, 0.20f)
        val threshold = maxOf(floor + 9f, -48f)
        val result = mutableListOf<Burst>()
        var start: Long? = null
        var end = 0L
        var peak = -60f
        window.forEach { f ->
            if (f.db >= threshold) {
                if (start == null) start = f.offsetMs
                end = f.offsetMs + 50L
                peak = maxOf(peak, f.db)
            } else if (start != null) {
                result += Burst(start!!, end, peak)
                start = null; peak = -60f
            }
        }
        if (start != null) result += Burst(start!!, end, peak)
        return floor to result
    }

    private fun isImpact(window: List<SoundFeature>): Boolean {
        val (floor, bs) = bursts(window)
        if (bs.size != 1) return false
        val b = bs.single()
        return b.duration <= 450L && b.peak - floor >= 18f
    }

    private fun isCough(window: List<SoundFeature>): Boolean {
        val (_, bs) = bursts(window)
        if (bs.isEmpty() || bs.size > 2) return false
        if (bs.any { it.duration !in 200L..1400L }) return false
        val loud = window.filter { f -> bs.any { f.offsetMs in it.start..it.end } }
        if (loud.size < 3) return false
        val low = loud.map { it.lowRatio }.average()
        val crossing = loud.map { it.crossingRate }.average()
        return crossing >= 0.16 && low < 0.55
    }

    private fun isSleepTalk(window: List<SoundFeature>): Boolean {
        val (_, bs) = bursts(window)
        if (bs.isEmpty() || bs.size > 5) return false
        if (bs.none { it.duration >= 1200L }) return false
        val loud = window.filter { f -> bs.any { f.offsetMs in it.start..it.end } }
        if (loud.size < 8) return false
        val low = loud.map { it.lowRatio }.average()
        val crossing = loud.map { it.crossingRate }.average()
        return crossing >= 0.17 && low < 0.48
    }

    private fun isVoiceOrBark(window: List<SoundFeature>): Boolean {
        val (_, bs) = bursts(window)
        if (bs.size < 2 || bs.size > 7) return false
        if (bs.count { it.duration <= 1200L } < 2) return false
        val starts = bs.map { it.start }
        val closeRepeats = starts.zipWithNext { a, b -> b - a }.count { it in 250L..2200L }
        if (closeRepeats < 1) return false
        val loud = window.filter { f -> bs.any { f.offsetMs in it.start..it.end } }
        if (loud.size < 4) return false
        val low = loud.map { it.lowRatio }.average()
        val crossing = loud.map { it.crossingRate }.average()
        return crossing >= 0.18 && low < 0.45
    }

    fun classifyWindow(window: List<SoundFeature>): String {
        if (window.size < 20) return "不明"
        val floor = percentileDb(window, 0.20f)
        val range = window.maxOf { it.db } - window.minOf { it.db }
        if (range < 5 && floor > -50f && window.last().offsetMs - window.first().offsetMs >= 5000) return "環境音候補"
        val threshold = maxOf(floor + 7f, -50f)
        val loud = window.filter { it.db > threshold }
        if (loud.isEmpty()) return "不明"
        val starts = mutableListOf<Long>()
        var lastPeak = -10_000L
        var wasLoud = false
        window.forEach {
            val high = it.db > threshold
            if (high && !wasLoud && it.offsetMs - lastPeak >= 1000) { starts.add(it.offsetMs); lastPeak = it.offsetMs }
            wasLoud = high
        }
        val intervals = starts.zipWithNext { a, b -> b - a }
        val rhythmic = intervals.size >= 2 && intervals.count { it in 1500L..8000L } >= intervals.size * 0.7
        val low = loud.map { it.lowRatio }.average()
        val crossing = loud.map { it.crossingRate }.average()
        if (rhythmic && low >= 0.25 && crossing <= 0.25) return "いびき候補"
        return "不明"
    }
}
