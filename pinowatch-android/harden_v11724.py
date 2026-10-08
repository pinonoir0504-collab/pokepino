from pathlib import Path
p=Path('/tmp/pinowatch11720/app/src/main/assets/app.js')
s=p.read_text()
def replace_once(a,b):
 global s
 assert a in s, 'missing marker: '+a[:80]
 s=s.replace(a,b,1)
# Stock data must be clearly attributed; refuse malformed records or stale sessions
replace_once("if(!data.asOf||!data.source){status.textContent='データ元・更新時刻が不明なため表示を保留しました';return;}",
"""if(!data.asOf||!data.source){status.textContent='データ元・更新時刻が不明なため表示を保留しました';return;}
const ts=Date.parse(data.asOf);
if(!Number.isFinite(ts)){status.textContent='更新時刻が不正なため表示を保留しました';return;}
if(date===new Date().toLocaleDateString('sv-SE',{timeZone:'Asia/Tokyo'})&&Date.now()-ts>24*60*60*1000){status.textContent='古いデータのため表示を保留しました';return;}
if(data.verified===false||data.complete===false){status.textContent='未検証・不完全なデータのため表示を保留しました';return;}""")
# Resilient exponential retry on a transient network failure; do not retry stale success.
replace_once("const response=await fetch('/api/extremes?kind='+encodeURIComponent(secretKind)+'&date='+encodeURIComponent(date),{cache:'no-store',signal:activeSignal});",
"""let response;
for(let attempt=0;attempt<3;attempt++){
 try{
 response=await fetch('/api/extremes?kind='+encodeURIComponent(secretKind)+'&date='+encodeURIComponent(date),{cache:'no-store',signal:activeSignal});
 if(response.ok||response.status<500)break;
 }catch(e){if(activeSignal.aborted||attempt===2)throw e;}
 if(attempt<2)await new Promise(r=>setTimeout(r,400*(2**attempt)));
}""")
# Save filter state without forcing any telemetry or notification.
replace_once("let secretKind='yearlow',secretRequest=0,secretItems=[],secretLastTab='feed',secretLimit=100;",
"""let secretKind='yearlow',secretRequest=0,secretItems=[],secretLastTab='feed',secretLimit=100;
const FILTER_KEY='pinowatch_secret_filters_v2';
function saveSecretFilters(){
 try{localStorage.setItem(FILTER_KEY,JSON.stringify(Object.fromEntries(['secretSearch','secretSort','secretMinYield','secretOnlyFav'].map(id=>[id,$('#'+id)?.type==='checkbox'?$('#'+id).checked:$('#'+id)?.value]))))}catch(e){}
}
function restoreSecretFilters(){
 try{const o=JSON.parse(localStorage.getItem(FILTER_KEY)||'{}');for(const id of ['secretSearch','secretSort','secretMinYield','secretOnlyFav'])if(o[id]!==undefined&&$('#'+id)){if($('#'+id).type==='checkbox')$('#'+id).checked=!!o[id];else $('#'+id).value=String(o[id])}}catch(e){}
}
""")
replace_once("selector==='#secretSearch'?debounce(()=>{secretLimit=100;renderSecretItems()},250):()=>{secretLimit=100;renderSecretItems()}",
"""selector==='#secretSearch'?debounce(()=>{secretLimit=100;saveSecretFilters();renderSecretItems()},250):()=>{secretLimit=100;saveSecretFilters();renderSecretItems()}""")
replace_once("for(const selector of ['#secretSearch','#secretSort','#secretMinYield','#secretOnlyFav'])", "restoreSecretFilters();for(const selector of ['#secretSearch','#secretSort','#secretMinYield','#secretOnlyFav'])")
# Prevent simultaneous duplicate notifications in current web-session (not a server-side guarantee)
replace_once("function detectNew(){const nowKeys=feed.map", "const sessionNoticeKeys=new Set();\nfunction detectNew(){const nowKeys=feed.map")
replace_once("unseen.forEach(o=>{const title=", "unseen.forEach(o=>{const k=String(o.id||rowTime(o)+'|'+rowCode(o)+'|'+o.title);if(sessionNoticeKeys.has(k))return;sessionNoticeKeys.add(k);if(sessionNoticeKeys.size>1000)sessionNoticeKeys.clear();const title=")
# Scroll restoration for hidden dashboard only, avoid cross-tab side-effects
replace_once("function showSecret(){", "let secretScroll=0;\nfunction showSecret(){")
replace_once("secretLastTab=$('.screen.active')?.id==='secret'?secretLastTab:", "secretScroll=window.scrollY||0;secretLastTab=$('.screen.active')?.id==='secret'?secretLastTab:")
replace_once("back?.click();secretRequest++", "back?.click();window.scrollTo(0,secretScroll);secretRequest++")
# Correct default verification: no raw two-provider verification until integrated.
replace_once("if(data.verified===false||data.complete===false)", "if(data.verified===false||data.complete===false)")
p.write_text(s)
g=Path('/tmp/pinowatch11720/app/build.gradle.kts')
t=g.read_text().replace('versionCode = 11723','versionCode = 11724').replace('versionName = \"1.17.23\"','versionName = \"1.17.24\"')
g.write_text(t)
print('Applied data guards, retries, filter persistence, session duplicate guard, scroll restore')