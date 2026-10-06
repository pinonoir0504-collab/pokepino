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
    fun add(value: Int) { low += alpha * (value - low); energy += value.toDouble() * value; lowEnergy += low * low; if (count > 0 && (value >= 0) != (previous >= 0)) crossings++; previous = value; count++ }
    fun finish(offset: Long, db: Float): SoundFeature {
        val result = SoundFeature(offset, db, if (energy > 0) (lowEnergy / energy).toFloat().coerceIn(0f, 1f) else 0f, if (count > 1) crossings.toFloat() / (count - 1) else 0f)
        energy = 0.0; lowEnergy = 0.0; crossings = 0; count = 0
        return result
    }
}

object SoundCandidates {
    fun refine(existing: List<SoundEvent>, features: List<SoundFeature>, durationMs: Long): List<SoundEvent> {
        if (features.isEmpty() || durationMs <= 0L) return existing
        val ordered = if (features.zipWithNext().all { (a,b) -> a.offsetMs <= b.offsetMs }) features else features.sortedBy { it.offsetMs }
        return preserveManualLabels(classify(detectAdaptive(ordered, durationMs), ordered), existing, durationMs)
    }

    fun detectAdaptive(features: List<SoundFeature>, durationMs: Long): List<SoundEvent> {
        if (features.size < 3 || durationMs <= 0L) return emptyList()
        val ordered = if (features.zipWithNext().all { (a,b) -> a.offsetMs <= b.offsetMs }) features else features.sortedBy { it.offsetMs }
        val floor = percentileDb(ordered, .20f)
        // Slightly more sensitive opening recovers quiet breathing/snore pulses; spectral shape still gates classification.
        val openThreshold = maxOf(floor + 5f, -52f)
        val keepThreshold = maxOf(floor + 2.5f, -55f)
        val result = mutableListOf<SoundEvent>(); var first:Long?=null; var last:Long?=null; var peak=-60f
        fun close(){ val s=first?:return; val e=last?:s; val a=(s-300).coerceAtLeast(0); val b=(e+550).coerceAtMost(durationMs); if(b>a){ val ev=SoundEvent(a,b,peak); val p=result.lastOrNull(); if(p!=null && ev.startMs<=p.endMs+100) result[result.lastIndex]=p.copy(endMs=maxOf(p.endMs,ev.endMs),peakDb=maxOf(p.peakDb,ev.peakDb)) else result+=ev }; first=null;last=null;peak=-60f }
        ordered.forEach { f -> if(f.offsetMs !in 0..durationMs || !f.db.isFinite()) return@forEach; if(f.db>=openThreshold || (first!=null && f.db>=keepThreshold)){ if(first==null) first=f.offsetMs; last=f.offsetMs; peak=maxOf(peak,f.db) } else if(last!=null && f.offsetMs-last!!>=600) close() }; close(); return result
    }

    fun classify(events: List<SoundEvent>, features: List<SoundFeature>) = events.map { event ->
        val local=window(features,event.startMs,event.endMs); val neighbour=window(features,(event.startMs-12000).coerceAtLeast(0),event.endMs+12000)
        val floor=percentileDb(if(neighbour.size>=20) neighbour else local,.20f); val audible=local.filter{it.db>maxOf(floor+5f,-52f)}
        val candidate=when {
            isImpact(local)->"物音候補"
            isCough(local)->"咳候補"
            isSleepTalk(local)->"寝言候補"
            isBark(local)->"犬の鳴き声候補"
            isVoice(local)->"寝言候補"
            isSteadyEnvironment(local) && !looksLikeSnore(local,neighbour)->"環境音候補"
            audible.size>=3 && looksLikeSnore(local,neighbour)->"いびき候補"
            else->"不明"
        }; event.copy(candidate=candidate)
    }

    private fun looksLikeSnore(local:List<SoundFeature>, context:List<SoundFeature>):Boolean {
        if(local.size<3) return false
        val floor=percentileDb(if(context.size>=20) context else local,.20f); val loud=local.filter{it.db>maxOf(floor+5f,-52f)}; if(loud.size<3)return false
        val low=loud.map{it.lowRatio}.average(); val z=loud.map{it.crossingRate}.average(); if(low<.24 || z>.27)return false
        if(classifyWindow(context)=="いびき候補") return true
        // Permit isolated quiet snores when their low-frequency shape is especially strong.
        return low>=.34 && z<=.20 && (local.maxOf{it.db}-floor)>=7f
    }

    private fun isSteadyEnvironment(w:List<SoundFeature>):Boolean { if(w.size<20)return false; val span=w.last().offsetMs-w.first().offsetMs; val range=w.maxOf{it.db}-w.minOf{it.db}; val lowSpread=w.maxOf{it.lowRatio}-w.minOf{it.lowRatio}; return span>=5000 && range<5 && lowSpread<.12 }
    private data class Burst(val start:Long,val end:Long,val peak:Float){val duration get()=(end-start).coerceAtLeast(50)}
    private fun bursts(w:List<SoundFeature>):Pair<Float,List<Burst>>{ if(w.size<3)return -60f to emptyList(); val floor=percentileDb(w,.20f); val t=maxOf(floor+9,-48f); val r=mutableListOf<Burst>();var s:Long?=null;var e=0L;var p=-60f;w.forEach{f->if(f.db>=t){if(s==null)s=f.offsetMs;e=f.offsetMs+50;p=maxOf(p,f.db)}else if(s!=null){r+=Burst(s!!,e,p);s=null;p=-60f}};if(s!=null)r+=Burst(s!!,e,p);return floor to r }
    private fun loudFor(w:List<SoundFeature>,bs:List<Burst>)=w.filter{f->bs.any{f.offsetMs in it.start..it.end}}
    private fun isImpact(w:List<SoundFeature>):Boolean{val(f,b)=bursts(w);return b.size==1&&b[0].duration<=450&&b[0].peak-f>=18}
    private fun isCough(w:List<SoundFeature>):Boolean{val(_,b)=bursts(w);if(b.isEmpty()||b.size>2||b.any{it.duration !in 200..1400})return false;val l=loudFor(w,b);return l.size>=3&&l.map{it.crossingRate}.average()>=.16&&l.map{it.lowRatio}.average()<.55}
    private fun isSleepTalk(w:List<SoundFeature>):Boolean{val(_,b)=bursts(w);if(b.isEmpty()||b.size>5||b.none{it.duration>=1200})return false;val l=loudFor(w,b);return l.size>=8&&l.map{it.crossingRate}.average()>=.17&&l.map{it.lowRatio}.average()<.48}
    private fun isBark(w:List<SoundFeature>):Boolean{val(_,b)=bursts(w);if(b.size !in 2..7||b.count{it.duration<=900}<2)return false;val gaps=b.map{it.start}.zipWithNext{a,c->c-a};val l=loudFor(w,b);return gaps.any{it in 250..1800}&&l.size>=4&&l.map{it.crossingRate}.average()>=.20&&l.map{it.lowRatio}.average()<.38}
    private fun isVoice(w:List<SoundFeature>):Boolean{val(_,b)=bursts(w);if(b.isEmpty()||b.size>7)return false;val l=loudFor(w,b);return l.size>=6&&b.any{it.duration>=700}&&l.map{it.crossingRate}.average()>=.18&&l.map{it.lowRatio}.average()<.45}

    fun classifyWindow(w:List<SoundFeature>):String{if(w.size<20)return "不明";if(isSteadyEnvironment(w))return "環境音候補";val floor=percentileDb(w,.20f);val t=maxOf(floor+6f,-52f);val loud=w.filter{it.db>t};if(loud.isEmpty())return "不明";val starts=mutableListOf<Long>();var last=-10000L;var was=false;w.forEach{val hi=it.db>t;if(hi&&!was&&it.offsetMs-last>=900){starts+=it.offsetMs;last=it.offsetMs};was=hi};val ints=starts.zipWithNext{a,b->b-a};val rhythmic=ints.size>=2&&ints.count{it in 1400..8500}>=ints.size*.65;val low=loud.map{it.lowRatio}.average();val z=loud.map{it.crossingRate}.average();return if(rhythmic&&low>=.24&&z<=.27)"いびき候補" else "不明"}

    private fun preserveManualLabels(refined:List<SoundEvent>,existing:List<SoundEvent>,durationMs:Long):List<SoundEvent>{val manual=existing.withIndex().filter{it.value.label.isNotBlank()};if(manual.isEmpty())return refined;val used=mutableSetOf<Int>();val out=refined.map{e->val center=(e.startMs+e.endMs)/2;val m=manual.filterNot{it.index in used}.map{x->val o=x.value;val overlap=(minOf(e.endMs,o.endMs)-maxOf(e.startMs,o.startMs)).coerceAtLeast(0);val oc=(o.startMs+o.endMs)/2;x to if(overlap>0)10000000+overlap else (3000-abs(center-oc)).coerceAtLeast(0)}.maxByOrNull{it.second}?.takeIf{it.second>0};if(m!=null){used+=m.first.index;e.copy(label=m.first.value.label)}else e}.toMutableList();manual.filterNot{it.index in used}.forEach{x->val o=x.value;val s=o.startMs.coerceIn(0,durationMs);val e=o.endMs.coerceIn(s,durationMs);if(e>s)out+=o.copy(startMs=s,endMs=e)};return out.sortedBy{it.startMs}}
    private fun window(f:List<SoundFeature>,s:Long,e:Long):List<SoundFeature>{if(f.isEmpty())return emptyList();fun bound(t:Long,inc:Boolean):Int{var l=0;var h=f.size;while(l<h){val m=(l+h) ushr 1;if(f[m].offsetMs<t||(inc&&f[m].offsetMs==t))l=m+1 else h=m};return l};return f.subList(bound(s,false),bound(e,true))}
    private fun percentileDb(w:List<SoundFeature>,p:Float):Float{val v=w.asSequence().map{it.db}.filter{it.isFinite()}.sorted().toList();if(v.isEmpty())return -60f;return v[((v.lastIndex)*p.coerceIn(0f,1f)).toInt().coerceIn(0,v.lastIndex)]}
}
