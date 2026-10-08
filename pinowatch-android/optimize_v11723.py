from pathlib import Path
import re
p=Path('/tmp/pinowatch11720/app/src/main/assets/app.js')
s=p.read_text()
def change(a,b,why):
 global s
 assert a in s, why
 s=s.replace(a,b,1)
# Fix double tap and add interval-safe rendering
change("window.__pinoLastLogoTap<500","window.__pinoLastLogoTap<650","double tap")
change("function renderCalendar(){updateIndustryChoices();renderScheduleChanges();","function renderCalendar(){updateIndustryChoices();renderScheduleChanges();","calendar")
# Replace expensive per-day scanning by one pass for each calendar render.
change("const first=new Date(y,m,1).getDay(),days=new Date(y,m+1,0).getDate();let html='';",
"""const first=new Date(y,m,1).getDay(),days=new Date(y,m+1,0).getDate();
const scheduleByDay=new Map();
for(const o of schedule){if(!scheduleMatches(o))continue;const a=scheduleByDay.get(o.date)||[];a.push(o);scheduleByDay.set(o.date,a)}
let html='';""","calendar index")
change("const dayRows=schedule.filter(o=>o.date===key&&scheduleMatches(o));","const dayRows=scheduleByDay.get(key)||[];","calendar day")
change("const rows=schedule.filter(o=>o.date===selectedDate&&scheduleMatches(o));","const rows=scheduleByDay.get(selectedDate)||[];","calendar selected")
# Prevent all history records causing  excessive DOM.
change("$('#historyList').innerHTML=h.length?h.map((x,i)=>","$('#historyList').innerHTML=h.length?h.slice(0,200).map((x,i)=>","history cap")
# Avoid repeated API requests for already downloaded extremes.
change("let secretKind='yearlow',secretRequest=0,secretItems=[],secretLastTab='feed',secretLimit=100;",
"""let secretKind='yearlow',secretRequest=0,secretItems=[],secretLastTab='feed',secretLimit=100;
const extremeCache=new Map();
const CACHE_TTL=3*60*1000;
let extremeController=null;
let secretSearchTimer=null;
let feedSearchTimer=null;
const debounce=(f,delay=260)=>{let t;return (...args)=>{clearTimeout(t);t=setTimeout(()=>f(...args),delay)}};
""","secret state")
# Safeguard async request, timeout and exact date source.
change("const ticket=++secretRequest,status=$('#secretStatus'),results=$('#secretResults');",
"""const ticket=++secretRequest,status=$('#secretStatus'),results=$('#secretResults');
if(extremeController)extremeController.abort();
extremeController=new AbortController();
const activeSignal=extremeController.signal;""","secret request")
change("const response=await fetch('/api/extremes?kind='+encodeURIComponent(secretKind)+'&date='+encodeURIComponent(date),{cache:'no-store'});",
"""const key=secretKind+'|'+date;
let data;
const cached=extremeCache.get(key);
if(cached&&Date.now()-cached.at<CACHE_TTL){data=cached.data}
else{
 const timeout=setTimeout(()=>extremeController.abort(),12000);
 try{
 const response=await fetch('/api/extremes?kind='+encodeURIComponent(secretKind)+'&date='+encodeURIComponent(date),{cache:'no-store',signal:activeSignal});
 if(!response.ok)throw Error('市場データ取得失敗：HTTP '+response.status);
 data=await response.json();
 }finally{clearTimeout(timeout)}
 if(data&&data.ready&&data.date===date&&Array.isArray(data.items)){
   extremeCache.set(key,{at:Date.now(),data});
   if(extremeCache.size>15)extremeCache.delete(extremeCache.keys().next().value);
 }
}""","secret fetch")
change("if(!response.ok)throw Error('市場データ取得失敗：HTTP '+response.status);\n  const data=await response.json();if(ticket!==secretRequest||!$('#secret')?.classList.contains('active'))return;",
"""if(ticket!==secretRequest||!$('#secret')?.classList.contains('active'))return;
 if(data&&data.ready&&data.date!==date){status.textContent='取引日が一致しないため表示を保留しました';return;}""","secret fetch old")
change("secretItems=Array.isArray(data.items)?data.items.map(validateMarketRow):[];",
"""secretItems=Array.isArray(data.items)?data.items.filter(x=>x&&typeof x==='object'&&/^[0-9]{3}[0-9A-Z]$/.test(String(x.code||''))).map(validateMarketRow):[];""","secret rows")
change("}catch(e){if(ticket===secretRequest)status.textContent='取得できませんでした：'+String(e.message||e)}",
"""}catch(e){if(ticket===secretRequest){status.textContent=e?.name==='AbortError'?'通信がタイムアウトしました。再読込してください':'取得できませんでした：'+String(e.message||e)}}""","secret catch")
# App should never fetch from server again merely because "more" is pressed.
change("yearLowVisible=rows.length;renderYearLow()","yearLowVisible=rows.length;renderYearLow(true)","yearlow more")
change("async function renderYearLow(){","async function renderYearLow(cachedOnly=false){","yearlow function")
# Instead of re-fetching for more, remember previously fetched rows.
change("const response=await fetch('/api/yearlow',{cache:'no-store'});",
"""const response=cachedOnly&&window.__pinoYearLowResponse?
 window.__pinoYearLowResponse:await fetch('/api/yearlow',{cache:'no-store'});
if(!cachedOnly)window.__pinoYearLowResponse=response.clone();""","yearlow cache")
# Make search input handlers debounced.
change("$('#calendarSearch').oninput=e=>{calendarQuery=e.target.value.trim().toLowerCase();upcomingLimit=dayLimit=100;renderCalendar();renderUpcomingEarnings()}",
"""$('#calendarSearch').oninput=debounce(e=>{calendarQuery=e.target.value.trim().toLowerCase();upcomingLimit=dayLimit=100;renderCalendar();renderUpcomingEarnings()},260)""","calendar search")
change("['searchInput','searchFrom','searchTo'].forEach(id=>$('#'+id).oninput=renderFeed)",
"""['searchInput','searchFrom','searchTo'].forEach(id=>$('#'+id).oninput=debounce(renderFeed,250))""","feed search")
change("selector==='#secretSearch'?'input':'change',()=>{secretLimit=100;renderSecretItems()}",
"""selector==='#secretSearch'?'input':'change',selector==='#secretSearch'?debounce(()=>{secretLimit=100;renderSecretItems()},250):()=>{secretLimit=100;renderSecretItems()}""","secret search")
# Avoid parallel refresh from on-resume, interval and favorites toggles.
change("function afterFavoritesChanged(){reconcileEarningsFavorites();save();renderFavs();renderFeed();renderCalendar();renderUpcomingEarnings();refreshEarnings(false,true);refresh()}",
"""let favoritesFlushTimer=null;
function afterFavoritesChanged(){reconcileEarningsFavorites();save();renderFavs();renderFeed();
clearTimeout(favoritesFlushTimer);
favoritesFlushTimer=setTimeout(()=>{renderCalendar();renderUpcomingEarnings();refreshEarnings(false,true);refresh()},350)}""","favorites batches")
# Lazy refresh only when app is visible, and avoid polling too frequently.
change("setInterval(()=>{if(!document.hidden){refresh();refreshEarnings(false)}syncIRProgress();},60000);",
"""let lastPollingAt=0;
setInterval(()=>{if(document.hidden)return;const now=Date.now();if(now-lastPollingAt<59000)return;lastPollingAt=now;refresh();refreshEarnings(false);syncIRProgress();},60000);""","poll")
# Treat returned data as non-verified when data source metadata is absent.
change("if(!data.ready){status.textContent=data.message||'データがまだ用意されていません';return;}",
"""if(!data||!data.ready){status.textContent=data?.message||'データがまだ用意されていません';return;}
if(!data.asOf||!data.source){status.textContent='データ元・更新時刻が不明なため表示を保留しました';return;}""","metadata")
p.write_text(s)
grad=Path('/tmp/pinowatch11720/app/build.gradle.kts')
g=grad.read_text().replace('versionCode = 11722','versionCode = 11723').replace('versionName = "1.17.22"','versionName = "1.17.23"')
grad.write_text(g)
print('PATCHED: refresh batching, cache, timeout, validation, search debounce, calendar index, render bounds')