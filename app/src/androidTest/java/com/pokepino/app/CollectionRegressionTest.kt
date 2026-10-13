package com.pokepino.app

import android.view.ViewGroup
import android.webkit.WebView
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
class CollectionRegressionTest {
    @get:Rule val rule = ActivityScenarioRule(CollectionActivity::class.java)

    private fun js(source: String): String {
        val latch = CountDownLatch(1)
        val out = AtomicReference("null")
        rule.scenario.onActivity { activity ->
            val view = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as WebView
            view.evaluateJavascript(source) { result ->
                out.set(result); latch.countDown()
            }
        }
        assertTrue("JavaScript did not complete", latch.await(15, TimeUnit.SECONDS))
        return out.get()
    }

    private fun waitForCatalog() {
        var ready = false
        repeat(35) {
            if (js("typeof records!=='undefined' && records.length>1500")=="true") ready = true
            if (ready) return@repeat
            Thread.sleep(200)
        }
        assertTrue("Catalog did not load", ready)
    }

    @Test fun productImageMustNotBeAnotherRandomFigureFromSeries() {
        waitForCatalog()
        assertEquals("true", js("records.filter(r=>r[0].startsWith('OFF-')||r[0].startsWith('CHK-')).every(r=>{ const image=art(r); return !image || !image.includes('/published/bnc_files/product/'); })"))
        assertEquals("true", js("typeof chooseGallery==='undefined' && typeof openScanner==='undefined'"))
        assertEquals("true", js("!document.querySelector('#nav-scan') && document.querySelectorAll('nav button').length===3"))
    }

    @Test fun backupAndKanaSearchAndSorting() {
        waitForCatalog()
        assertEquals("true", js("(()=>{term='ぴかちゅう';return records.some(r=>r[2]==='ピカチュウ' && searchMatch(r))})()"))
        assertEquals("true", js("(()=>{term='ピカチュウ';return records.some(r=>r[2]==='ピカチュウ' && searchMatch(r))})()"))
        assertEquals("true", js("(()=>{switchTab('settings');return !!document.querySelector('.backupBtn.primary') && typeof N.exportBackup==='function' && typeof N.restoreBackup==='function'})()"))
        assertEquals("true", js("(()=>{setSort('name');setEra('2010');toggleSpecial();const v=sortMode==='name'&&era==='2010'&&specialOnly;setSort('dex');setEra('all');toggleSpecial();return v})()"))
        assertEquals("true", js("records.some(r=>r.length===10 && r[9]==='needs_review')"))
    }
    @Test fun publishedBeforeOctoberCutoffOnly() {
        waitForCatalog()
        assertEquals("true", js("records.every(r=>r[6]!=='planned')"))
        assertEquals("true", js("records.every(r=>!['https://www.bandai.co.jp/candy/products/2026/4570117932410000.html','https://www.bandai.co.jp/candy/products/2026/4570117935251000.html'].includes(r[8]))"))
    }

    @Test fun selectedPokemonRespectsSearchAndBackWorks() {
        waitForCatalog()
        val selected = js("(()=>{term='ピカチュウ';setFilter('all');const r=records.find(x=>x[2]==='ピカチュウ');if(!r)return false;showGroup(groupKey(r));return !!document.querySelector('.record') && !!document.querySelector('.bar') && window.goBackFromAndroid()===true;})()")
        assertEquals("true", selected)
        assertEquals("true", js("(()=>{term='';switchTab('about');const a=window.goBackFromAndroid();const b=window.goBackFromAndroid();return a===true&&b===false;})()"))
    }

    @Test fun manualNameSearchAndOwnedCountDoNotAffectOtherItems() {
        waitForCatalog()
        assertEquals("true", js("(()=>{const a=records.find(r=>r[0].startsWith('CHK-')),b=records.find(r=>r[0].startsWith('HIS-'));if(!a||!b)return false;const prev=getCount(a),other=getCount(b);changeCount(a[0],1);const ok=getCount(a)===prev+1 && getCount(b)===other;term=a[2];switchTab('owned');const searched=!!document.querySelector('#query') && document.querySelectorAll('.record').length>0;changeCount(a[0],-1);return ok && searched && getCount(a)===prev;})()"))
    }
}
