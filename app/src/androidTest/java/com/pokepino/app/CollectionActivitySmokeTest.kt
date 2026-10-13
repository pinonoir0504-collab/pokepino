package com.pokepino.app

import android.webkit.WebView
import android.view.ViewGroup
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class CollectionActivitySmokeTest {
    @get:Rule val rule = ActivityScenarioRule(CollectionActivity::class.java)

    private fun js(expression: String): String {
        val latch = CountDownLatch(1)
        val result = AtomicReference("null")
        rule.scenario.onActivity { activity ->
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            val web = root.getChildAt(0) as WebView
            web.evaluateJavascript(expression) { value ->
                result.set(value)
                latch.countDown()
            }
        }
        assertTrue("WebView script timeout", latch.await(10, TimeUnit.SECONDS))
        return result.get()
    }

    @Test fun launchesCatalogAndLoadsOfficialImages() {
        var ready = false
        repeat(25) {
            if (js("typeof records!=='undefined' && records.length>1500 && records.length<2800") == "true") {
                ready = true
                return@repeat
            }
            Thread.sleep(300)
        }
        assertTrue("Collection catalog must load without JS syntax errors", ready)
        assertEquals("true", js("typeof galleries==='undefined' && typeof chosen==='undefined'"))
        assertEquals("true", js("records.filter(r=>r[0].startsWith('HIS-')).length===440"))
        assertEquals("true", js("records.filter(r=>r[0].startsWith('CHK-')).length===182"))
        assertEquals("true", js("records.every(r=>r[6]!=='planned')"))
        assertEquals("true", js("records.some(r=>r[3].includes('サトシのメルタン'))"))
        assertEquals("true", js("records.some(r=>r[4].includes('ピカチュウピカピカ大集合'))"))

        assertEquals("true", js("records.some(r=>r[3]==='ミュウ（シークレット）' && r[5]==='2018-12-03')"))
        assertEquals("true", js("records.some(r=>r[3].includes('しんさくフォルム') && r[5]==='2022-11-21')"))
        assertEquals("true", js("records.some(r=>r[4].includes('2008 ポケモンキッズDP ぜんこく図鑑版'))"))
        assertEquals("true", js("typeof searchInput==='function' && typeof changeCount==='function'"))
        assertEquals("true", js("records.some(r=>r[6]==='official')"))
        assertEquals("true", js("records.some(r=>r[7] && !r[7].includes('noimage'))"))
        assertEquals("true", js("records.some(r=>r[0]==='WHF1997-078')"))
        assertEquals("true", js("records.every(r=>r[6]!=='legacy_unverified' || getCount(r)>0)"))
        assertEquals("true", js("records.filter(r=>r[1]===25).length>=60"))
        assertEquals("true", js("(()=>{term='ピカチュウ';render();return !!document.querySelector('.group')})()"))
        assertEquals("true", js("(()=>{setFilter('missing');return !!document.querySelector('.group')})()"))
    }

    @Test fun ownedCountsAndNameSearchAreUsable() {
        var ready = false
        repeat(25) {
            if (js("typeof records!=='undefined' && records.length>1500 && records.length<2800") == "true") {
                ready=true
                return@repeat
            }
            Thread.sleep(300)
        }
        assertTrue(ready)
        val count = js("(()=>{const r=records.find(x=>x[6]==='official');if(!r)return false;const old=getCount(r);changeCount(r[0],1);const ok=getCount(r)===old+1;changeCount(r[0],-1);return ok && getCount(r)===old})()")
        assertEquals("true", count)
        val search = js("(()=>{term='ピカチュウ';switchTab('dex');setFilter('all');const groups=document.querySelectorAll('.group');return groups.length>0 && [...groups].every(g=>records.some(r=>groupKey(r)===g.dataset.key && searchMatch(r) && recordVisible(r)));})()")
        assertEquals("true",search)
    }
}
