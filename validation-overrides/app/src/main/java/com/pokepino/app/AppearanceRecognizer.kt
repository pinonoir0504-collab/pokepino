package com.pokepino.app

import android.content.Context
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.exp
import kotlin.math.ln

/** Development-selected candidate classifier. Scores are rankings, not calibrated certainty. */
internal class AppearanceRecognizer(context: Context) {
    private val model = JSONObject(context.assets.open("appearance_model.json").bufferedReader().use { it.readText() })
    private val kind = model.getString("kind")
    val needsAppearance = model.getInt("feature_count") > 14 && kind != "baseline"
    val needsPatches = needsAppearance
    private val candidateTopK = model.optInt("candidate_top_k", 7).coerceIn(1, supportedSpeciesUpperBound)
    private val descriptors = listOf("global", "spatial", "silhouette", "patch_global", "patch_spatial")
    private val referenceSpecies = if (needsAppearance) model.getJSONArray("reference_species").let { a -> IntArray(a.length()) { a.getInt(it) } } else intArrayOf()
    private val gallery = if (needsAppearance) descriptors.associateWith { key ->
        val file = model.getJSONObject("gallery").getJSONObject(key)
        val bytes = context.assets.open(file.getString("path")).use { it.readBytes() }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        require(sha == file.getString("sha256"))
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val values = FloatArray(buffer.remaining()).also { buffer.get(it) }
        require(values.size == referenceSpecies.size * file.getInt("dimensions"))
        require(values.all { it.isFinite() })
        values
    } else emptyMap()
    private fun values(key: String) = model.getJSONArray(key).let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
    private val mean = if (kind == "logistic" || kind == "pairwise") values("mean") else doubleArrayOf()
    private val scale = if (kind == "logistic" || kind == "pairwise") values("scale") else doubleArrayOf()
    private val coef = if (kind == "logistic" || kind == "pairwise") values("coef") else doubleArrayOf()
    private fun linearScore(x: DoubleArray): Double {
        var z = model.getDouble("intercept")
        for (i in x.indices) z += (x[i] - mean[i]) / scale[i] * coef[i]
        return z
    }
    private fun probability(x: DoubleArray): Double {
        var z = model.getDouble("intercept")
        if (kind == "logistic") {
            z = linearScore(x)
        } else {
            val trees = model.getJSONArray("trees")
            for (i in 0 until trees.length()) {
                val t = trees.getJSONObject(i)
                var node = 0
                while (t.getJSONArray("left").getInt(node) >= 0) {
                    val feature = t.getJSONArray("feature").getInt(node)
                    node = t.getJSONArray(if (x[feature].toFloat().toDouble() <= t.getJSONArray("threshold").getDouble(node)) "left" else "right").getInt(node)
                }
                z += model.getDouble("learning_rate") * t.getJSONArray("value").getDouble(node)
            }
        }
        return 1.0 / (1.0 + exp(-z.coerceIn(-700.0, 700.0)))
    }
    fun rank(base: Map<Int, Double>, current: Map<Int, Double>, prototype: Map<Int, Double>, supported: Set<Int>, appearance: Map<String, FloatArray>): List<ShapeFusion.Candidate> {
        fun order(scores: Map<Int, Double>) = supported.sortedWith(compareByDescending<Int> { scores[it] ?: 0.0 }.thenBy { it })
        val baseOrder = order(base)
        if (kind == "baseline" || (needsAppearance && !descriptors.all { appearance.containsKey(it) })) return baseOrder.take(7).map { ShapeFusion.Candidate(it, (base[it] ?: 0.0).toFloat()) }
        val currentOrder = order(current); val prototypeOrder = order(prototype)
        val currentRank = currentOrder.withIndex().associate { it.value to it.index }; val prototypeRank = prototypeOrder.withIndex().associate { it.value to it.index }
        val pool = (baseOrder.take(candidateTopK) + currentOrder.take(candidateTopK) + prototypeOrder.take(candidateTopK)).distinct().sorted()
        val first = baseOrder.first()
        val entropy = -base.values.filter { it > 0 }.sumOf { it * ln(it) } / ln(supported.size.toDouble())
        val similarities = descriptors.associateWith { key ->
            val query = appearance[key]
            val result = HashMap<Int, Double>()
            if (query != null) {
                val vector = gallery.getValue(key)
                val top = HashMap<Int, MutableList<Double>>()
                for (row in referenceSpecies.indices) {
                    val dex = referenceSpecies[row]
                    if (dex !in pool) continue
                    var dot = 0.0
                    for (j in query.indices) dot += query[j].toDouble() * vector[row * query.size + j]
                    val list = top.getOrPut(dex) { ArrayList() }; list.add(dot); list.sortDescending()
                    if (list.size > 3) list.removeAt(3)
                }
                top.forEach { (dex, scores) -> result[dex] = scores.average() }
            }
            result
        }
        if (kind == "simple") {
            val cap = model.getInt("cap"); val key = model.getString("descriptor"); val ids = baseOrder.take(cap); val color = similarities.getValue(key)
            val present = ids.filter { (color[it] ?: 0.0) > 0.0 }
            if (present.size < 2) return baseOrder.take(7).map { ShapeFusion.Candidate(it, (base[it] ?: 0.0).toFloat()) }
            val average = present.map { color.getValue(it) }.average()
            val logits = ids.associateWith { ln((base[it] ?: 0.0).coerceAtLeast(1e-12)) + model.getDouble("weight") * (if (it in present) color.getValue(it) - average else 0.0) / .05 }
            val maximum = logits.values.max(); val p = logits.mapValues { exp(it.value - maximum) }; val mass = ids.sumOf { base[it] ?: 0.0 }; val sum = p.values.sum()
            return (base + p.mapValues { it.value / sum * mass }).map { ShapeFusion.Candidate(it.key,it.value.toFloat()) }.sortedWith(compareByDescending<ShapeFusion.Candidate> { it.score }.thenBy { it.dex }).take(7)
        }
        val scores = pool.associateWith { dex ->
            val b = base[dex] ?: 0.0; val c = current[dex] ?: 0.0; val p = prototype[dex] ?: 0.0
            val x = arrayListOf(b, ln(b.coerceAtLeast(1e-12)), base[first] ?: 0.0, (base[first] ?: 0.0) - (base[baseOrder[1]] ?: 0.0), entropy, if (dex == first) 1.0 else 0.0, c, p, ln(c.coerceAtLeast(1e-12)), ln(p.coerceAtLeast(1e-12)), currentRank.getValue(dex).toDouble() / (supported.size - 1), prototypeRank.getValue(dex).toDouble() / (supported.size - 1), if (dex == currentOrder.first()) 1.0 else 0.0, if (dex == prototypeOrder.first()) 1.0 else 0.0)
            if (needsAppearance) for (key in descriptors) {
                val s = similarities.getValue(key); val own = s[dex] ?: 0.0; val top = s[first] ?: 0.0; val present = own > 0.0
                x.add(own); x.add(if (present && top > 0.0) own-top else 0.0); x.add(if (present) own - pool.maxOf { s[it] ?: 0.0 } else 0.0); x.add(if (present) 1.0 else 0.0)
            }
            if (kind == "pairwise") linearScore(x.toDoubleArray()) else probability(x.toDoubleArray())
        }
        if (kind == "pairwise") return PairwiseCandidateRanking.rank(scores)
        val sum = scores.values.sum().coerceAtLeast(1e-12)
        return scores.map { ShapeFusion.Candidate(it.key, (it.value / sum).toFloat()) }
            .sortedWith(compareByDescending<ShapeFusion.Candidate> { it.score }.thenBy { it.dex }).take(7)
    }

    private companion object { const val supportedSpeciesUpperBound = 1025 }
}
