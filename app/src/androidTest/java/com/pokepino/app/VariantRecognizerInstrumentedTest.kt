package com.pokepino.app

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class VariantRecognizerInstrumentedTest {
    private fun loadFigures():List<Figure>{
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val text=context.assets.open("catalog_v2.json").bufferedReader().use{it.readText()}
        val a=JSONArray(text)
        return (0 until a.length()).mapNotNull{i->
            val r=a.optJSONArray(i)?:return@mapNotNull null
            val refs=r.optJSONArray(8)?.let{x->
                (0 until x.length()).mapNotNull{j->x.optString(j).takeIf(String::isNotBlank)}
            }?:emptyList()
            val meta=r.optJSONObject(9)
            Figure(
                id=r.optString(0),
                dex=r.optInt(1),
                pokemon=r.optString(2),
                variant=r.optString(3),
                series=r.optString(4),
                year=r.optString(5),
                visualGroupId=r.optString(6,r.optString(0)),
                status=r.optString(7,"species_only"),
                refs=refs,
                kidsNo=meta?.optInt("kidsNo",0)?:0,
                feature=meta?.optString("feature","")?:"",
                source=meta?.optString("source","")?:""
            )
        }
    }

    @Test
    fun bundledVisualSignaturesMatchLiveReferenceImages(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val figures=loadFigures().filter{it.dex>0&&it.feature.isNotBlank()&&it.refs.firstOrNull()?.startsWith("http")==true}
        assertTrue("reference catalog should be large",figures.size>=1000)

        val step=(figures.size/10).coerceAtLeast(1)
        val sample=(0 until figures.size step step).map{figures[it]}.take(10)
        val recognizer=VariantRecognizer(context)
        var tested=0
        var correct=0

        sample.forEach{target->
            val conn=(URL(target.refs.first()).openConnection() as HttpURLConnection).apply{
                connectTimeout=15000
                readTimeout=30000
                instanceFollowRedirects=true
                setRequestProperty("User-Agent","PokepinoRuntimeTest/1.0")
            }
            try{
                conn.connect()
                if(conn.responseCode in 200..299){
                    val bitmap=conn.inputStream.use{BitmapFactory.decodeStream(it)}
                    if(bitmap!=null){
                        val result=recognizer.recognize(bitmap,figures)
                        val first=result.candidates.firstOrNull()
                        tested++
                        if(first?.recordIds?.contains(target.id)==true)correct++
                        bitmap.recycle()
                    }
                }
            }finally{
                conn.disconnect()
            }
        }

        assertTrue("not enough reference images could be tested: $tested",tested>=8)
        assertTrue("Kotlin matcher exact-reference accuracy too low: $correct/$tested",correct>=tested-1)
    }
}
