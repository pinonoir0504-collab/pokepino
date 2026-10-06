package com.pokepino.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import java.io.Closeable
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

class PokemonRecognizer(private val context: Context):Closeable{
    data class Candidate(val dex:Int,val score:Float)
    data class Result(val candidates:List<Candidate>,val accepted:Boolean,val engine:String, val allCandidates:List<Candidate> = candidates)
    private val env by lazy{OrtEnvironment.getEnvironment()}
    private var session:OrtSession?=null

    @Synchronized fun recognize(bitmap:Bitmap):Result{
        val model=ensure(MODEL,MODEL_URL,22_000_000)
        val labels=labels()
        val s=session ?: env.createSession(model.absolutePath,OrtSession.SessionOptions()).also{session=it}
        val input=preprocess(bitmap)
        val name=s.inputNames.first()
        OnnxTensor.createTensor(env,input,longArrayOf(1,3,224,224)).use{t->
            s.run(mapOf(name to t)).use{out->
                val raw=out[0].value
                val logits=when(raw){
                    is Array<*> -> raw[0] as FloatArray
                    is FloatArray -> raw
                    else -> error("AI出力形式が不明です")
                }
                val ranked=logits.indices.sortedByDescending{logits[it]}
                val max=ranked.maxOf{logits[it]}
                val denom=logits.sumOf{exp((it-max).toDouble())}.toFloat()
                val allCandidates=ranked.map{
                    Candidate(
                        labels.getOrElse(it){it+1},
                        exp((logits[it]-max).toDouble()).toFloat()/denom
                    )
                }
                val cs=allCandidates.take(5)
                val margin=if(cs.size>1)cs[0].score-cs[1].score else 1f
                return Result(cs,cs.firstOrNull()?.score?.let{it>=0.35f&&margin>=0.10f}==true,"全1025種AI",allCandidates)
            }
        }
    }

    private fun labels():List<Int>{
        val f=ensure(LABELS,LABELS_URL,500)
        val a=JSONArray(f.readText())
        return (0 until a.length()).map{i->
            a.getJSONObject(i).opt("id").toString().toIntOrNull()?:i+1
        }
    }

    private fun preprocess(src:Bitmap):java.nio.FloatBuffer{
        val b=FigureCrop.classifierSquare(src)
        val bb=ByteBuffer.allocateDirect(3*224*224*4).order(ByteOrder.nativeOrder())
        val fb=bb.asFloatBuffer()
        val px=IntArray(224*224)
        b.getPixels(px,0,224,0,0,224,224)
        val mean=floatArrayOf(.485f,.456f,.406f)
        val std=floatArrayOf(.229f,.224f,.225f)
        for(c in 0..2)for(p in px){
            val v=when(c){0->(p shr 16)and 255;1->(p shr 8)and 255;else->p and 255}
            fb.put((v/255f-mean[c])/std[c])
        }
        fb.rewind()
        if(b!==src)b.recycle()
        return fb
    }

    private fun ensure(name:String,url:String,min:Long):File{
        val dir=File(context.filesDir,"models").apply{mkdirs()}
        val f=File(dir,name)
        if(f.exists()&&f.length()>=min)return f
        val tmp=File(dir,"$name.part")
        // CI bundles these fixed-revision assets so recognition works offline on first launch.
        runCatching {
            context.assets.open(name).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (tmp.length() < min) error("同梱AIデータが不足しています")
            tmp.copyTo(f, true); tmp.delete()
        }.onSuccess { return f }

        if(tmp.exists())tmp.delete()
        val c=(URL(url).openConnection() as HttpURLConnection).apply{
            connectTimeout=20000
            readTimeout=120000
            instanceFollowRedirects=true
            setRequestProperty("User-Agent","Pokepino/0.9")
        }
        try{
            c.connect()
            if(c.responseCode !in 200..299) error("AIデータ取得失敗 HTTP ${c.responseCode}")
            c.inputStream.use{i->tmp.outputStream().use{o->i.copyTo(o)}}
            if(tmp.length()<min)error("AIデータ取得失敗")
            tmp.copyTo(f,true)
            tmp.delete()
            return f
        }finally{c.disconnect()}
    }

    override fun close(){session?.close();session=null}

    companion object{
        private const val REV="68c055b397ad1a9bc61bbba39be438c4a2ac3f28"
        private const val BASE="https://huggingface.co/BiernyVR/pokemon-classifier-mobilenetv3/resolve/$REV"
        private const val MODEL="pokemon_1025.onnx"
        private const val LABELS="pokemon_labels.json"
        private const val MODEL_URL="$BASE/pokemon_classifier.onnx?download=true"
        private const val LABELS_URL="$BASE/pokemon_labels.json?download=true"
    }
}
