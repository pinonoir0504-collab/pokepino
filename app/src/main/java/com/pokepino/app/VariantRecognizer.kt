package com.pokepino.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.sqrt

class VariantRecognizer(private val context:Context){
    data class Candidate(val recordIds:List<String>,val score:Float)
    data class Result(val candidates:List<Candidate>,val accepted:Boolean)

    private data class Signature(
        val blocks:FloatArray,
        val hist:FloatArray,
        val edge:ByteArray
    )

    fun recognize(bitmap:Bitmap,vars:List<Figure>):Result{
        val q=signature(bitmap)
        val groups=vars.groupBy{it.visualGroupId}
        val scored=groups.mapNotNull{(_,rs)->
            val local=rs.mapNotNull{decodeSignature(it.feature)}.map{similarity(q,it)}
            val sims=if(local.isNotEmpty()) local else {
                rs.flatMap{it.refs}.filter{it.startsWith("http")}.distinct().take(2).mapNotNull{u->
                    runCatching{
                        val f=cache(u)
                        val b=BitmapFactory.decodeFile(f.absolutePath)?:return@runCatching null
                        val s=similarity(q,signature(b))
                        b.recycle()
                        s
                    }.getOrNull()
                }
            }
            if(sims.isEmpty())null else Candidate(rs.map{it.id},sims.maxOrNull()?:0f)
        }.sortedByDescending{it.score}.take(7)

        val a=scored.firstOrNull()
        val b=scored.getOrNull(1)
        val margin=if(a!=null&&b!=null)a.score-b.score else 1f
        val ok=a!=null&&a.score>=.92f&&margin>=.045f
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
            setRequestProperty("User-Agent","Pokepino/1.0")
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

    private fun decodeSignature(encoded:String):Signature?{
        if(encoded.isBlank())return null
        return runCatching{
            val raw=Base64.decode(encoded,Base64.DEFAULT)
            if(raw.size<264)return@runCatching null
            val blocks=FloatArray(192){i->(raw[i].toInt() and 255)/255f}
            val hist=FloatArray(40){i->(raw[192+i].toInt() and 255)/255f}
            val edge=raw.copyOfRange(232,264)
            Signature(blocks,hist,edge)
        }.getOrNull()
    }

    private fun signature(src:Bitmap):Signature{
        val working=foregroundCrop(src)
        val b=Bitmap.createScaledBitmap(working,32,32,true)

        val blocks=FloatArray(192)
        var p=0
        for(cy in 0..7)for(cx in 0..7){
            var r=0f;var g=0f;var bl=0f
            for(y in cy*4 until cy*4+4)for(x in cx*4 until cx*4+4){
                val z=b.getPixel(x,y)
                r+=Color.red(z);g+=Color.green(z);bl+=Color.blue(z)
            }
            blocks[p++]=r/(16*255f)
            blocks[p++]=g/(16*255f)
            blocks[p++]=bl/(16*255f)
        }

        val histCounts=IntArray(40)
        val hsv=FloatArray(3)
        for(y in 0 until 32)for(x in 0 until 32){
            Color.colorToHSV(b.getPixel(x,y),hsv)
            val h=(hsv[0]/360f*255f).coerceIn(0f,255f)
            val s=(hsv[1]*255f).coerceIn(0f,255f)
            val v=(hsv[2]*255f).coerceIn(0f,255f)
            histCounts[(h/256f*24f).toInt().coerceIn(0,23)]++
            histCounts[24+(s/256f*8f).toInt().coerceIn(0,7)]++
            histCounts[32+(v/256f*8f).toInt().coerceIn(0,7)]++
        }
        val histMax=histCounts.maxOrNull()?.coerceAtLeast(1)?:1
        val hist=FloatArray(40){histCounts[it].toFloat()/histMax}

        val g=Bitmap.createScaledBitmap(b,16,16,true)
        val gray=IntArray(256)
        for(y in 0 until 16)for(x in 0 until 16){
            val z=g.getPixel(x,y)
            gray[y*16+x]=(Color.red(z)*299+Color.green(z)*587+Color.blue(z)*114)/1000
        }
        val edge=ByteArray(32)
        for(y in 0 until 16)for(x in 0 until 16){
            val i=y*16+x
            val left=if(x==0)gray[i] else gray[i-1]
            val up=if(y==0)gray[i] else gray[i-16]
            val strength=kotlin.math.abs(gray[i]-left)+kotlin.math.abs(gray[i]-up)
            if(strength>38){
                val byteIndex=i/8
                val bit=7-(i%8)
                edge[byteIndex]=(edge[byteIndex].toInt() or (1 shl bit)).toByte()
            }
        }
        g.recycle()
        if(b!==working && b!==src)b.recycle()
        if(working!==src)working.recycle()
        return Signature(blocks,hist,edge)
    }

    private fun foregroundCrop(src:Bitmap):Bitmap{
        val s=Bitmap.createScaledBitmap(src,96,96,true)
        val borderR=ArrayList<Int>(384)
        val borderG=ArrayList<Int>(384)
        val borderB=ArrayList<Int>(384)
        fun add(z:Int){borderR+=Color.red(z);borderG+=Color.green(z);borderB+=Color.blue(z)}
        for(x in 0 until 96){add(s.getPixel(x,0));add(s.getPixel(x,95))}
        for(y in 1 until 95){add(s.getPixel(0,y));add(s.getPixel(95,y))}
        fun median(a:ArrayList<Int>):Float{
            val v=a.sorted()
            return v[v.size/2].toFloat()
        }
        val br=median(borderR);val bg=median(borderG);val bb=median(borderB)
        val distances=FloatArray(96*96)
        var k=0
        for(y in 0 until 96)for(x in 0 until 96){
            val z=s.getPixel(x,y)
            val dr=Color.red(z)-br
            val dg=Color.green(z)-bg
            val db=Color.blue(z)-bb
            distances[k++]=sqrt(dr*dr+dg*dg+db*db)
        }
        val sorted=distances.clone().apply{sort()}
        val threshold=max(28f,sorted[(sorted.size*.58f).toInt().coerceIn(0,sorted.lastIndex)])
        var x0=96;var y0=96;var x1=-1;var y1=-1;var count=0
        k=0
        for(y in 0 until 96)for(x in 0 until 96){
            if(distances[k++]>threshold){
                count++
                if(x<x0)x0=x;if(x>x1)x1=x
                if(y<y0)y0=y;if(y>y1)y1=y
            }
        }
        if(count<140||x1<x0||y1<y0)return s
        x0=(x0-2).coerceAtLeast(0);y0=(y0-2).coerceAtLeast(0)
        x1=(x1+2).coerceAtMost(95);y1=(y1+2).coerceAtMost(95)
        val crop=Bitmap.createBitmap(s,x0,y0,x1-x0+1,y1-y0+1)
        s.recycle()
        return crop
    }

    private fun similarity(a:Signature,b:Signature):Float{
        val color=cos(a.blocks,b.blocks)
        val hist=cos(a.hist,b.hist)
        var equalBits=0
        for(i in a.edge.indices){
            val diff=(a.edge[i].toInt() xor b.edge[i].toInt()) and 255
            equalBits+=8-Integer.bitCount(diff)
        }
        val edge=equalBits/256f
        return (color*.55f+hist*.25f+edge*.20f).coerceIn(0f,1f)
    }

    private fun cos(a:FloatArray,b:FloatArray):Float{
        if(a.size!=b.size)return 0f
        var d=0f;var aa=0f;var bb=0f
        for(i in a.indices){d+=a[i]*b[i];aa+=a[i]*a[i];bb+=b[i]*b[i]}
        return if(aa==0f||bb==0f)0f else d/sqrt(aa*bb)
    }

    private fun sha(s:String)=MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray()).joinToString(""){"%02x".format(it)}+".img"
}
