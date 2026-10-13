package com.pokepino.app

import android.content.Context
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Each test is dispatched to a separate Android emulator VM, not 10 threads in one emulator. */
@RunWith(AndroidJUnit4::class)
class CollectionTenMachineTest {
    @get:Rule val rule = ActivityScenarioRule(CollectionActivity::class.java)

    private fun js(script: String): String {
        val latch = CountDownLatch(1)
        val value = AtomicReference("null")
        rule.scenario.onActivity { a ->
            val web = a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as WebView
            web.evaluateJavascript(script) { raw ->
                value.set(raw)
                latch.countDown()
            }
        }
        assertTrue("WebView evaluation timed out: $script", latch.await(15, TimeUnit.SECONDS))
        return value.get()
    }

    private fun ready() {
        var loaded = false
        repeat(45) {
            if (!loaded && js("typeof records!=='undefined' && records.length>1500 && document.querySelector('#screen')!==null") == "true") loaded = true
            if (!loaded) Thread.sleep(250)
        }
        assertTrue("Manual catalog did not initialize", loaded)
    }

    @Test fun t01_catalogLaunchAndManualNavigation() {
        ready()
        assertEquals("true", js("document.querySelectorAll('nav button').length===2 && !document.querySelector('#nav-scan')"))
        assertEquals("true", js("typeof openScanner==='undefined' && typeof chooseGallery==='undefined'"))
        assertEquals("true", js("(()=>{switchTab('owned');const ok=!!document.querySelector('#query');switchTab('settings');return ok&&!!document.querySelector('.backupBtn')})()"))
    }

    @Test fun t02_nameKanaAndNumberSearch() {
        ready()
        assertEquals("true", js("(()=>{term='ぴかちゅう';return records.some(r=>r[2]==='ピカチュウ'&&searchMatch(r))})()"))
        assertEquals("true", js("(()=>{term='ピカチュウ';return records.some(r=>r[2]==='ピカチュウ'&&searchMatch(r))})()"))
        assertEquals("true", js("(()=>{term='２５';return records.some(r=>r[1]===25&&searchMatch(r))})()"))
    }

    @Test fun t03_japaneseInputKeepsDomFieldAndFiltersResults() {
        ready()
        assertEquals("true", js("(()=>{switchTab('dex');const input=document.querySelector('#query');input.value='ピカチュウ';onSearch(input.value);return document.querySelector('#query')===input && term==='ピカチュウ' && document.querySelectorAll('.group').length>0;})()"))
        assertEquals("true", js("(()=>{const input=document.querySelector('#query');onSearch('ぴかちゅう');return input===document.querySelector('#query') && !!document.querySelector('#search-results')})()"))
    }

    @Test fun t04_cutoffAndVariantSourceSeparation() {
        ready()
        assertEquals("true", js("records.every(r=>r[6]!=='planned' && !(r[5].length>=10 && /^2026-/.test(r[5]) && r[5]>'2026-10-01'))"))
        assertEquals("true", js("records.some(r=>r[0].startsWith('HIS-')) && records.some(r=>r[0].startsWith('CHK-'))"))
        assertEquals("true", js("records.some(r=>r[6]==='official') && records.some(r=>r[6]==='reference')"))
    }

    @Test fun t05_missingOwnedFiltersAndManualAddRemove() {
        ready()
        assertEquals("true", js("(()=>{term='';switchTab('dex');setFilter('missing');const r=records.find(x=>x[0].startsWith('CHK-')&&getCount(x)===0);if(!r)return false;const old=getCount(r);changeCount(r[0],1);const now=getCount(r)===old+1;setFilter('owned');const visible=recordVisible(r);changeCount(r[0],-1);setFilter('all');return now && visible && getCount(r)===old})()"))
    }

    @Test fun t06_repeatedCopiesAndUpperBound() {
        ready()
        assertEquals("true", js("(()=>{term='';const r=records.find(x=>x[0].startsWith('OFF-'));if(!r)return false;const before=getCount(r);changeCount(r[0],1);changeCount(r[0],1);const two=getCount(r)===before+2;changeCount(r[0],-1);changeCount(r[0],-1);return two && getCount(r)===before})()"))
    }

    @Test fun t07_sortEraAndVariantFilter() {
        ready()
        assertEquals("true", js("(()=>{term='';setSort('name');const n=sorted(records.slice(0,60));const ok=n.every((r,i)=>!i || n[i-1][2].localeCompare(r[2],'ja')<=0);setSort('dex');return ok})()"))
        assertEquals("true", js("(()=>{setEra('2010');const r=records.find(x=>String(x[5]).startsWith('2018'));const year=!!r&&recordVisible(r);setEra('all');return year})()"))
        assertEquals("true", js("(()=>{toggleSpecial();const a=records.filter(recordVisible);const ok=a.length>0&&a.every(isVariant);toggleSpecial();return ok})()"))
    }

    @Test fun t08_photoIntegrityAndBadReferenceWarning() {
        ready()
        assertEquals("true", js("records.filter(r=>r[9]==='needs_review').every(r=>!art(r))"))
        assertEquals("true", js("records.every(r=>!art(r) || !art(r).includes('PokeAPI/sprites'))"))
        assertEquals("true", js("(()=>{const r=records.find(x=>x[9]==='needs_review');return !r || productCard(r).includes('写真要照合')})()"))
    }

    @Test fun t09_backupExportRestoreValidRoundTrip() {
        ready()
        rule.scenario.onActivity { a ->
            val prefs = a.getSharedPreferences("owned", Context.MODE_PRIVATE)
            val id = "CHK-00-000"
            val prior = prefs.getInt(id, 0)
            val backup = a.javaClass.getDeclaredMethod("backupJson").apply { isAccessible = true }
            val merge = a.javaClass.getDeclaredMethod("mergeBackup", String::class.java).apply { isAccessible = true }
            try {
                assertTrue(prefs.edit().putInt(id, 7).commit())
                val json = backup.invoke(a) as String
                assertEquals("pokepino-collection", JSONObject(json).getString("format"))
                assertEquals(7, JSONObject(json).getJSONObject("owned").getInt(id))
                assertTrue(prefs.edit().putInt(id, 2).commit())
                val changed = merge.invoke(a, json) as Int
                assertEquals(1, changed)
                assertEquals(7, prefs.getInt(id, 0))
            } finally {
                val edit = prefs.edit()
                if (prior > 0) edit.putInt(id, prior) else edit.remove(id)
                assertTrue(edit.commit())
            }
        }
    }

    @Test fun t10_backupRejectsTamperingAndNeverLowersOwned() {
        ready()
        rule.scenario.onActivity { a ->
            val prefs = a.getSharedPreferences("owned", Context.MODE_PRIVATE)
            val id = "CHK-00-000"
            val prior = prefs.getInt(id, 0)
            val merge = a.javaClass.getDeclaredMethod("mergeBackup", String::class.java).apply { isAccessible = true }
            try {
                assertTrue(prefs.edit().putInt(id, 5).commit())
                val invalid = listOf(
                    """{"format":"not-pokepino","schema":1,"owned":{"CHK-00-000":9}}""",
                    """{"format":"pokepino-collection","schema":1,"owned":{"CHK-00-000":-1}}""",
                    """{"format":"pokepino-collection","schema":1,"owned":{"CHK-00-000":1000}}""",
                    """{"format":"pokepino-collection","schema":1,"owned":{"CHK-00-000":"5"}}"""
                )
                for (bad in invalid) {
                    var rejected = false
                    try { merge.invoke(a,bad) }
                    catch (_: InvocationTargetException) { rejected = true }
                    assertTrue("Invalid import should be rejected",rejected)
                    assertEquals(5,prefs.getInt(id,0))
                }
                val result = merge.invoke(a, """{"format":"pokepino-collection","schema":1,"owned":{"CHK-00-000":3}}""") as Int
                assertEquals(0,result)
                assertEquals(5,prefs.getInt(id,0))
            } finally {
                val edit=prefs.edit()
                if(prior>0)edit.putInt(id,prior) else edit.remove(id)
                assertTrue(edit.commit())
            }
        }
    }
}
