package com.pokepino.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.sqrt

class VariantRecognizer(private val context:Context){
    data class Candidate(val recordIds:List<String>,val score:Float)
    data class Result(val candidates:List<Candidate>,val accepted:Boolean)

    fun recognize(bitmap:Bitmap,vars:List<Figure>):Result{
        val q=feature(bitmap)
        val groups=vars.groupBy{it.visualGroupId}
        val scored=groups.mapNotNull{(_,rs)->
            val refs=rs.flatMap{it.refs}.filter{it.startsWith("http")}.distinct().take(3)
            if(refs.isEmpty())return@mapNotNull null
            val sims=refs.mapNotNull{u->
                runCatching{
                    val f=cache(u)
                    val b=BitmapFactory.decodeFile(f.absolutePath)?:return@runCatching null
                    val s=cos(q,feature(b))
                    b.recycle()
                    s
                }.getOrNull()
            }
            if(sims.isEmpty())null else Candidate(rs.map{it.id},sims.maxOrNull()?:0f)
        }.sortedByDescending{it.score}.take(5)

        val a=scored.firstOrNull()
        val b=scored.getOrNull(1)
        val ok=a!=null&&a.score>=.90f&&(b==null||a.score-b.score>=.08f)
        return Result(scored,ok)
    }

    private fun cache(url:String):File{
        val d=File(context.filesDir,"refs").apply{mkdirs()}
        val f=File(d,sha(url))
        if(f.exists()&&f.length()>512){
            if(BitmapFactory.decodeFile(f.absolutePath)!=null)return f
            f.delete()
        }

        val tmp=File(d,f.name+".part")
        if(tmp.exists())tmp.delete()

        val c=(URL(url).openConnection() as HttpURLConnection).apply{
            connectTimeout=10000
            readTimeout=20000
            instanceFollowRedirects=true
            setRequestProperty("User-Agent","Pokepino/0.9")
        }
        try{
            c.connect()
            if(c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            c.inputStream.use{i->tmp.outputStream().use{o->i.copyTo(o)}}
            if(tmp.length()<=512 || BitmapFactory.decodeFile(tmp.absolutePath)==null){
                tmp.delete()
                error("参照画像を取得できません")
            }
            if(!tmp.renameTo(f)){
                tmp.copyTo(f,true)
                tmp.delete()
            }
            return f
        }catch(e:Exception){
            tmp.delete()
            throw e
        }finally{
            c.disconnect()
        }
    }

    private fun feature(src:Bitmap):FloatArray{
        val b=Bitmap.createScaledBitmap(src,32,32,true)
        val v=FloatArray(3*64)
        var p=0
        for(cy in 0..7)for(cx in 0..7){
            var r=0f;var g=0f;var bl=0f
            for(y in cy*4 until cy*4+4)for(x in cx*4 until cx*4+4){
                val z=b.getPixel(x,y)
                r+=Color.red(z);g+=Color.green(z);bl+=Color.blue(z)
            }
            v[p++]=r/(16*255f);v[p++]=g/(16*255f);v[p++]=bl/(16*255f)
        }
        if(b!==src)b.recycle()
        return v
    }

    private fun cos(a:FloatArray,b:FloatArray):Float{
        var d=0f;var aa=0f;var bb=0f
        for(i in a.indices){d+=a[i]*b[i];aa+=a[i]*a[i];bb+=b[i]*b[i]}
        return if(aa==0f||bb==0f)0f else d/sqrt(aa*bb)
    }

    private fun sha(s:String)=MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray()).joinToString(""){"%02x".format(it)}+".img"
}
