package com.pokepino.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import org.json.JSONArray
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

class EmbeddingRecognizer(private val context: Context) : Closeable {
    data class SpeciesCandidate(val dex: Int, val score: Float)
    data class Result(
        val speciesCandidates: List<SpeciesCandidate>,
        val acceptedSpecies: Boolean,
        val veryStrongSpecies: Boolean,
        internal val query: FloatArray,
        internal val allSpeciesCandidates: List<SpeciesCandidate> = speciesCandidates
    )
    data class VariantCandidate(
        val recordIds: List<String>,
        val visualGroupId: String,
        val score: Float
    )
    data class VariantResult(
        val candidates: List<VariantCandidate>,
        val accepted: Boolean,
        val veryStrong: Boolean
    )

    private data class Ref(
        val id: String,
        val dex: Int,
        val group: String,
        val vector: ByteArray,
        val norm: Float,
        val penalty: Float
    )

    private val env by lazy { OrtEnvironment.getEnvironment().apply { setTelemetry(false) } }
    private var session: OrtSession? = null
    private val refs by lazy { loadRefs() }
    private val photoRefs by lazy { loadRefs(PHOTO_REFS_ASSET, 1, speciesOnly = true) }

    fun isAvailable(): Boolean = runCatching {
        context.assets.open(MODEL_ASSET).close()
        context.assets.open(REFS_ASSET).close()
        true
    }.getOrDefault(false)

    @Synchronized
    fun recognize(bitmap: Bitmap): Result {
        if (!isAvailable()) error("高精度認識データが未生成です")
        val q = embed(bitmap)
        val bestByDex = HashMap<Int, Float>()
        for (r in refs) {
            if (r.dex <= 0) continue
            val s = similarity(q, r)
            val old = bestByDex[r.dex]
            if (old == null || s > old) bestByDex[r.dex] = s
        }
        val baseRanked = bestByDex.entries
            .map { SpeciesCandidate(it.key, it.value) }
            .sortedByDescending { it.score }

        val photoCandidates = photoRefs.map { SpeciesPhotoRanking.Candidate(it.dex, similarity(q, it)) }
        val allRanked = SpeciesPhotoRanking.rank(
            baseRanked.map { SpeciesPhotoRanking.Candidate(it.dex, it.score) }, photoCandidates
        ).map { SpeciesCandidate(it.dex, it.score) }

        val ranked = allRanked.take(7)

        // Prototype-only evidence cannot increase automatic registration confidence.
        // Variant matching below continues to use only the original variant references.
        val first = baseRanked.firstOrNull()
        val second = baseRanked.getOrNull(1)
        val margin = if (first != null && second != null) first.score - second.score else 1f
        val preservesTrust = SpeciesPhotoRanking.preservesTrustedSpecies(ranked.firstOrNull()?.dex, first?.dex)

        // Conservative auto-accept thresholds. Ranking remains useful below these.
        val accepted = preservesTrust && first != null && first.score >= 0.66f && margin >= 0.035f
        val veryStrong = preservesTrust && first != null && first.score >= 0.76f && margin >= 0.060f
        return Result(ranked, accepted, veryStrong, q, allRanked)
    }

    fun rankVariants(result: Result, dex: Int, figures: List<Figure>): VariantResult {
        if (dex <= 0) return VariantResult(emptyList(), false, false)
        val idsByGroup = figures
            .asSequence()
            .filter { it.dex == dex && it.visualGroupId.isNotBlank() }
            .groupBy { it.visualGroupId }
            .mapValues { (_, rows) -> rows.map { it.id }.distinct() }

        val bestByGroup = HashMap<String, Float>()
        for (r in refs) {
            if (r.dex != dex || r.group.isBlank()) continue
            val s = similarity(result.query, r)
            val old = bestByGroup[r.group]
            if (old == null || s > old) bestByGroup[r.group] = s
        }

        val ranked = bestByGroup.entries
            .mapNotNull { (group, score) ->
                val ids = idsByGroup[group].orEmpty()
                if (ids.isEmpty()) null else VariantCandidate(ids, group, score)
            }
            .sortedByDescending { it.score }
            .take(7)

        val first = ranked.firstOrNull()
        val second = ranked.getOrNull(1)
        val margin = if (first != null && second != null) first.score - second.score else 1f
        val accepted = first != null && first.score >= 0.70f && margin >= 0.035f
        val veryStrong = first != null && first.score >= 0.80f && margin >= 0.060f
        return VariantResult(ranked, accepted, veryStrong)
    }

    private fun loadRefs(assetName: String = REFS_ASSET, minimum: Int = 1000, speciesOnly: Boolean = false): List<Ref> {
        val text = context.assets.open(assetName).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val rows = JSONArray(text)
        val out = ArrayList<Ref>(rows.length())
        repeat(rows.length()) { i ->
            val row = rows.optJSONArray(i) ?: return@repeat
            val encoded = row.optString(3)
            if (encoded.isBlank()) return@repeat
            val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: return@repeat
            if (bytes.size != EMBEDDING_SIZE) return@repeat
            var ss = 0f
            for (b in bytes) {
                val v = b.toInt() / 127f
                ss += v * v
            }
            val n = sqrt(ss).coerceAtLeast(1e-6f)
            val prototype=row.optString(4,"base")
            val penalty=if(speciesOnly) 0.05f else when(prototype){
                "mirror" -> 0.012f
                "dark","bright" -> 0.008f
                else -> 0f
            }
            out += Ref(
                id = row.optString(0),
                dex = row.optInt(1),
                group = if (speciesOnly) "" else row.optString(2),
                vector = bytes,
                norm = n,
                penalty = penalty
            )
        }
        if (out.size < minimum) error("高精度認識DBが不足しています: $assetName (${out.size})")
        return out
    }

    private fun embed(src: Bitmap): FloatArray {
        val model = ensureAssetModel()
        val s = session ?: env.createSession(model.absolutePath, OrtSession.SessionOptions()).also { session = it }
        val input = preprocess(src)
        val name = s.inputNames.first()
        OnnxTensor.createTensor(env, input, longArrayOf(1, 3, 224, 224)).use { tensor ->
            s.run(mapOf(name to tensor)).use { output ->
                val raw = output[0].value
                val vec = when (raw) {
                    is Array<*> -> raw.firstOrNull() as? FloatArray
                    is FloatArray -> raw
                    else -> null
                } ?: error("高精度AIの出力形式が不明です")
                if (vec.size != EMBEDDING_SIZE) error("高精度AIの特徴量サイズが不正です: ${vec.size}")
                val copy = vec.copyOf()
                var ss = 0f
                for (v in copy) ss += v * v
                val n = sqrt(ss).coerceAtLeast(1e-6f)
                for (i in copy.indices) copy[i] /= n
                return copy
            }
        }
    }

    private fun preprocess(src: Bitmap): java.nio.FloatBuffer {
        val crop = foregroundCrop(src)
        val shortSide = minOf(crop.width, crop.height).coerceAtLeast(1)
        val scale = 256f / shortSide
        val rw = max(224, (crop.width * scale).roundToInt())
        val rh = max(224, (crop.height * scale).roundToInt())
        val resized = Bitmap.createScaledBitmap(crop, rw, rh, true)
        val x = ((rw - 224) / 2).coerceAtLeast(0)
        val y = ((rh - 224) / 2).coerceAtLeast(0)
        val square = Bitmap.createBitmap(resized, x, y, 224, 224)

        val px = IntArray(224 * 224)
        square.getPixels(px, 0, 224, 0, 0, 224, 224)
        val bb = ByteBuffer.allocateDirect(3 * 224 * 224 * 4).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        val mean = floatArrayOf(.485f, .456f, .406f)
        val std = floatArrayOf(.229f, .224f, .225f)
        for (c in 0..2) {
            for (p in px) {
                val v = when (c) {
                    0 -> (p shr 16) and 255
                    1 -> (p shr 8) and 255
                    else -> p and 255
                }
                fb.put((v / 255f - mean[c]) / std[c])
            }
        }
        fb.rewind()

        square.recycle()
        if (resized !== crop) resized.recycle()
        if (crop !== src) crop.recycle()
        return fb
    }

    private fun foregroundCrop(src: Bitmap): Bitmap {
        val size = 128
        val sample = Bitmap.createScaledBitmap(src, size, size, true)
        val borderR = ArrayList<Int>(size * 4)
        val borderG = ArrayList<Int>(size * 4)
        val borderB = ArrayList<Int>(size * 4)
        fun add(z: Int) {
            borderR += Color.red(z)
            borderG += Color.green(z)
            borderB += Color.blue(z)
        }
        for (x in 0 until size) {
            add(sample.getPixel(x, 0))
            add(sample.getPixel(x, size - 1))
        }
        for (y in 1 until size - 1) {
            add(sample.getPixel(0, y))
            add(sample.getPixel(size - 1, y))
        }
        fun median(a: ArrayList<Int>): Float {
            val v = a.sorted()
            return v[v.size / 2].toFloat()
        }
        val br = median(borderR)
        val bg = median(borderG)
        val bb = median(borderB)

        val distances = FloatArray(size * size)
        var k = 0
        for (y in 0 until size) for (x in 0 until size) {
            val z = sample.getPixel(x, y)
            val dr = Color.red(z) - br
            val dg = Color.green(z) - bg
            val db = Color.blue(z) - bb
            distances[k++] = sqrt(dr * dr + dg * dg + db * db)
        }
        val sorted = distances.clone().apply { sort() }
        val threshold = max(28f, sorted[(sorted.size * .58f).toInt().coerceIn(0, sorted.lastIndex)])

        var x0 = size
        var y0 = size
        var x1 = -1
        var y1 = -1
        var count = 0
        k = 0
        for (y in 0 until size) for (x in 0 until size) {
            if (distances[k++] > threshold) {
                count++
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
            }
        }
        sample.recycle()

        if (count < 220 || x1 <= x0 || y1 <= y0) return src
        val pad = 4
        x0 = (x0 - pad).coerceAtLeast(0)
        y0 = (y0 - pad).coerceAtLeast(0)
        x1 = (x1 + pad).coerceAtMost(size - 1)
        y1 = (y1 + pad).coerceAtMost(size - 1)

        val sx0 = (x0.toFloat() / size * src.width).toInt().coerceIn(0, src.width - 1)
        val sy0 = (y0.toFloat() / size * src.height).toInt().coerceIn(0, src.height - 1)
        val sx1 = ((x1 + 1).toFloat() / size * src.width).toInt().coerceIn(sx0 + 1, src.width)
        val sy1 = ((y1 + 1).toFloat() / size * src.height).toInt().coerceIn(sy0 + 1, src.height)
        val area = (sx1 - sx0).toLong() * (sy1 - sy0).toLong()
        if (area < src.width.toLong() * src.height.toLong() / 25L) return src
        return Bitmap.createBitmap(src, sx0, sy0, sx1 - sx0, sy1 - sy0)
    }

    private fun similarity(q: FloatArray, r: Ref): Float {
        if (q.size != r.vector.size) return -1f
        var dot = 0f
        for (i in q.indices) dot += q[i] * (r.vector[i].toInt() / 127f)
        return (dot / r.norm - r.penalty).coerceIn(-1f, 1f)
    }

    private fun ensureAssetModel(): File {
        val dir = File(context.filesDir, "models").apply { mkdirs() }
        val f = File(dir, MODEL_ASSET)
        if (f.exists() && f.length() > 2_000_000L) return f
        val tmp = File(dir, "$MODEL_ASSET.part")
        if (tmp.exists()) tmp.delete()
        context.assets.open(MODEL_ASSET).use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        if (tmp.length() <= 2_000_000L) {
            tmp.delete()
            error("高精度AIモデルが壊れています")
        }
        if (!tmp.renameTo(f)) {
            tmp.copyTo(f, true)
            tmp.delete()
        }
        return f
    }

    override fun close() {
        session?.close()
        session = null
    }

    companion object {
        private const val MODEL_ASSET = "mobilenet_v3_small_features.onnx"
        private const val REFS_ASSET = "embedding_catalog_v3.json"
        private const val PHOTO_REFS_ASSET = "species_photo_refs_v1.json"
        private const val EMBEDDING_SIZE = 576
    }
}
