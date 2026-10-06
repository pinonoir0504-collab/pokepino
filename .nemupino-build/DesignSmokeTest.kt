package com.oyasumi.recorder

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DesignSmokeTest {
    @Test fun resultLayoutAndRankingOpen() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val store=SessionStore.get(context)
        val file=store.recordingFile("design-fixture.m4a")
        instrumentation.context.assets.open("ui-bursts.m4a").use { input -> file.outputStream().use { input.copyTo(it) } }
        val samples=AudioEnvelope.read(file)
        val now=System.currentTimeMillis()
        store.save(RecordingSession("design-fixture",now-18000,now,file.name,listOf(SoundEvent(1000,4000,-18f,"いびき候補"),SoundEvent(8000,12000,-20f)),samples))
        RecordingBus.completed("design-fixture")
        instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("show_results",true))
        fun find(node: AccessibilityNodeInfo?, value: String): AccessibilityNodeInfo? {
            if(node==null)return null
            if(node.text?.toString()==value || node.contentDescription?.toString()==value)return node
            for(i in 0 until node.childCount) find(node.getChild(i),value)?.let { return it }
            return null
        }
        val deadline=System.currentTimeMillis()+30000
        while(find(instrumentation.uiAutomation.rootInActiveWindow,"録音全体")==null && System.currentTimeMillis()<deadline)Thread.sleep(250)
        assertNotNull(find(instrumentation.uiAutomation.rootInActiveWindow,"録音全体"))
        assertNotNull(find(instrumentation.uiAutomation.rootInActiveWindow,"次の区間"))
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap -> File(context.cacheDir,"design-overview.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
        val wave=find(instrumentation.uiAutomation.rootInActiveWindow,"詳細波形 · 30秒")!!
        val waveRect=android.graphics.Rect();wave.getBoundsInScreen(waveRect)
        val next=find(instrumentation.uiAutomation.rootInActiveWindow,"次の区間")!!
        val nextRect=android.graphics.Rect();next.getBoundsInScreen(nextRect)
        assertTrue("Detail toolbar and playback must fit together",waveRect.bottom < nextRect.top)
        val rank=find(instrumentation.uiAutomation.rootInActiveWindow,"大いびきTOP5")!!
        val rect=android.graphics.Rect();rank.getBoundsInScreen(rect)
        val time=android.os.SystemClock.uptimeMillis()
        listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP).forEach { action ->
            val event=android.view.MotionEvent.obtain(time,android.os.SystemClock.uptimeMillis(),action,rect.exactCenterX(),rect.exactCenterY(),0)
            event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.uiAutomation.injectInputEvent(event,true);event.recycle()
        }
        val dialogDeadline=System.currentTimeMillis()+15000
        while(find(instrumentation.uiAutomation.rootInActiveWindow,"1位")==null && System.currentTimeMillis()<dialogDeadline)Thread.sleep(250)
        assertNotNull(find(instrumentation.uiAutomation.rootInActiveWindow,"1位"))
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap -> File(context.cacheDir,"design-ranking.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
    }
}
