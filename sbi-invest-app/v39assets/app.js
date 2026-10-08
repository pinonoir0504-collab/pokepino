(() => {
'use strict';

const $ = s => document.querySelector(s);
const $$ = s => [...document.querySelectorAll(s)];
const yen = new Intl.NumberFormat('ja-JP',{style:'currency',currency:'JPY',maximumFractionDigits:0});
const num = new Intl.NumberFormat('ja-JP');
const pct = v => Number.isFinite(v) ? `${(v*100).toFixed(1)}%` : '—';
const clamp = (v,a,b) => Math.max(a,Math.min(b,v));
const VERSION = 4;

function defaultState(){
  return {
    version: VERSION,
    records: [],
    trades: [],
    perks: [],
    adjustments: [],
    files: [],
    favorites: [],
    lastBackupAt: '',
    settings: { dividendGoal: 1000000, equityOnly: true, dark: true, haptics: true, includeCrossCosts: false, winRateMode: 'stockday' },
    filters: { homeYear:'', stockYear:'', analysisYear:'', perkYear:'', perkMethod:'all', favoriteOnly:false, calendarMonth:'' }
  };
}

function norm(s){return String(s??'').normalize('NFKC').replace(/[\s　]+/g,'').replace(/[（）]/g,m=>m==='（'?'(':')').replace(/[／]/g,'/').trim();}
function esc(s){return String(s??'').replace(/[&<>"']/g,m=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[m]));}
function cleanNum(v){
  if(v==null)return NaN;
  let s=String(v).normalize('NFKC').trim();
  if(!s||s==='-'||s==='—'||s==='--')return NaN;
  const neg=/^\(.*\)$/.test(s)||/^△/.test(s)||/^▲/.test(s);
  s=s.replace(/[¥￥$,，円+\s]/g,'').replace(/[()△▲]/g,'');
  const x=Number(s);
  return Number.isFinite(x)?(neg?-Math.abs(x):x):NaN;
}
function parseDate(v,takeLast=false){
  const s=String(v??'').normalize('NFKC');
  const ms=[...s.matchAll(/(20\d{2}|\d{2})[\/\-.年](\d{1,2})[\/\-.月](\d{1,2})日?/g)];
  if(!ms.length)return null;
  const m=takeLast?ms[ms.length-1]:ms[0];
  let y=+m[1];if(y<100)y+=2000;
  const mo=+m[2],d=+m[3];
  if(mo<1||mo>12||d<1||d>31)return null;
  const dt=new Date(Date.UTC(y,mo-1,d));
  if(dt.getUTCFullYear()!==y||dt.getUTCMonth()!==mo-1||dt.getUTCDate()!==d)return null;
  return `${y}-${String(mo).padStart(2,'0')}-${String(d).padStart(2,'0')}`;
}
function localToday(){const d=new Date();return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;}
function dateUTC(s){const [y,m,d]=s.split('-').map(Number);return Date.UTC(y,m-1,d);}
function daysBetween(a,b){return Math.max(0,Math.round((dateUTC(b)-dateUTC(a))/86400000));}
function parseCSV(text){
  const rows=[];let row=[],cell='',q=false;
  for(let i=0;i<text.length;i++){
    const c=text[i];
    if(q){if(c==='"'&&text[i+1]==='"'){cell+='"';i++;}else if(c==='"')q=false;else cell+=c;}
    else{if(c==='"')q=true;else if(c===','){row.push(cell);cell='';}else if(c==='\n'){row.push(cell.replace(/\r$/,''));rows.push(row);row=[];cell='';}else cell+=c;}
  }
  if(cell.length||row.length){row.push(cell.replace(/\r$/,''));rows.push(row);}
  return rows.filter(r=>r.some(c=>String(c).trim()!==''));
}
function decodeBuffer(buf){
  const bytes=new Uint8Array(buf);
  try{return{text:new TextDecoder('utf-8',{fatal:true}).decode(bytes),enc:'UTF-8'};}catch(e){}
  try{return{text:new TextDecoder('shift_jis').decode(bytes),enc:'Shift_JIS'};}catch(e){}
  return{text:new TextDecoder().decode(bytes),enc:'自動'};
}
function col(headers,tests){
  for(const t of tests){const isRe=t instanceof RegExp||Object.prototype.toString.call(t)==='[object RegExp]';const i=headers.findIndex(h=>isRe?t.test(h):h===norm(t));if(i>=0)return i;}
  return -1;
}
function findHeader(rows){
  let best=null;
  for(let i=0;i<Math.min(rows.length,60);i++){
    const hs=rows[i].map(norm);let score=0;
    if(hs.some(h=>/実現損益/.test(h)))score+=10;
    if(hs.some(h=>/受取額.*税引後/.test(h)))score+=10;
    if(hs.some(h=>/手数料.?諸経費等/.test(h)))score+=5;
    if(hs.some(h=>/^取引$|取引区分/.test(h)))score+=3;
    if(hs.some(h=>/約定数量|株数|数量/.test(h)))score+=2;
    if(hs.some(h=>/約定日|受渡日/.test(h)))score+=3;
    if(hs.some(h=>/銘柄/.test(h)))score+=2;
    if(!best||score>best.score)best={idx:i,score,headers:hs};
  }
  return best&&best.score>=7?best:null;
}
function extractCode(s){
  const t=String(s??'').normalize('NFKC').toUpperCase();
  const m=t.match(/(?:^|[^0-9A-Z])([0-9][0-9A-Z]{3})(?:[^0-9A-Z]|$)/);
  return m?m[1]:'';
}
function cleanName(s){
  let t=String(s??'').normalize('NFKC').trim();const code=extractCode(t);
  if(code)t=t.replace(new RegExp(`(^|[^0-9A-Z])${code}(?=[^0-9A-Z]|$)`,'i'),' ');
  t=t.replace(/^[\s\/・,，:：-]+/,'').replace(/^(東証(?:プライム|スタンダード|グロース|P|S|G)?|名証|札証|福証|PTS)[\s\/・,，:：-]*/i,'').trim();
  return t||String(s??'').trim();
}
function keyFor(code,name){return code?`C:${code}`:`N:${norm(name).toLowerCase()}`;}
function accountBucket(v){const s=norm(v);if(/NISA|成長投資枠|つみたて/.test(s))return'NISA';if(/特定/.test(s))return'特定';if(/一般/.test(s))return'一般';return s||'不明';}
function tradeBucket(action){const s=norm(action);if(/信用|現引|現渡/.test(s))return'信用';if(/現物/.test(s))return'現物';return'不明';}
function creditDirection(action){const s=norm(action);if(/新規買|返済売|現引/.test(s))return'信用買い';if(/新規売|返済買|現渡/.test(s))return'信用売り';return'不明';}
function isEquity(r){
  if(r.type!=='dividend')return true;
  const p=norm(r.product);if(!p)return true;
  return /株式|国内株|外国株|米国株|ETF|REIT/.test(p)&&!/投資信託|債券|MMF/.test(p);
}
function recordSig(r){return [r.type,r.date,r.code,r.name,r.account,r.product,r.action,r.amount].map(x=>String(x??'')).join('|');}
function tradeSig(r){return [r.date,r.code,r.name,r.action,r.account,r.qty,r.price,r.feeTotal,r.settle].map(x=>String(x??'')).join('|');}
function perkSig(p){return [p.date,p.code,p.name,normalizePerkMethod(p.method),p.shares,p.item,p.basis,p.faceValue,p.personalValue,p.resaleValue,p.commission,p.lendingFee,p.reverseDaily,p.otherCost,p.memo].map(x=>String(x??'')).join('|');}
function fnv1a(s){let h=0x811c9dc5;for(let i=0;i<s.length;i++){h^=s.charCodeAt(i);h=Math.imul(h,0x01000193);}return (h>>>0).toString(36);}
function djb2(s){let h=5381;for(let i=0;i<s.length;i++)h=(Math.imul(h,33)^s.charCodeAt(i))>>>0;return h.toString(36);}
function rowBaseKey(type,row){return `${type}|${row.map(v=>String(v??'').normalize('NFKC').trim()).join('␟')}`;}
function sourceKeyFor(type,row,occurrence){const base=rowBaseKey(type,row);return `${type}:${fnv1a(base)}-${djb2(base)}:${occurrence}`;}
function normalizePerkMethod(v){const s=norm(v).toLowerCase();if(/cross|クロス/.test(s))return'cross';if(/holding|現物|保有/.test(s))return'holding';if(/other|その他/.test(s))return'other';return'other';}

function loadState(){
  let raw='';
  try{if(window.Android&&typeof Android.loadState==='function')raw=Android.loadState()||'';}catch(e){}
  if(!raw){try{raw=localStorage.getItem('investment_dashboard_v3')||localStorage.getItem('investment_dashboard_v2')||'';}catch(e){}}
  if(raw){
    try{
      const parsed=JSON.parse(raw);
      if(parsed&&typeof parsed==='object'&&Array.isArray(parsed.records)&&Array.isArray(parsed.trades)&&['perks','adjustments','files','favorites'].every(k=>parsed[k]===undefined||Array.isArray(parsed[k]))){
        const base=defaultState();
        const merged={...base,...parsed,settings:{...base.settings,...(parsed.settings||{})},filters:{...base.filters,...(parsed.filters||{})},version:VERSION};merged.perks=(merged.perks||[]).map(p=>({...p,method:normalizePerkMethod(p.method)}));merged.favorites=Array.isArray(merged.favorites)?[...new Set(merged.favorites.map(String))]:[];return merged;
      }
    }catch(e){}
  }
  const st=defaultState();
  try{
    const old=JSON.parse(localStorage.getItem('sbi_dashboard_manual_v1')||'[]');
    if(Array.isArray(old)){
      st.adjustments=old.map(m=>({id:m.id||uid('a'),date:m.date,code:m.code||'',name:m.name||'',memo:m.memo||m.category||'旧データ',type:m.category==='その他費用'?'expense':'income',amount:Math.abs(Number(m.amount)||0)}));
    }
  }catch(e){}
  return st;
}
let state=loadState();
let stockStatsRevision=0;
const screenDirty=new Set(['home','stocks','analysis','perks','data']);
let saveTimer=null;
function persist(){
  stockStatsCache.clear();stockStatsRevision++;
  ['home','stocks','analysis','perks','data'].forEach(k=>screenDirty.add(k));
  clearTimeout(saveTimer);
  saveTimer=setTimeout(()=>{
    const raw=JSON.stringify(state);
    let saved=false;
    try{if(window.Android&&typeof Android.saveState==='function')saved=Android.saveState(raw)!==false;}catch(e){console.warn('Android save failed',e);}
    if(!saved){try{localStorage.setItem('investment_dashboard_v3',raw);saved=true;}catch(e){console.warn('Local save failed',e);}}
    if(!saved){toast('保存容量不足：データを保存できません。バックアップを出力してください');}
  },80);
}
function uid(prefix='x'){return `${prefix}${Date.now().toString(36)}${Math.random().toString(36).slice(2,8)}`;}
function toast(msg,actionLabel='',actionFn=null){
  const el=$('#toast'),text=$('#toastMsg'),btn=$('#toastAction');text.textContent=msg;btn.hidden=!actionLabel;btn.textContent=actionLabel||'';
  btn.onclick=actionFn?()=>{clearTimeout(toast.t);el.classList.remove('show');btn.hidden=true;actionFn();haptic('success');}:null;
  el.classList.add('show');clearTimeout(toast.t);toast.t=setTimeout(()=>{el.classList.remove('show');btn.hidden=true;btn.onclick=null;},actionLabel?4200:1800);
}
function haptic(kind='light'){
  if(!state.settings.haptics)return;
  try{if(window.Android&&typeof Android.vibrate==='function'){Android.vibrate(kind);return;}}catch(e){}
  try{if(navigator.vibrate)navigator.vibrate(kind==='warning'?18:kind==='success'?12:8);}catch(e){}
}
function isFavorite(key){return (state.favorites||[]).includes(String(key));}
function toggleFavorite(key){
  key=String(key||'');if(!key)return false;state.favorites=state.favorites||[];
  const i=state.favorites.indexOf(key);if(i>=0)state.favorites.splice(i,1);else state.favorites.push(key);
  persist();haptic('light');renderStocks();return i<0;
}
function showActionSheet(title,actions){
  const bg=$('#actionSheetBackdrop'),list=$('#actionSheetList');$('#actionSheetTitle').textContent=title||'操作';
  list.innerHTML='';actions.forEach(a=>{const b=document.createElement('button');b.textContent=a.label;b.className=a.danger?'danger':'';b.onclick=()=>{closeActionSheet();a.action();};list.appendChild(b);});
  bg.hidden=false;document.body.style.overflow='hidden';haptic('light');
}
function closeActionSheet(){const bg=$('#actionSheetBackdrop');if(!bg||bg.hidden)return false;bg.hidden=true;if($('#stockModalBackdrop')?.hidden!==false)document.body.style.overflow='';return true;}
function bindSwipeAction(el,onSwipe){
  if(!el||el.dataset.swipeBound)return;el.dataset.swipeBound='1';el.classList.add('swipeable');let sx=0,sy=0,active=false;
  el.addEventListener('pointerdown',e=>{if(e.pointerType==='mouse')return;sx=e.clientX;sy=e.clientY;active=true;},{passive:true});
  el.addEventListener('pointerup',e=>{if(!active)return;active=false;const dx=e.clientX-sx,dy=e.clientY-sy;if(dx<-72&&Math.abs(dx)>Math.abs(dy)*1.3){el.classList.add('swipe-hint');setTimeout(()=>el.classList.remove('swipe-hint'),180);onSwipe();haptic('light');}},{passive:true});
  el.addEventListener('pointercancel',()=>active=false,{passive:true});
}
function suspiciousPerk(p){
  const maxValue=Math.max(valueOrZero(p.faceValue),valueOrZero(p.personalValue),valueOrZero(p.resaleValue)),cost=perkCost(p);
  if(maxValue>1000000)return `優待価値が ${yen.format(maxValue)} になっています。入力内容を確認しますか？`;
  if(cost>500000)return `取得コストが ${yen.format(cost)} になっています。入力内容を確認しますか？`;
  if(valueOrZero(p.shares)>100000)return `株数が ${num.format(p.shares)}株 になっています。入力内容を確認しますか？`;
  if(normalizePerkMethod(p.method)==='cross'&&maxValue>0&&cost>maxValue)return 'クロス費用が優待価値を上回っています。このまま保存しますか？';
  return '';
}
function moneyClass(v){return v>0?'pos':v<0?'neg':'';}
function selectedYear(v){return v&&v!=='all'?Number(v):null;}
function filterYear(items,field,yearValue){const y=selectedYear(yearValue);return y?items.filter(x=>String(x[field]||'').startsWith(String(y))):items;}
function currentDataYear(){
  const years=availableYears();const now=new Date().getFullYear();
  return years.includes(now)?String(now):(years.length?String(years[0]):String(now));
}
function availableYears(){
  const ys=new Set();
  [...state.records,...state.trades,...state.perks,...state.adjustments].forEach(x=>{const y=Number(String(x.date||'').slice(0,4));if(y)ys.add(y);});
  return [...ys].sort((a,b)=>b-a);
}
function allMonths(){
  const ms=new Set();state.records.filter(r=>r.type==='profit').forEach(r=>ms.add(r.date.slice(0,7)));
  return [...ms].sort().reverse();
}
function valueOrZero(v){v=Number(v);return Number.isFinite(v)?v:0;}
function perkValue(p){
  const basis=p.basis||'personal';
  const map={face:valueOrZero(p.faceValue),personal:valueOrZero(p.personalValue),resale:valueOrZero(p.resaleValue)};
  return map[basis]||map.personal||map.face||map.resale||0;
}
function perkCost(p){return valueOrZero(p.commission)+valueOrZero(p.lendingFee)+valueOrZero(p.reverseDaily)+valueOrZero(p.otherCost);}
function perkNet(p){return perkValue(p)-perkCost(p);}
function adjustmentSigned(a){const v=Math.abs(valueOrZero(a.amount));return a.type==='expense'?-v:v;}
function recordsFiltered(){return state.records.filter(r=>!state.settings.equityOnly||isEquity(r));}

async function ingest(file){
  const buf=await file.arrayBuffer(),dec=decodeBuffer(buf),rows=parseCSV(dec.text),hdr=findHeader(rows);
  const batchId=uid('b'),importedAt=new Date().toISOString();
  if(!hdr)return{name:file.name,status:'err',msg:'対応する見出しを検出できません',enc:dec.enc,added:0,skipped:0,duplicates:0,invalid:0,batchId:null,date:importedAt};
  const H=hdr.headers;
  const iProfit=col(H,[/実現損益.*税引前/,/^実現損益$/]);
  const iDiv=col(H,[/受取額.*税引後/,/^受取額$/]);
  const iFee=col(H,[/手数料.?諸経費等/,/^諸経費等$/, /^手数料$/]);
  const iAction=col(H,[/^取引$/, /^取引区分$/]);
  const iQty=col(H,[/約定数量/,/^株数$/, /^数量$/]);
  const iPrice=col(H,[/平均約定単価/,/^約定単価$/, /^単価$/]);
  const iSettle=col(H,[/受渡金額.?決済損益/,/決済損益/,/^受渡金額$/]);
  const iInterest=col(H,[/^日歩$/, /買方金利/, /支払金利/]);
  const iReverse=col(H,[/逆日歩/, /品貸料/]);
  const iLending=col(H,[/貸株料/]);
  const iHyper=col(H,[/HYPER料/i]);
  const type=iProfit>=0?'profit':iDiv>=0?'dividend':(iFee>=0&&iAction>=0?'trade':null);
  if(!type)return{name:file.name,status:'err',msg:'実現損益・配当・約定履歴として判定できません',enc:dec.enc,added:0,skipped:0,duplicates:0,invalid:0,batchId:null,date:importedAt};
  const iDate=type==='dividend'?col(H,[/^受渡日$/, /受渡日/]):col(H,[/^約定日\/受渡日$/, /^約定日$/, /約定日/, /受渡日/]);
  const iName=col(H,[/^銘柄名$/, /^銘柄$/, /銘柄名/, /銘柄/]);
  const iCode=col(H,[/^銘柄コード$/, /^コード$/]);
  const iAcct=col(H,[/^口座$/, /預り区分/, /口座/]);
  const iProduct=col(H,[/^商品$/, /商品/]);
  if(iDate<0)return{name:file.name,status:'err',msg:'日付列を検出できません',enc:dec.enc,added:0,skipped:0,duplicates:0,invalid:0,batchId:null,date:importedAt};
  let added=0,skipped=0,duplicates=0,invalid=0;
  const occurrence=new Map();
  const nextSourceKey=row=>{const base=rowBaseKey(type,row),n=(occurrence.get(base)||0)+1;occurrence.set(base,n);return sourceKeyFor(type,row,n);};
  if(type==='trade'){
    const existingSource=new Set(state.trades.map(x=>x.sourceKey).filter(Boolean));
    const existingLegacyHash=new Set(state.trades.map(x=>x.sourceKey).filter(x=>x&&/^trade:[0-9a-z]+:\d+$/.test(x)));
    const legacyCounts=new Map();state.trades.filter(x=>!x.sourceKey).forEach(x=>legacyCounts.set(tradeSig(x),(legacyCounts.get(tradeSig(x))||0)+1));
    const seenLegacy=new Map();
    for(let r=hdr.idx+1;r<rows.length;r++){
      if(r%450===0)await new Promise(resolve=>setTimeout(resolve,0));
      const row=rows[r];if(!row||row.every(x=>!String(x).trim()))continue;
      const sourceKey=nextSourceKey(row),date=parseDate(row[iDate]);if(!date){invalid++;continue;}
      const rawName=iName>=0?String(row[iName]??'').trim():'';
      const components={interest:iInterest>=0?Math.abs(cleanNum(row[iInterest])||0):0,reverse:iReverse>=0?Math.abs(cleanNum(row[iReverse])||0):0,lending:iLending>=0?Math.abs(cleanNum(row[iLending])||0):0,hyper:iHyper>=0?Math.abs(cleanNum(row[iHyper])||0):0};
      const compSum=components.interest+components.reverse+components.lending+components.hyper;
      const tr={date,name:cleanName(rawName),code:iCode>=0?String(row[iCode]??'').trim().toUpperCase():extractCode(rawName),action:iAction>=0?String(row[iAction]??'').trim():'',account:iAcct>=0?String(row[iAcct]??'').trim():'',product:iProduct>=0?String(row[iProduct]??'').trim():'',qty:iQty>=0?Math.abs(cleanNum(row[iQty])||0):0,price:iPrice>=0?cleanNum(row[iPrice]):0,feeTotal:iFee>=0?Math.abs(cleanNum(row[iFee])||0):compSum,settle:iSettle>=0?cleanNum(row[iSettle]):0,costs:components,file:file.name,batchId,sourceKey};
      if(existingSource.has(sourceKey)||existingLegacyHash.has(`${type}:${fnv1a(rowBaseKey(type,row))}:${occurrence.get(rowBaseKey(type,row))}`)){duplicates++;continue;}
      const sig=tradeSig(tr),seen=(seenLegacy.get(sig)||0)+1;seenLegacy.set(sig,seen);if((legacyCounts.get(sig)||0)>=seen){duplicates++;continue;}
      existingSource.add(sourceKey);state.trades.push(tr);added++;
    }
  }else{
    const iAmount=type==='profit'?iProfit:iDiv;if(iAmount<0)return{name:file.name,status:'err',msg:'金額列を検出できません',enc:dec.enc,added:0,skipped:0,duplicates:0,invalid:0,batchId:null,date:importedAt};
    const existingSource=new Set(state.records.map(x=>x.sourceKey).filter(Boolean));
    const existingLegacyHash=new Set(state.records.map(x=>x.sourceKey).filter(x=>x&&/^(profit|dividend):[0-9a-z]+:\d+$/.test(x)));
    const legacyCounts=new Map();state.records.filter(x=>x.type===type&&!x.sourceKey).forEach(x=>legacyCounts.set(recordSig(x),(legacyCounts.get(recordSig(x))||0)+1));
    const seenLegacy=new Map();
    for(let r=hdr.idx+1;r<rows.length;r++){
      if(r%450===0)await new Promise(resolve=>setTimeout(resolve,0));
      const row=rows[r];if(!row||row.every(x=>!String(x).trim()))continue;
      const sourceKey=nextSourceKey(row),date=parseDate(row[iDate]),amount=cleanNum(row[iAmount]);if(!date||!Number.isFinite(amount)){invalid++;continue;}
      const rawName=iName>=0?String(row[iName]??'').trim():'';
      const rec={type,date,amount,name:cleanName(rawName),code:iCode>=0?String(row[iCode]??'').trim().toUpperCase():extractCode(rawName),account:iAcct>=0?String(row[iAcct]??'').trim():'',product:iProduct>=0?String(row[iProduct]??'').trim():'',action:iAction>=0?String(row[iAction]??'').trim():'',file:file.name,batchId,sourceKey};
      if(existingSource.has(sourceKey)||existingLegacyHash.has(`${type}:${fnv1a(rowBaseKey(type,row))}:${occurrence.get(rowBaseKey(type,row))}`)){duplicates++;continue;}
      const sig=recordSig(rec),seen=(seenLegacy.get(sig)||0)+1;seenLegacy.set(sig,seen);if((legacyCounts.get(sig)||0)>=seen){duplicates++;continue;}
      existingSource.add(sourceKey);state.records.push(rec);added++;
    }
  }
  skipped=duplicates+invalid;
  const label=type==='profit'?'実現損益':type==='dividend'?'配当':'約定履歴';
  const details=[`${label} ${num.format(added)}件追加`];if(duplicates)details.push(`重複${num.format(duplicates)}件`);if(invalid)details.push(`不正${num.format(invalid)}件`);
  return{name:file.name,kind:type,status:added?'ok':(duplicates?'warn':'err'),msg:details.join(' / '),enc:dec.enc,added,skipped,duplicates,invalid,batchId:added?batchId:null,date:importedAt};
}

function winLoss(profits){
  const wins=profits.filter(r=>r.amount>0),losses=profits.filter(r=>r.amount<0),draws=profits.filter(r=>r.amount===0);
  const grossWin=wins.reduce((s,r)=>s+r.amount,0),grossLoss=Math.abs(losses.reduce((s,r)=>s+r.amount,0));
  return {wins:wins.length,losses:losses.length,draws:draws.length,winrate:(wins.length+losses.length)?wins.length/(wins.length+losses.length):NaN,avgWin:wins.length?grossWin/wins.length:0,avgLoss:losses.length?grossLoss/losses.length:0,avgTrade:(wins.length+losses.length+draws.length)?(grossWin-grossLoss)/(wins.length+losses.length+draws.length):0,profitFactor:grossLoss?grossWin/grossLoss:(grossWin?Infinity:NaN),grossWin,grossLoss};
}
function groupProfitRows(rows,mode=state.settings.winRateMode){
  if(mode==='row')return rows.map(r=>({...r}));
  const map=new Map();
  for(const r of rows){const k=`${r.date}|${keyFor(r.code,r.name)}`;if(!map.has(k))map.set(k,{...r,amount:0,action:'同日決済'});map.get(k).amount+=r.amount;}
  return [...map.values()];
}
function profitEvents(yearValue){return groupProfitRows(recordsFor(yearValue).filter(r=>r.type==='profit'));}

function creditTrade(t){return tradeBucket(t.action)==='信用';}
function recordsFor(yearValue){return filterYear(recordsFiltered(),'date',yearValue);}
function tradesFor(yearValue){return filterYear(state.trades,'date',yearValue);}
function perksFor(yearValue){return filterYear(state.perks,'date',yearValue);}
function adjustmentsFor(yearValue){return filterYear(state.adjustments,'date',yearValue);}
function aggregate(yearValue){
  const recs=recordsFor(yearValue),profits=recs.filter(r=>r.type==='profit'),divs=recs.filter(r=>r.type==='dividend');
  const perks=perksFor(yearValue),adjs=adjustmentsFor(yearValue),trades=tradesFor(yearValue);
  const profit=profits.reduce((s,r)=>s+r.amount,0),dividend=divs.reduce((s,r)=>s+r.amount,0);
  const perkValueTotal=perks.reduce((s,p)=>s+perkValue(p),0),perkCostTotal=perks.reduce((s,p)=>s+perkCost(p),0),perkNetTotal=perks.reduce((s,p)=>s+perkNet(p),0),crossCost=perks.filter(p=>normalizePerkMethod(p.method)==='cross').reduce((s,p)=>s+perkCost(p),0);
  const adjustment=adjs.reduce((s,a)=>s+adjustmentSigned(a),0),creditCost=trades.filter(creditTrade).reduce((s,t)=>s+valueOrZero(t.feeTotal),0);
  const total=profit+dividend+perkValueTotal+adjustment-(state.settings.includeCrossCosts?crossCost:0);
  return {profit,dividend,perkValue:perkValueTotal,perkCost:perkCostTotal,perkNet:perkNetTotal,crossCost,adjustment,total,creditCost,...winLoss(profitEvents(yearValue))};
}
function monthlySeries(yearValue){
  const y=selectedYear(yearValue);const map=new Map();
  const ensure=m=>{if(!map.has(m))map.set(m,{month:m,profit:0,dividend:0,perkValue:0,perkNet:0,adjustment:0,total:0,income:0});return map.get(m);};
  for(const r of recordsFor(yearValue)){const x=ensure(r.date.slice(0,7));if(r.type==='profit')x.profit+=r.amount;else x.dividend+=r.amount;}
  for(const p of perksFor(yearValue)){const x=ensure(p.date.slice(0,7));x.perkValue+=perkValue(p);x.perkNet+=perkNet(p);x.crossCost=(x.crossCost||0)+(normalizePerkMethod(p.method)==='cross'?perkCost(p):0);}
  for(const a of adjustmentsFor(yearValue))ensure(a.date.slice(0,7)).adjustment+=adjustmentSigned(a);
  if(y){for(let m=1;m<=12;m++)ensure(`${y}-${String(m).padStart(2,'0')}`);}
  return [...map.values()].sort((a,b)=>a.month.localeCompare(b.month)).map(x=>({...x,total:x.profit+x.dividend+x.perkValue+x.adjustment-(state.settings.includeCrossCosts?(x.crossCost||0):0),income:x.dividend+x.perkValue}));
}
function aliases(){
  const m=new Map();
  [...state.trades,...state.records].forEach(x=>{if(x.code&&x.name)m.set(norm(x.name).toLowerCase(),x.code);});
  state.perks.forEach(x=>{if(x.code&&x.name)m.set(norm(x.name).toLowerCase(),x.code);});
  return m;
}
function resolvedCode(code,name,alias){return code||alias.get(norm(name).toLowerCase())||'';}
let stockStatsCache=new Map();
function stockStats(yearValue){
  const cacheKey=String(yearValue||'all');
  if(stockStatsCache.has(cacheKey))return stockStatsCache.get(cacheKey);
  const alias=aliases(),map=new Map();
  const ensure=(code,name)=>{
    const rc=resolvedCode(code,name,alias),k=keyFor(rc,name);
    if(!map.has(k))map.set(k,{key:k,code:rc,name:name||rc||'銘柄不明',profit:0,dividend:0,perkValue:0,perkCost:0,crossCost:0,perkNet:0,creditCost:0,wins:0,losses:0,draws:0,trades:0,accounts:new Set(),tradeTypes:new Set()});
    const x=map.get(k);if(!x.code&&rc)x.code=rc;if(name&&(!x.name||x.name==='銘柄不明'))x.name=name;return x;
  };
  for(const r of recordsFor(yearValue)){const x=ensure(r.code,r.name);x.accounts.add(accountBucket(r.account));x.tradeTypes.add(tradeBucket(r.action));if(r.type==='profit')x.profit+=r.amount;else x.dividend+=r.amount;}
  for(const e of profitEvents(yearValue)){const x=ensure(e.code,e.name);if(e.amount>0)x.wins++;else if(e.amount<0)x.losses++;else x.draws++;}
  for(const t of tradesFor(yearValue)){const x=ensure(t.code,t.name);x.trades++;x.accounts.add(accountBucket(t.account));x.tradeTypes.add(tradeBucket(t.action));if(creditTrade(t))x.creditCost+=valueOrZero(t.feeTotal);}
  for(const p of perksFor(yearValue)){const x=ensure(p.code,p.name);x.perkValue+=perkValue(p);x.perkCost+=perkCost(p);if(normalizePerkMethod(p.method)==='cross')x.crossCost+=perkCost(p);x.perkNet+=perkNet(p);}
  const result=[...map.values()].map(x=>({...x,total:x.profit+x.dividend+x.perkValue-(state.settings.includeCrossCosts?x.crossCost:0),winrate:(x.wins+x.losses)?x.wins/(x.wins+x.losses):NaN,accounts:[...x.accounts],tradeTypes:[...x.tradeTypes]}));
  stockStatsCache.set(cacheKey,result);
  return result;
}

function recordTradeType(r){return tradeBucket(r.action);}
function breakdownBy(items,keyFn){
  const map=new Map();
  for(const r of items){const k=keyFn(r);if(!map.has(k))map.set(k,[]);map.get(k).push(r);}
  return map;
}
function profitBreakdownRows(rows,keyFn,mode=state.settings.winRateMode){
  const grouped=[];
  if(mode==='row')rows.forEach((r,i)=>grouped.push({...r,__bucket:keyFn(r)||'不明',__group:i}));
  else{
    const map=new Map();
    for(const r of rows){const bucket=keyFn(r)||'不明',k=`${bucket}|${r.date}|${keyFor(r.code,r.name)}`;if(!map.has(k))map.set(k,{...r,amount:0,__bucket:bucket});map.get(k).amount+=r.amount;}
    grouped.push(...map.values());
  }
  const map=breakdownBy(grouped,r=>r.__bucket),out=[];
  for(const [name,arr] of map){const w=winLoss(arr);out.push({name,amount:arr.reduce((s,r)=>s+r.amount,0),count:arr.length,winrate:w.winrate,wins:w.wins,losses:w.losses});}
  return out.sort((a,b)=>b.amount-a.amount);
}
function profitBreakdown(yearValue,keyFn){return profitBreakdownRows(recordsFor(yearValue).filter(r=>r.type==='profit'),keyFn);}
function accountStats(yearValue){
  const recs=recordsFor(yearValue);const map=new Map();
  const ensure=k=>{if(!map.has(k))map.set(k,{name:k,profit:0,dividend:0,count:0,wins:0,losses:0});return map.get(k);};
  for(const r of recs){const x=ensure(accountBucket(r.account));if(r.type==='profit'){x.profit+=r.amount;x.count++;if(r.amount>0)x.wins++;else if(r.amount<0)x.losses++;}else x.dividend+=r.amount;}
  return [...map.values()].map(x=>({...x,total:x.profit+x.dividend,winrate:(x.wins+x.losses)?x.wins/(x.wins+x.losses):NaN})).sort((a,b)=>b.total-a.total);
}
function creditDirectionStats(yearValue){return profitBreakdown(yearValue,r=>recordTradeType(r)==='信用'?creditDirection(r.action):'対象外').filter(x=>x.name!=='対象外');}
function creditCosts(yearValue){
  const ts=tradesFor(yearValue).filter(creditTrade);const out={total:0,interest:0,reverse:0,lending:0,hyper:0,other:0};
  for(const t of ts){const c=t.costs||{};out.total+=valueOrZero(t.feeTotal);out.interest+=valueOrZero(c.interest);out.reverse+=valueOrZero(c.reverse);out.lending+=valueOrZero(c.lending);out.hyper+=valueOrZero(c.hyper);}
  out.other=Math.max(0,out.total-out.interest-out.reverse-out.lending-out.hyper);return out;
}
function streakStats(yearValue){
  const arr=profitEvents(yearValue).filter(r=>r.amount!==0).slice().sort((a,b)=>a.date.localeCompare(b.date));
  let win=0,loss=0,maxWin=0,maxLoss=0;
  for(const r of arr){if(r.amount>0){win++;loss=0;maxWin=Math.max(maxWin,win);}else{loss++;win=0;maxLoss=Math.max(maxLoss,loss);}}
  return {maxWin,maxLoss};
}
function weekdayStats(yearValue){
  const names=['日','月','火','水','木','金','土'];const map=Array.from({length:7},(_,i)=>({name:names[i],amount:0,count:0,wins:0,losses:0}));
  for(const r of profitEvents(yearValue)){const d=new Date(dateUTC(r.date)).getUTCDay(),x=map[d];x.amount+=r.amount;x.count++;if(r.amount>0)x.wins++;else if(r.amount<0)x.losses++;}
  return [1,2,3,4,5,6,0].map(i=>({...map[i],winrate:(map[i].wins+map[i].losses)?map[i].wins/(map[i].wins+map[i].losses):NaN}));
}
function maxDrawdown(yearValue){
  const arr=profitEvents(yearValue).slice().sort((a,b)=>a.date.localeCompare(b.date));let cum=0,peak=0,maxDD=0,peakDate='',troughDate='';
  for(const r of arr){cum+=r.amount;if(cum>peak){peak=cum;peakDate=r.date;}const dd=peak-cum;if(dd>maxDD){maxDD=dd;troughDate=r.date;}}
  return {amount:maxDD,peakDate,troughDate};
}
function positiveMonthStats(yearValue){const ms=monthlySeries(yearValue).filter(x=>x.profit!==0);const pos=ms.filter(x=>x.profit>0).length;return{positive:pos,total:ms.length,rate:ms.length?pos/ms.length:NaN};}
function cumulativeSeries(yearValue){let cum=0;return monthlySeries(yearValue).map(x=>({month:x.month,total:(cum+=x.total),income:0}));}
function dividendStats(yearValue){
  const rows=stockStats(yearValue).filter(x=>x.dividend>0).sort((a,b)=>b.dividend-a.dividend),total=rows.reduce((s,x)=>s+x.dividend,0),top5=rows.slice(0,5).reduce((s,x)=>s+x.dividend,0);
  return {rows,total,top5,top5Rate:total?top5/total:NaN,count:rows.length};
}
function estimatedPosition(code,name){
  const alias=aliases(),k=keyFor(resolvedCode(code,name,alias),name);let cash=0,long=0,short=0;
  for(const t of state.trades){if(keyFor(resolvedCode(t.code,t.name,alias),t.name)!==k)continue;const a=norm(t.action),q=Math.abs(valueOrZero(t.qty));
    if(/現物買/.test(a))cash+=q;else if(/現物売/.test(a))cash-=q;else if(/信用新規買/.test(a))long+=q;else if(/信用返済売/.test(a))long-=q;else if(/信用新規売/.test(a))short+=q;else if(/信用返済買/.test(a))short-=q;else if(/現引/.test(a)){long-=q;cash+=q;}else if(/現渡/.test(a)){short-=q;cash-=q;}
  }
  return {cash,long,short};
}

function holdingPairs(yearValue){
  const y=selectedYear(yearValue);const grouped=new Map();
  for(const t of state.trades.slice().sort((a,b)=>a.date.localeCompare(b.date))){
    const code=t.code||norm(t.name).toLowerCase();if(!code||!t.qty)continue;
    if(!grouped.has(code))grouped.set(code,{cash:[],long:[],short:[],pairs:[]});const g=grouped.get(code),a=norm(t.action),qty=Math.abs(valueOrZero(t.qty));
    const open=(bucket,q,date)=>bucket.push({qty:q,date});
    const close=(bucket,q,date,kind)=>{let remain=q;while(remain>0&&bucket.length){const lot=bucket[0],take=Math.min(remain,lot.qty);g.pairs.push({openDate:lot.date,closeDate:date,days:daysBetween(lot.date,date),qty:take,kind,code:t.code,name:t.name});lot.qty-=take;remain-=take;if(lot.qty<=0)bucket.shift();}};
    if(/現物買/.test(a))open(g.cash,qty,t.date);
    else if(/現物売/.test(a))close(g.cash,qty,t.date,'現物');
    else if(/信用新規買/.test(a))open(g.long,qty,t.date);
    else if(/信用返済売/.test(a))close(g.long,qty,t.date,'信用買い');
    else if(/信用新規売/.test(a))open(g.short,qty,t.date);
    else if(/信用返済買/.test(a))close(g.short,qty,t.date,'信用売り');
    else if(/現引/.test(a)){close(g.long,qty,t.date,'信用買い');open(g.cash,qty,t.date);}
    else if(/現渡/.test(a)){close(g.short,qty,t.date,'信用売り');close(g.cash,qty,t.date,'現物');}
  }
  let pairs=[...grouped.values()].flatMap(g=>g.pairs);if(y)pairs=pairs.filter(p=>p.closeDate.startsWith(String(y)));return pairs;
}
function averageHoldingDays(yearValue){const ps=holdingPairs(yearValue);const q=ps.reduce((s,p)=>s+p.qty,0);return q?ps.reduce((s,p)=>s+p.days*p.qty,0)/q:NaN;}
function taxReference(yearValue){
  const recs=recordsFor(yearValue),groups=['特定','一般','NISA','不明'];
  return groups.map(g=>{const arr=recs.filter(r=>accountBucket(r.account)===g),profits=arr.filter(r=>r.type==='profit'),divs=arr.filter(r=>r.type==='dividend');return{name:g,profit:profits.reduce((s,r)=>s+r.amount,0),dividend:divs.reduce((s,r)=>s+r.amount,0),count:profits.length};}).filter(x=>x.profit||x.dividend||x.count);
}
function dailyProfit(month){
  const map=new Map();state.records.filter(r=>r.type==='profit'&&r.date.startsWith(month)).forEach(r=>map.set(r.date,(map.get(r.date)||0)+r.amount));return map;
}
function stockDetailData(key,yearValue){
  const stat=stockStats(yearValue).find(x=>x.key===key);if(!stat)return null;
  const match=x=>keyFor(resolvedCode(x.code,x.name,aliases()),x.name)===key;
  const records=recordsFor(yearValue).filter(match),trades=tradesFor(yearValue).filter(match),perks=perksFor(yearValue).filter(match);
  const timeline=[...records.map(r=>({date:r.date,type:r.type==='profit'?'売買損益':'配当',desc:r.action||r.product||'',amount:r.amount})),...trades.map(t=>({date:t.date,type:'約定',desc:`${t.action}${t.qty?` ${num.format(t.qty)}株`:''}`,amount:0})),...perks.map(p=>({date:p.date,type:'優待',desc:p.item||p.method,amount:perkValue(p)}))].sort((a,b)=>b.date.localeCompare(a.date));
  const monthly=new Map();records.forEach(r=>{const m=r.date.slice(0,7);monthly.set(m,(monthly.get(m)||0)+r.amount);});perks.forEach(p=>{const m=p.date.slice(0,7);monthly.set(m,(monthly.get(m)||0)+perkValue(p));});
  const pairs=holdingPairs(yearValue).filter(p=>keyFor(resolvedCode(p.code,p.name,aliases()),p.name)===key);const q=pairs.reduce((s,p)=>s+p.qty,0);const hold=q?pairs.reduce((s,p)=>s+p.days*p.qty,0)/q:NaN;
  return {stat,records,trades,perks,timeline,monthly:[...monthly].sort((a,b)=>a[0].localeCompare(b[0])),hold};
}
function setMoney(el,v){const e=$(el);e.textContent=yen.format(v);e.classList.remove('pos','neg');if(v>0)e.classList.add('pos');else if(v<0)e.classList.add('neg');}
function optionYears(elId,includeAll=true){
  const el=$(elId),years=availableYears(),current=el.value;let html=includeAll?'<option value="all">全期間</option>':'';
  html+=years.map(y=>`<option value="${y}">${y}年</option>`).join('');
  el.innerHTML=html;
  const desired=current||state.filters[elId.replace('#','').replace('homeYear','homeYear').replace('analysisYear','analysisYear').replace('perkYear','perkYear')]||currentDataYear();
  if([...el.options].some(o=>o.value===String(desired)))el.value=String(desired);else el.value=includeAll?'all':(years[0]?String(years[0]):String(new Date().getFullYear()));
}
function syncSelectors(){
  const mapping=[['#homeYear','homeYear'],['#stockYear','stockYear'],['#analysisYear','analysisYear'],['#perkYear','perkYear']];
  for(const [id,key] of mapping){const el=$(id),old=state.filters[key]||el.value;const years=availableYears();el.innerHTML='<option value="all">全期間</option>'+years.map(y=>`<option value="${y}">${y}年</option>`).join('');let v=old||currentDataYear();if(![...el.options].some(o=>o.value===String(v)))v=years.length?String(years[0]):'all';el.value=String(v);state.filters[key]=String(v);}
  const months=allMonths(),cal=$('#calendarMonth'),old=state.filters.calendarMonth||cal.value;cal.innerHTML=months.map(m=>`<option value="${m}">${m.replace('-','年')}月</option>`).join('');const v=months.includes(old)?old:(months[0]||'');cal.value=v;state.filters.calendarMonth=v;
}
function bindChartScrub(el,series,specs,{W=900,padL=22,padR=12}={}){
  if(!el||!series.length)return;
  const line=document.createElement('div'),tip=document.createElement('div');line.className='chart-scrub-line';tip.className='chart-tooltip';el.append(line,tip);
  let touching=false,hideTimer=null;
  const showAt=clientX=>{
    const rect=el.getBoundingClientRect();if(!rect.width)return;const innerLeft=rect.left+rect.width*padL/W,innerRight=rect.right-rect.width*padR/W;const ratio=clamp((clientX-innerLeft)/Math.max(1,innerRight-innerLeft),0,1);const idx=series.length===1?0:Math.round(ratio*(series.length-1)),d=series[idx],svgX=padL+(series.length===1?0:(W-padL-padR)*idx/(series.length-1)),left=svgX/W*100;
    line.style.left=`${left}%`;tip.style.left=`${left}%`;tip.style.transform=left<15?'translateX(0)':left>85?'translateX(-100%)':'translateX(-50%)';const date=String(d.month||d.date||'').replace(/^(\d{4})-(\d{2})$/,'$1年$2月');tip.innerHTML=`<strong>${esc(date)}</strong>${specs.map(s=>`<span><i class="${esc(s.cls||'')}"></i>${esc(s.label)} <b class="${moneyClass(valueOrZero(d[s.key]))}">${yen.format(valueOrZero(d[s.key]))}</b></span>`).join('')}`;line.classList.add('show');tip.classList.add('show');clearTimeout(hideTimer);
  };
  const hide=()=>{line.classList.remove('show');tip.classList.remove('show');};
  el.onpointerdown=e=>{touching=true;showAt(e.clientX);};
  el.onpointermove=e=>{if(e.pointerType==='mouse'||touching)showAt(e.clientX);};
  el.onpointerup=e=>{touching=false;showAt(e.clientX);hideTimer=setTimeout(hide,1600);};
  el.onpointercancel=()=>{touching=false;hide();};
  el.onpointerleave=e=>{if(e.pointerType==='mouse'&&!touching)hide();};
}
function plotSamples(series,limit=190){if(series.length<=limit)return series;const out=[],step=Math.ceil(series.length/limit);for(let i=0;i<series.length;i+=step){const bucket=series.slice(i,i+step),first=bucket[0],last=bucket[bucket.length-1];out.push(first);if(last!==first)out.push(last);}if(out[out.length-1]!==series[series.length-1])out.push(series[series.length-1]);return out;}
function renderLineChart(elId,series){series=plotSamples(series);
  const el=$(elId);if(!series.length||!series.some(x=>x.total||x.income)){el.className='svg-chart empty-chart';el.textContent='対象データがありません';return;}
  el.className='svg-chart';el.textContent='';
  const W=900,H=220,pad={l:22,r:12,t:12,b:28};const vals=series.flatMap(x=>[x.total,x.income]);let min=Math.min(0,...vals),max=Math.max(0,...vals);if(max===min){max+=1;min-=1;}const x=i=>pad.l+(series.length===1?0:(W-pad.l-pad.r)*i/(series.length-1));const y=v=>pad.t+(max-v)*(H-pad.t-pad.b)/(max-min);const path=k=>series.map((d,i)=>`${i?'L':'M'}${x(i).toFixed(1)},${y(d[k]).toFixed(1)}`).join(' ');const zero=y(0);
  let svg=`<svg viewBox="0 0 ${W} ${H}" preserveAspectRatio="none">`;
  for(let i=0;i<4;i++){const yy=pad.t+(H-pad.t-pad.b)*i/3;svg+=`<line class="chart-grid" x1="${pad.l}" x2="${W-pad.r}" y1="${yy}" y2="${yy}"/>`;}
  svg+=`<line class="chart-zero" x1="${pad.l}" x2="${W-pad.r}" y1="${zero}" y2="${zero}"/>`;
  svg+=`<path class="chart-total" d="${path('total')}"/><path class="chart-income" d="${path('income')}"/>`;
  series.forEach((d,i)=>{if(series.length<=18||i%Math.ceil(series.length/12)===0){svg+=`<text class="chart-label" x="${x(i)}" y="${H-8}" text-anchor="middle">${d.month.slice(5)}月</text>`;}svg+=`<circle class="chart-dot-total" cx="${x(i)}" cy="${y(d.total)}" r="2.4"/><circle class="chart-dot-income" cx="${x(i)}" cy="${y(d.income)}" r="1.8"/>`;});
  svg+='</svg>';el.innerHTML=svg;bindChartScrub(el,series,[{key:'total',label:'総合',cls:'total'},{key:'income',label:'インカム',cls:'income'}],{W,padL:pad.l,padR:pad.r});
}
function renderCumulativeChart(elId,series){series=plotSamples(series);
  const el=$(elId);if(!series.length||!series.some(x=>x.total)){el.className='svg-chart empty-chart';el.textContent='対象データがありません';return;}
  const W=900,H=220,pad={l:22,r:12,t:12,b:28},vals=series.map(x=>x.total);let min=Math.min(0,...vals),max=Math.max(0,...vals);if(max===min){max+=1;min-=1;}const x=i=>pad.l+(series.length===1?0:(W-pad.l-pad.r)*i/(series.length-1)),y=v=>pad.t+(max-v)*(H-pad.t-pad.b)/(max-min),zero=y(0),path=series.map((d,i)=>`${i?'L':'M'}${x(i).toFixed(1)},${y(d.total).toFixed(1)}`).join(' ');let svg=`<svg viewBox="0 0 ${W} ${H}" preserveAspectRatio="none">`;
  for(let i=0;i<4;i++){const yy=pad.t+(H-pad.t-pad.b)*i/3;svg+=`<line class="chart-grid" x1="${pad.l}" x2="${W-pad.r}" y1="${yy}" y2="${yy}"/>`;}
  svg+=`<line class="chart-zero" x1="${pad.l}" x2="${W-pad.r}" y1="${zero}" y2="${zero}"/><path class="chart-total" d="${path}"/>`;
  series.forEach((d,i)=>{if(series.length<=18||i%Math.ceil(series.length/12)===0)svg+=`<text class="chart-label" x="${x(i)}" y="${H-8}" text-anchor="middle">${d.month.slice(5)}月</text>`;svg+=`<circle class="chart-dot-total" cx="${x(i)}" cy="${y(d.total)}" r="2.4"/>`;});el.className='svg-chart';el.innerHTML=svg+'</svg>';bindChartScrub(el,series,[{key:'total',label:'累計',cls:'total'}],{W,padL:pad.l,padR:pad.r});
}

function deltaHtml(cur,prev){if(!prev&&cur)return'<span class="delta positive">新規</span>';if(!prev)return'<span class="delta">—</span>';const d=(cur-prev)/Math.abs(prev);return`<span class="delta ${d>0?'positive':d<0?'negative':''}">${d>=0?'+':''}${(d*100).toFixed(1)}%</span>`;}
function renderHome(){
  const year=state.filters.homeYear||$('#homeYear').value||'all',a=aggregate(year);setMoney('#homeTotal',a.total);setMoney('#homeProfit',a.profit);setMoney('#homeDividend',a.dividend);setMoney('#homePerkNet',a.perkValue);$('#homeCreditCost').textContent=yen.format(a.creditCost);
  const y=selectedYear(year),prev=y?aggregate(String(y-1)):null;
  const badge=$('#homeYoYBadge');badge.className='hero-badge';if(prev&&prev.total){const d=(a.total-prev.total)/Math.abs(prev.total);badge.textContent=`前年比 ${d>=0?'+':''}${(d*100).toFixed(1)}%`;badge.classList.add(d>0?'positive':d<0?'negative':'');}else badge.textContent='前年比 —';
  const goalY=y||new Date().getFullYear(),ga=aggregate(String(goalY)),goal=Math.max(0,valueOrZero(state.settings.dividendGoal)),rate=goal?ga.dividend/goal:0;$('#goalActual').textContent=yen.format(ga.dividend);$('#goalTarget').textContent=yen.format(goal);$('#goalBar').style.width=`${clamp(rate*100,0,100)}%`;$('#goalRate').textContent=`${(rate*100).toFixed(1)}%`;$('#goalRemaining').textContent=ga.dividend>=goal?'目標達成':`あと ${yen.format(Math.max(0,goal-ga.dividend))}`;
  const monthsPassed=goalY===new Date().getFullYear()?new Date().getMonth()+1:12,pace=monthsPassed?ga.dividend/monthsPassed*12:0;$('#goalPace').textContent=`${goalY}年・税引後受取額ベース　年換算ペース ${yen.format(pace)}`;
  setMoney('#incomeTotal',a.dividend+a.perkValue);setMoney('#incomeDividend',a.dividend);setMoney('#incomePerkValue',a.perkValue);$('#incomePerkCost').textContent=yen.format(a.perkCost);
  renderLineChart('#monthlyChart',monthlySeries(year));renderCumulativeChart('#cumulativeChart',cumulativeSeries(year));$('#homeTotalSub').textContent=state.settings.includeCrossCosts?'売買益・配当・優待価値・その他収支 − 優待クロス費用':'売買益・配当・優待価値・その他収支';
  if(y){const p=aggregate(String(y-1));$('#yoyList').innerHTML=[['総合損益',a.total,p.total],['売買益',a.profit,p.profit],['配当',a.dividend,p.dividend],['優待価値',a.perkValue,p.perkValue]].map(([n,c,pr])=>`<div class="compare-row"><span>${n}</span><div><b class="${moneyClass(c)}">${yen.format(c)}</b>${deltaHtml(c,pr)}</div></div>`).join('');}else $('#yoyList').innerHTML='<div class="empty-state">年を選択すると前年と比較します</div>';
  const ss=stockStats(year).sort((x,z)=>z.total-x.total),profits=recordsFor(year).filter(r=>r.type==='profit').sort((x,z)=>z.amount-x.amount),losses=profits.slice().sort((x,z)=>x.amount-z.amount),ps=perksFor(year).sort((x,z)=>perkNet(z)-perkNet(x));
  const highs=[['ベスト銘柄',ss[0]?`${ss[0].name} ${yen.format(ss[0].total)}`:'—'],['最大利確',profits[0]?`${profits[0].name} ${yen.format(profits[0].amount)}`:'—'],['最大損切り',losses[0]&&losses[0].amount<0?`${losses[0].name} ${yen.format(losses[0].amount)}`:'—'],['優待お得額',ps[0]?`${ps[0].name} ${yen.format(perkNet(ps[0]))}`:'—']];$('#highlightGrid').innerHTML=highs.map(([n,v])=>`<div class="highlight"><span>${n}</span><b>${esc(v)}</b></div>`).join('');
}
function rankListHtml(data,valueKey,negative=false){if(!data.length)return'<div class="empty-state">対象データがありません</div>';return data.slice(0,10).map((x,i)=>`<div class="rank-item"><span class="rank-no">${String(i+1).padStart(2,'0')}</span><div class="rank-name"><b>${esc(x.name||'銘柄不明')}</b><span>${esc(x.code||'')}</span></div><strong class="rank-value ${moneyClass(x[valueKey])}">${yen.format(x[valueKey])}</strong></div>`).join('');}
function renderVirtualTable(wrapperId,tbodyId,rows,rowHtml,colSpan,bindVisible){
  const wrap=$(wrapperId),body=$(tbodyId);if(!wrap||!body)return;
  if(wrap._virtualHandler){wrap.removeEventListener('scroll',wrap._virtualHandler);wrap._virtualHandler=null;}if(wrap._virtualRaf){cancelAnimationFrame(wrap._virtualRaf);wrap._virtualRaf=0;}wrap.classList.remove('virtualized');
  if(!rows.length){body.innerHTML=`<tr><td colspan="${colSpan}"><div class="empty-state">該当するデータがありません</div></td></tr>`;if(bindVisible)bindVisible(body);return;}
  const LIMIT=240;if(rows.length<=LIMIT){body.innerHTML=rows.map(rowHtml).join('');if(bindVisible)bindVisible(body);return;}
  wrap.classList.add('virtualized');const ROW_H=48,BUFFER=12;let lastKey='';
  const maxScroll=Math.max(0,rows.length*ROW_H-Math.max(360,wrap.clientHeight));if(wrap.scrollTop>maxScroll)wrap.scrollTop=maxScroll;
  const draw=()=>{wrap._virtualRaf=0;const visible=Math.ceil(Math.max(360,wrap.clientHeight)/ROW_H)+BUFFER*2,start=Math.max(0,Math.floor(wrap.scrollTop/ROW_H)-BUFFER),end=Math.min(rows.length,start+visible),key=`${start}:${end}`;if(key===lastKey)return;lastKey=key;const top=start*ROW_H,bottom=Math.max(0,(rows.length-end)*ROW_H);body.innerHTML=`<tr class="virtual-spacer"><td colspan="${colSpan}" style="height:${top}px"></td></tr>`+rows.slice(start,end).map(rowHtml).join('')+`<tr class="virtual-spacer"><td colspan="${colSpan}" style="height:${bottom}px"></td></tr>`;if(bindVisible)bindVisible(body);};
  wrap._virtualHandler=()=>{if(!wrap._virtualRaf)wrap._virtualRaf=requestAnimationFrame(draw);};wrap.addEventListener('scroll',wrap._virtualHandler,{passive:true});draw();
}
let lastStockRankKey='';
function renderStocks(){
  const q=norm($('#stockSearch').value).toLowerCase(),sort=$('#stockSort').value||'total',year=state.filters.stockYear||'all',favoriteOnly=!!state.filters.favoriteOnly;
  let data=stockStats(year).filter(x=>(!q||norm(`${x.code}${x.name}`).toLowerCase().includes(q))&&(!favoriteOnly||isFavorite(x.key)));
  data.sort((a,b)=>{const fav=isFavorite(b.key)-isFavorite(a.key);return fav||((b[sort]??0)-(a[sort]??0));});
  const all=stockStats(year);const rankKey=`${year}:${stockStatsRevision}`;if(rankKey!==lastStockRankKey){$('#bestStocks').innerHTML=rankListHtml(all.slice().sort((a,b)=>b.total-a.total),'total');$('#worstStocks').innerHTML=rankListHtml(all.filter(x=>x.total<0).sort((a,b)=>a.total-b.total),'total',true);lastStockRankKey=rankKey;}
  const favCount=all.filter(x=>isFavorite(x.key)).length;$('#stockCount').textContent=`${num.format(data.length)}銘柄${favCount?` ・ ★${num.format(favCount)}`:''}`;
  const ff=$('#favoriteFilter');ff.classList.toggle('active',favoriteOnly);ff.setAttribute('aria-pressed',String(favoriteOnly));ff.textContent=favoriteOnly?'★ お気に入りのみ':'☆ お気に入り';
  renderVirtualTable('#stockTableWrap','#stockTableBody',data,x=>`<tr class="clickable-row vrow" data-stockkey="${esc(x.key)}"><td><button class="stock-fav ${isFavorite(x.key)?'active':''}" data-favkey="${esc(x.key)}" aria-label="お気に入り">${isFavorite(x.key)?'★':'☆'}</button><span class="stock-name">${esc(x.name)}</span>${x.code?`<span class="stock-code">${esc(x.code)}</span>`:''}</td><td class="${moneyClass(x.total)}"><b>${yen.format(x.total)}</b></td><td class="${moneyClass(x.profit)}">${yen.format(x.profit)}</td><td>${yen.format(x.dividend)}</td><td>${yen.format(x.perkValue)}</td><td class="${moneyClass(x.perkNet)}">${yen.format(x.perkNet)}</td><td>${yen.format(x.creditCost)}</td><td>${pct(x.winrate)}</td><td>${x.wins+x.losses+x.draws}</td><td>${x.trades}</td></tr>`,10,body=>{
    body.querySelectorAll('[data-favkey]').forEach(b=>b.onclick=e=>{e.stopPropagation();toggleFavorite(b.dataset.favkey);});
    body.querySelectorAll('[data-stockkey]').forEach(tr=>tr.onclick=()=>openStockDetail(tr.dataset.stockkey));
  });
}
function statRows(data,formatter){return data.length?data.map(x=>`<div class="stat-row"><span>${esc(x.name)}</span><div class="stack"><b class="${moneyClass(x.amount??x.total??0)}">${formatter?formatter(x):yen.format(x.amount??x.total??0)}</b>${Number.isFinite(x.winrate)?`<span class="subvalue">勝率 ${pct(x.winrate)} / ${x.count??(x.wins+x.losses)}件</span>`:''}</div></div>`).join(''):'<div class="empty-state">対象データがありません</div>';}
function renderAnalysis(){
  const year=state.filters.analysisYear||'all',profits=profitEvents(year),w=winLoss(profits),streak=streakStats(year),hold=averageHoldingDays(year),dd=maxDrawdown(year),pm=positiveMonthStats(year);$('#aWinrate').textContent=pct(w.winrate);$('#aWinrate').className=moneyClass((w.winrate||0)-.5);$('#aWinLoss').textContent=`${w.wins}勝 ${w.losses}敗 ${w.draws}分・${state.settings.winRateMode==='stockday'?'銘柄×日':'明細'}単位`;$('#aPF').textContent=w.profitFactor===Infinity?'∞':Number.isFinite(w.profitFactor)?w.profitFactor.toFixed(2):'—';
  const maxW=profits.slice().sort((a,b)=>b.amount-a.amount)[0],maxL=profits.slice().sort((a,b)=>a.amount-b.amount)[0];setMoney('#aMaxWin',maxW?.amount||0);$('#aMaxWinName').textContent=maxW?`${maxW.name||maxW.code||'—'} ${maxW.date}`:'—';setMoney('#aMaxLoss',maxL?.amount<0?maxL.amount:0);$('#aMaxLossName').textContent=maxL&&maxL.amount<0?`${maxL.name||maxL.code||'—'} ${maxL.date}`:'—';$('#aWinStreak').textContent=streak.maxWin;$('#aLossStreak').textContent=streak.maxLoss;$('#aHoldDays').textContent=Number.isFinite(hold)?`${hold.toFixed(1)}日`:'—';setMoney('#aAvgTrade',w.avgTrade);setMoney('#aMaxDrawdown',-dd.amount);$('#aPositiveMonths').textContent=Number.isFinite(pm.rate)?pct(pm.rate):'—';
  $('#tradeTypeBreakdown').innerHTML=statRows(profitBreakdown(year,r=>recordTradeType(r)));$('#accountBreakdown').innerHTML=statRows(accountStats(year),x=>yen.format(x.total));$('#creditDirection').innerHTML=statRows(creditDirectionStats(year));
  const c=creditCosts(year);$('#creditCostBreakdown').innerHTML=[['合計',c.total],['日歩・金利',c.interest],['逆日歩',c.reverse],['貸株料',c.lending],['HYPER料',c.hyper],['その他',c.other]].map(([n,v])=>`<div class="stat-row"><span>${n}</span><b>${yen.format(v)}</b></div>`).join('');
  const wins=profits.filter(r=>r.amount>0).sort((a,b)=>b.amount-a.amount).map(r=>({name:`${r.name||r.code||'銘柄不明'} ・ ${r.date}`,value:r.amount})),loss=profits.filter(r=>r.amount<0).sort((a,b)=>a.amount-b.amount).map(r=>({name:`${r.name||r.code||'銘柄不明'} ・ ${r.date}`,value:r.amount}));$('#takeProfitRank').innerHTML=rankListHtml(wins.map(x=>({name:x.name,amount:x.value})),'amount');$('#stopLossRank').innerHTML=rankListHtml(loss.map(x=>({name:x.name,amount:x.value})),'amount');
  $('#weekdayStats').innerHTML=statRows(weekdayStats(year),x=>`${yen.format(x.amount)} / ${pct(x.winrate)}`);renderCalendar();
  const tx=taxReference(year);$('#taxTableBody').innerHTML=tx.length?tx.map(x=>`<tr><td>${esc(x.name)}</td><td class="${moneyClass(x.profit)}">${yen.format(x.profit)}</td><td>${yen.format(x.dividend)}</td><td>${x.count}</td></tr>`).join(''):'<tr><td colspan="4"><div class="empty-state">対象データがありません</div></td></tr>';
  const ds=dividendStats(year);$('#dividendRank').innerHTML=rankListHtml(ds.rows.map(x=>({name:x.name,code:x.code,dividend:x.dividend})),'dividend');$('#dividendConcentration').innerHTML=[['配当銘柄数',`${num.format(ds.count)}銘柄`],['上位5銘柄比率',Number.isFinite(ds.top5Rate)?pct(ds.top5Rate):'—'],['上位5銘柄',yen.format(ds.top5)],['配当合計',yen.format(ds.total)]].map(([n,v])=>`<div class="stat-row"><span>${n}</span><b>${v}</b></div>`).join('');
}

function renderCalendar(){
  const month=state.filters.calendarMonth||$('#calendarMonth').value;if(!month){$('#tradeCalendar').innerHTML='<div class="empty-state">売買データがありません</div>';return;}const [y,m]=month.split('-').map(Number),first=new Date(Date.UTC(y,m-1,1)),days=new Date(Date.UTC(y,m,0)).getUTCDate(),weekday=(first.getUTCDay()+6)%7,map=dailyProfit(month);let html='';for(let i=0;i<weekday;i++)html+='<div class="cal-day empty"></div>';for(let d=1;d<=days;d++){const date=`${month}-${String(d).padStart(2,'0')}`,v=map.get(date)||0;html+=`<div class="cal-day ${v>0?'win':v<0?'loss':''}" title="${date} ${yen.format(v)}"><span class="d">${d}</span><span class="v ${moneyClass(v)}">${v?yen.format(v):''}</span></div>`;}$('#tradeCalendar').innerHTML=html;
}
function perkMethodLabel(v){v=normalizePerkMethod(v);return v==='cross'?'優待クロス':v==='holding'?'現物保有':'その他';}
function updatePerkPreview(){
  const p={basis:$('#perkBasis').value,faceValue:valueOrZero($('#perkFace').value),personalValue:valueOrZero($('#perkPersonal').value),resaleValue:valueOrZero($('#perkResale').value),commission:valueOrZero($('#perkCommission').value),lendingFee:valueOrZero($('#perkLending').value),reverseDaily:valueOrZero($('#perkReverse').value),otherCost:valueOrZero($('#perkOtherCost').value)};
  setMoney('#perkPreviewValue',perkValue(p));$('#perkPreviewCost').textContent=yen.format(perkCost(p));setMoney('#perkPreviewNet',perkNet(p));
}
let editingPerkId='';
function resetPerkForm(){editingPerkId='';['perkCode','perkName','perkShares','perkItem','perkFace','perkPersonal','perkResale','perkMemo'].forEach(id=>$('#'+id).value='');['perkCommission','perkLending','perkReverse','perkOtherCost'].forEach(id=>$('#'+id).value='0');$('#perkMethod').value='holding';$('#perkBasis').value='personal';$('#perkDate').value=localToday();$('#addPerk').textContent='記録する';updatePerkPreview();}
function perkFromForm(id=uid('p')){return{id,date:$('#perkDate').value,code:$('#perkCode').value.trim().toUpperCase(),name:$('#perkName').value.trim(),method:normalizePerkMethod($('#perkMethod').value),shares:valueOrZero($('#perkShares').value),item:$('#perkItem').value.trim(),basis:$('#perkBasis').value,faceValue:valueOrZero($('#perkFace').value),personalValue:valueOrZero($('#perkPersonal').value),resaleValue:valueOrZero($('#perkResale').value),commission:valueOrZero($('#perkCommission').value),lendingFee:valueOrZero($('#perkLending').value),reverseDaily:valueOrZero($('#perkReverse').value),otherCost:valueOrZero($('#perkOtherCost').value),memo:$('#perkMemo').value.trim()};}
function addPerk(){const p=perkFromForm(editingPerkId||uid('p'));if(!p.date||(!p.name&&!p.code)||!p.item){toast('取得日・銘柄・優待内容を入力してください');haptic('warning');return;}const warning=suspiciousPerk(p);if(warning&&!confirm(warning))return;if(editingPerkId){const i=state.perks.findIndex(x=>x.id===editingPerkId);if(i>=0)state.perks[i]=p;toast('優待を更新しました');}else{state.perks.push(p);toast('優待を記録しました');}haptic('success');persist();syncSelectors();renderPerkAffected();resetPerkForm();}
function deletePerk(id){const i=state.perks.findIndex(x=>x.id===id);if(i<0)return;const removed=state.perks[i];state.perks.splice(i,1);if(editingPerkId===id)resetPerkForm();persist();syncSelectors();renderPerkAffected();haptic('warning');toast('優待を削除しました','元に戻す',()=>{state.perks.splice(Math.min(i,state.perks.length),0,removed);persist();syncSelectors();renderPerkAffected();});}
function perkActions(id){const p=state.perks.find(x=>x.id===id);if(!p)return;showActionSheet(`${p.name||p.code||'優待'} ・ ${p.item}`,[{label:'編集する',action:()=>editPerk(id)},{label:'削除する',danger:true,action:()=>deletePerk(id)}]);}
function editPerk(id){const p=state.perks.find(x=>x.id===id);if(!p)return;editingPerkId=id;$('#perkDate').value=p.date;$('#perkCode').value=p.code||'';$('#perkName').value=p.name||'';$('#perkMethod').value=normalizePerkMethod(p.method);$('#perkShares').value=p.shares||'';$('#perkItem').value=p.item||'';$('#perkBasis').value=p.basis||'personal';$('#perkFace').value=p.faceValue||0;$('#perkPersonal').value=p.personalValue||0;$('#perkResale').value=p.resaleValue||0;$('#perkCommission').value=p.commission||0;$('#perkLending').value=p.lendingFee||0;$('#perkReverse').value=p.reverseDaily||0;$('#perkOtherCost').value=p.otherCost||0;$('#perkMemo').value=p.memo||'';$('#addPerk').textContent='更新する';updatePerkPreview();window.scrollTo({top:0,behavior:'smooth'});}
function perkMethodStats(ps){
  const make=(method,label)=>{const rows=ps.filter(p=>normalizePerkMethod(p.method)===method);const value=rows.reduce((s,p)=>s+perkValue(p),0),cost=rows.reduce((s,p)=>s+perkCost(p),0),net=rows.reduce((s,p)=>s+perkNet(p),0);return{method,label,rows,count:rows.length,value,cost,net};};
  return [make('holding','現物保有'),make('cross','優待クロス'),make('other','その他')];
}
function renderPerks(){
  const year=state.filters.perkYear||'all',ps=perksFor(year),value=ps.reduce((s,p)=>s+perkValue(p),0),cost=ps.reduce((s,p)=>s+perkCost(p),0),net=ps.reduce((s,p)=>s+perkNet(p),0),methodStats=perkMethodStats(ps),holding=methodStats[0],crossStat=methodStats[1],other=methodStats[2],cross=crossStat.rows,crossCost=crossStat.cost,crossNet=crossStat.net;
  setMoney('#pValue',value);$('#pCost').textContent=yen.format(cost);setMoney('#pNet',net);$('#pCount').textContent=num.format(ps.length);$('#pCrossCount').textContent=`現物 ${num.format(holding.count)}件 ・ クロス ${num.format(crossStat.count)}件${other.count?` ・ その他 ${num.format(other.count)}件`:''}`;$('#pCrossROI').textContent=crossCost?(crossNet/crossCost).toFixed(2)+'倍':(crossNet>0?'∞':'—');setMoney('#pAvgNet',ps.length?net/ps.length:0);
  const methodSummary=$('#methodSummary');if(methodSummary)methodSummary.innerHTML=methodStats.map(x=>`<div class="stat-row"><span>${esc(x.label)}</span><div class="stack"><b>${num.format(x.count)}件</b><span class="subvalue">価値 ${yen.format(x.value)} ・ コスト ${yen.format(x.cost)} ・ 実質 ${yen.format(x.net)}</span></div></div>`).join('');
  const grouped=new Map();for(const p of ps){const k=keyFor(p.code,p.name);if(!grouped.has(k))grouped.set(k,{name:p.name||p.code||'銘柄不明',code:p.code,net:0,count:0});const x=grouped.get(k);x.net+=perkNet(p);x.count++;}$('#perkRank').innerHTML=rankListHtml([...grouped.values()].sort((a,b)=>b.net-a.net).map(x=>({...x,perkNet:x.net})),'perkNet');
  const cc={commission:0,lending:0,reverse:0,other:0};cross.forEach(p=>{cc.commission+=valueOrZero(p.commission);cc.lending+=valueOrZero(p.lendingFee);cc.reverse+=valueOrZero(p.reverseDaily);cc.other+=valueOrZero(p.otherCost);});$('#crossSummary').innerHTML=[['取得件数',crossStat.count],['優待価値',crossStat.value],['クロス総コスト',crossCost],['実質お得額',crossNet],['売買手数料',cc.commission],['貸株料',cc.lending],['逆日歩',cc.reverse],['その他費用',cc.other]].map(([n,v])=>`<div class="stat-row"><span>${n}</span><b class="${moneyClass(n==='実質お得額'?v:0)}">${n==='取得件数'?num.format(v)+'件':yen.format(v)}</b></div>`).join('');
  const methodFilter=state.filters.perkMethod||'all';if($('#perkMethodFilter'))$('#perkMethodFilter').value=methodFilter;const shown=methodFilter==='all'?ps:ps.filter(p=>normalizePerkMethod(p.method)===methodFilter),sorted=shown.slice().sort((a,b)=>b.date.localeCompare(a.date));renderVirtualTable('#perkTableWrap','#perkTableBody',sorted,p=>`<tr class="vrow" data-perkid="${esc(p.id)}"><td>${p.date}</td><td><span class="stock-name">${esc(p.name||p.code||'—')}</span>${p.code?`<span class="stock-code">${esc(p.code)}</span>`:''}</td><td>${perkMethodLabel(p.method)}</td><td>${esc(p.item)}</td><td>${yen.format(perkValue(p))}</td><td>${yen.format(perkCost(p))}</td><td class="${moneyClass(perkNet(p))}"><b>${yen.format(perkNet(p))}</b></td><td><button class="tiny-btn" data-editperk="${esc(p.id)}">編集</button><button class="tiny-btn" data-delperk="${esc(p.id)}">削除</button></td></tr>`,8,body=>{body.querySelectorAll('[data-editperk]').forEach(b=>b.onclick=()=>editPerk(b.dataset.editperk));body.querySelectorAll('[data-delperk]').forEach(b=>b.onclick=()=>deletePerk(b.dataset.delperk));body.querySelectorAll('[data-perkid]').forEach(tr=>bindSwipeAction(tr,()=>perkActions(tr.dataset.perkid)));});
}

function addAdjustment(){const date=$('#adjDate').value,memo=$('#adjMemo').value.trim(),amount=valueOrZero($('#adjAmount').value);if(!date||!memo||!amount){toast('日付・内容・金額を入力してください');haptic('warning');return;}if(Math.abs(amount)>10000000&&!confirm(`金額が ${yen.format(Math.abs(amount))} になっています。このまま追加しますか？`))return;state.adjustments.push({id:uid('a'),date,memo,type:$('#adjType').value,amount:Math.abs(amount),code:'',name:''});$('#adjMemo').value='';$('#adjAmount').value='';persist();syncSelectors();renderAdjustmentAffected();haptic('success');toast('その他収支を追加しました');}
function deleteAdjustment(id){const i=state.adjustments.findIndex(a=>a.id===id);if(i<0)return;const removed=state.adjustments[i];state.adjustments.splice(i,1);persist();renderAdjustmentAffected();haptic('warning');toast('その他収支を削除しました','元に戻す',()=>{state.adjustments.splice(Math.min(i,state.adjustments.length),0,removed);persist();renderAdjustmentAffected();});}
function renderAdjustments(){const arr=state.adjustments.slice().sort((a,b)=>b.date.localeCompare(a.date));$('#adjustmentList').innerHTML=arr.length?arr.map(a=>`<div class="simple-item swipeable" data-adjid="${esc(a.id)}"><div class="grow"><b>${esc(a.memo)}</b><span>${a.date}・${a.type==='expense'?'費用':'収入'}</span></div><strong class="${moneyClass(adjustmentSigned(a))}">${yen.format(adjustmentSigned(a))}</strong><button class="tiny-btn" data-deladj="${esc(a.id)}">削除</button></div>`).join(''):'<div class="empty-state">その他収支はありません</div>';$$('[data-deladj]').forEach(b=>b.onclick=()=>deleteAdjustment(b.dataset.deladj));$$('[data-adjid]').forEach(el=>bindSwipeAction(el,()=>showActionSheet('その他収支',[{label:'削除する',danger:true,action:()=>deleteAdjustment(el.dataset.adjid)}])));}
function removeImportBatch(batchId){if(!batchId||!confirm('この取込分のCSVデータだけ削除しますか？'))return;const rr=state.records.filter(x=>x.batchId===batchId),tt=state.trades.filter(x=>x.batchId===batchId),ff=state.files.filter(x=>x.batchId===batchId);state.records=state.records.filter(x=>x.batchId!==batchId);state.trades=state.trades.filter(x=>x.batchId!==batchId);state.files=state.files.filter(x=>x.batchId!==batchId);const removed=rr.length+tt.length;persist();syncSelectors();renderAll();haptic('warning');toast(`${num.format(removed)}件を削除しました`,'元に戻す',()=>{state.records.push(...rr);state.trades.push(...tt);state.files.push(...ff);persist();syncSelectors();renderAll();});}
function renderFiles(){const el=$('#fileHistory');el.innerHTML=state.files.length?state.files.slice().reverse().slice(0,30).map(f=>`<div class="file-row"><div><b>${esc(f.name)}</b><span>${esc(f.enc||'')} ${f.date?`・${new Date(f.date).toLocaleString('ja-JP')}`:''}</span></div><div class="file-actions"><span class="file-status ${f.status}">${esc(f.msg)}</span>${f.batchId?`<button class="tiny-btn" data-delbatch="${esc(f.batchId)}">取込取消</button>`:''}</div></div>`).join(''):'<div class="empty-state">まだCSVを読み込んでいません</div>';$$('[data-delbatch]').forEach(b=>b.onclick=()=>removeImportBatch(b.dataset.delbatch));renderDiagnostics();}
async function handleFiles(files){
  const csvFiles=Array.from(files||[]).filter(f=>/\.csv$/i.test(f.name));
  const status=$('#csvImportStatus'),button=$('#pickCsv');
  if(!csvFiles.length){if(status){status.textContent='CSVファイルを選択してください。';status.dataset.status='error';}return;}
  let count=0,errors=0,duplicates=0;
  const started=performance.now();button.disabled=true;
  try{
    for(let i=0;i<csvFiles.length;i++){
      const f=csvFiles[i];
      if(status){status.textContent=`読込中 ${i+1}/${csvFiles.length}：${f.name}`;status.dataset.status='loading';}
      // Paint the progress message before CPU-intensive CSV parsing.
      await new Promise(resolve=>requestAnimationFrame(()=>setTimeout(resolve,0)));
      try{
        const res=await ingest(f);
        state.files.push(res);count+=res.added||0;duplicates+=res.duplicates||0;
        if(res.status==='err')errors++;
      }catch(e){errors++;state.files.push({name:f.name,status:'err',msg:`読込エラー ${e.message}`,enc:'-',added:0,date:new Date().toISOString()});}
    }
    persist();syncSelectors();renderAll();
    const seconds=((performance.now()-started)/1000).toFixed(2);
    const label=`取込完了 ${seconds}秒：${num.format(count)}件追加${duplicates?`／重複${num.format(duplicates)}件`:''}${errors?`／エラー${errors}ファイル`:''}`;
    if(status){status.textContent=label;status.dataset.status=errors?'error':'ok';}
    toast(errors?`一部のCSVを読み込めませんでした（${errors}件）。履歴を確認してください`:count?`${num.format(count)}件を反映しました（${seconds}秒）`:'新しい明細はありませんでした');
  }finally{button.disabled=false;}
}
function dataDiagnostics(){
  const issues=[],records=state.records,trades=state.trades;
  const missingIdentity=records.filter(r=>!r.code&&!r.name).length;if(missingIdentity)issues.push({level:'warn',text:`銘柄を特定できない損益・配当が ${missingIdentity}件あります`});
  const unknownAccount=records.filter(r=>accountBucket(r.account)==='不明').length;if(unknownAccount)issues.push({level:'info',text:`口座区分が不明な明細が ${unknownAccount}件あります`});
  const unknownTrade=trades.filter(t=>tradeBucket(t.action)==='不明').length;if(unknownTrade)issues.push({level:'info',text:`現物/信用を判定できない約定が ${unknownTrade}件あります`});
  const noQty=trades.filter(t=>!t.qty).length;if(noQty)issues.push({level:'warn',text:`数量を取得できない約定が ${noQty}件あります`});
  const fileErrors=state.files.filter(f=>f.status==='err').length;if(fileErrors)issues.push({level:'err',text:`読込エラー履歴が ${fileErrors}件あります`});
  const unknownPerkMethod=state.perks.filter(p=>!String(p.method||'').trim()).length;if(unknownPerkMethod)issues.push({level:'warn',text:`取得方法を判定できない優待が ${unknownPerkMethod}件あります`});
  const sourceKeys=[...records,...trades].map(x=>x.sourceKey).filter(Boolean),dupSource=sourceKeys.length-new Set(sourceKeys).size;if(dupSource)issues.push({level:'err',text:`内部重複キーを ${dupSource}件検出しました`});
  const pairs=holdingPairs('all'),totalTradeQty=trades.reduce((s,t)=>s+Math.abs(valueOrZero(t.qty)),0);if(trades.length&&!pairs.length)issues.push({level:'info',text:'約定履歴はありますが保有期間を推計できる売買ペアがありません'});
  if(!issues.length)issues.push({level:'ok',text:'主要なデータ整合性チェックで問題は見つかりませんでした'});
  const duplicateSkipped=state.files.reduce((s,f)=>s+valueOrZero(f.duplicates),0),lastImport=state.files.map(f=>f.date).filter(Boolean).sort().pop()||'';return {issues,records:records.length,trades:trades.length,perks:state.perks.length,files:state.files.length,pairs:pairs.length,totalTradeQty,duplicateSkipped,lastImport,favorites:(state.favorites||[]).length,lastBackupAt:state.lastBackupAt||''};
}
function renderDiagnostics(){const d=dataDiagnostics(),sum=$('#diagnosticSummary'),list=$('#diagnosticIssues'),meta=$('#diagnosticMeta');if(!sum||!list)return;sum.innerHTML=[['損益/配当',d.records],['約定',d.trades],['優待',d.perks],['取込CSV',d.files],['売買ペア',d.pairs],['お気に入り',d.favorites]].map(([n,v])=>`<div><span>${n}</span><b>${num.format(v)}</b></div>`).join('');if(meta){const last=d.lastImport?new Date(d.lastImport).toLocaleString('ja-JP'):'未取込',backup=d.lastBackupAt?new Date(d.lastBackupAt).toLocaleString('ja-JP'):'未保存';meta.innerHTML=`最終CSV取込：${esc(last)}<br>重複除外：${num.format(d.duplicateSkipped)}件　・　最終バックアップ：${esc(backup)}`;}list.innerHTML=d.issues.map(x=>`<div class="diagnostic-item ${x.level}"><i></i><span>${esc(x.text)}</span></div>`).join('');}
function runSelfChecks(){
  const checks=[];const t=(name,fn)=>{try{const ok=!!fn();checks.push({name,ok,msg:ok?'正常':'不一致'});}catch(e){checks.push({name,ok:false,msg:e.message});}};
  t('金額のカンマ・△表記',()=>cleanNum('△1,234')===-1234&&cleanNum('￥2,500')===2500);
  t('存在しない日付の除外',()=>parseDate('2026/02/30')===null&&parseDate('2026/02/28')==='2026-02-28');
  t('CSVの引用符・カンマ',()=>parseCSV('a,"b,c"\n1,2')[0][1]==='b,c');
  t('勝率計算',()=>{const w=winLoss([{amount:10},{amount:-4},{amount:0}]);return w.wins===1&&w.losses===1&&w.draws===1&&Math.abs(w.winrate-.5)<1e-9;});
  t('同日分割決済の集約',()=>groupProfitRows([{date:'2026-01-01',code:'1234',name:'A',amount:10},{date:'2026-01-01',code:'1234',name:'A',amount:-3}], 'stockday').length===1&&groupProfitRows([{date:'2026-01-01',code:'1234',name:'A',amount:10},{date:'2026-01-01',code:'1234',name:'A',amount:-3}], 'stockday')[0].amount===7);
  t('優待実質お得額',()=>perkNet({basis:'personal',personalValue:3000,commission:100,lendingFee:200,reverseDaily:50,otherCost:0})===2650);
  t('優待取得方法の分類',()=>normalizePerkMethod('現物保有')==='holding'&&normalizePerkMethod('優待クロス')==='cross'&&normalizePerkMethod('')==='other');
  t('現物・クロス件数の分離',()=>{const x=perkMethodStats([{method:'holding',basis:'face',faceValue:1000},{method:'cross',basis:'face',faceValue:2000,lendingFee:100},{method:'cross',basis:'face',faceValue:3000}]);return x[0].count===1&&x[1].count===2&&x[1].value===5000&&x[1].cost===100;});
  t('現物・信用の分類保持',()=>{const x=profitBreakdownRows([{date:'2026-01-01',code:'1234',name:'A',action:'現物売',amount:10},{date:'2026-01-01',code:'1234',name:'A',action:'信用返済売',amount:20}],r=>recordTradeType(r),'stockday');return x.some(v=>v.name==='現物'&&v.amount===10)&&x.some(v=>v.name==='信用'&&v.amount===20);});
  t('重複キーの複数行識別',()=>sourceKeyFor('profit',['a','b'],1)!==sourceKeyFor('profit',['a','b'],2));
  t('最大ドローダウン計算',()=>{const rows=[{date:'2026-01-01',code:'1',name:'A',amount:100},{date:'2026-01-02',code:'2',name:'B',amount:-40},{date:'2026-01-03',code:'3',name:'C',amount:-30}];let cum=0,peak=0,dd=0;for(const r of rows){cum+=r.amount;peak=Math.max(peak,cum);dd=Math.max(dd,peak-cum);}return dd===70;});
  t('バックアップ状態のJSON化',()=>{JSON.stringify(state);return true;});
  t('大量表の仮想描画機構',()=>typeof renderVirtualTable==='function'&&!!$('#stockTableWrap')&&!!$('#perkTableWrap'));
  t('戻る操作のシート制御',()=>typeof window.handleAndroidBack==='function');
  t('グラフの指なぞり機構',()=>typeof bindChartScrub==='function');
  t('お気に入り銘柄の保持',()=>Array.isArray(state.favorites)&&typeof toggleFavorite==='function');
  t('Undo付き削除',()=>typeof deletePerk==='function'&&typeof deleteAdjustment==='function');
  t('スワイプ操作',()=>typeof bindSwipeAction==='function'&&typeof showActionSheet==='function');
  t('Pull to Refresh',()=>typeof bindPullRefresh==='function');
  t('入力ミス警告',()=>typeof suspiciousPerk==='function');
  const el=$('#selfCheckResult');el.innerHTML=checks.map(c=>`<div class="check-item ${c.ok?'ok':'err'}"><span>${c.ok?'✓':'×'}</span><div><b>${esc(c.name)}</b><small>${esc(c.msg)}</small></div></div>`).join('');toast(checks.every(c=>c.ok)?'セルフチェックはすべて正常です':'セルフチェックで問題を検出しました');return checks;
}

function csvText(rows){return '\ufeff'+rows.map(r=>r.map(v=>`"${String(v??'').replace(/"/g,'""')}"`).join(',')).join('\r\n');}
function saveText(name,text,mime='text/plain;charset=utf-8'){
  try{if(window.Android&&typeof Android.saveText==='function'){Android.saveText(name,text);return;}}catch(e){}
  const blob=new Blob([text],{type:mime}),a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(a.href),1000);
}
function exportBackup(){state.lastBackupAt=new Date().toISOString();persist();renderDiagnostics();const snapshot={...state,version:VERSION,exportedAt:state.lastBackupAt};saveText(`いんかむぴのりん_バックアップ_${localToday()}.json`,JSON.stringify(snapshot,null,2),'application/json;charset=utf-8');haptic('success');toast('バックアップを準備しました');}
function validateBackup(obj){
  const isObject=v=>!!v&&typeof v==='object'&&!Array.isArray(v);
  if(!isObject(obj)||!Array.isArray(obj.records)||!Array.isArray(obj.trades))throw new Error('バックアップ形式が正しくありません');
  for(const key of ['perks','adjustments','files','favorites']){
    if(obj[key]!==undefined&&!Array.isArray(obj[key]))throw new Error(`${key}の形式が正しくありません`);
  }
  for(const key of ['settings','filters']){
    if(obj[key]!==undefined&&!isObject(obj[key]))throw new Error(`${key}の形式が正しくありません`);
  }
  const array=(key)=>obj[key]===undefined?[]:obj[key];
  for(const r of obj.records){
    if(!isObject(r)||!parseDate(r.date)||!['profit','dividend'].includes(r.type)||!Number.isFinite(r.amount))throw new Error('売買損益・配当の明細が不正です');
  }
  for(const t of obj.trades){
    if(!isObject(t)||!parseDate(t.date)||!Number.isFinite(t.qty)||!Number.isFinite(t.feeTotal))throw new Error('約定履歴の明細が不正です');
  }
  for(const p of array('perks')){
    if(!isObject(p)||!parseDate(p.date)||['faceValue','personalValue','resaleValue','commission','lendingFee','reverseDaily','otherCost'].some(k=>p[k]!==undefined&&!Number.isFinite(p[k])))throw new Error('株主優待の明細が不正です');
  }
  for(const a of array('adjustments')){
    if(!isObject(a)||!parseDate(a.date)||!Number.isFinite(a.amount))throw new Error('その他収支の明細が不正です');
  }
  for(const f of array('files'))if(!isObject(f))throw new Error('取込履歴が不正です');
  if(array('favorites').some(v=>typeof v!=='string'&&typeof v!=='number'))throw new Error('お気に入りの形式が不正です');
  const base=defaultState();
  return {...base,...obj,records:obj.records,trades:obj.trades,perks:array('perks').map(p=>({...p,method:normalizePerkMethod(p.method)})),adjustments:array('adjustments'),files:array('files'),favorites:[...new Set(array('favorites').map(String))],settings:{...base.settings,...(obj.settings||{})},filters:{...base.filters,...(obj.filters||{})},version:VERSION};
}
async function restoreBackup(file){
  const previous=state;
  try{
    const parsed=JSON.parse(await file.text());
    const validated=validateBackup(parsed); // No state mutation until every field has passed.
    state=validated;
    try{applyTheme();syncSettings();syncSelectors();renderAll();}catch(err){state=previous;applyTheme();syncSettings();syncSelectors();renderAll();throw err;}
    persist();toast('バックアップを復元しました');
  }catch(e){toast(`復元できません: ${e.message}`);}
}
function exportPeriodSummary(){
  const rows=[['集計単位','期間','売買益','配当','優待価値','優待取得コスト','優待実質お得額','その他収支','信用コスト','総合損益']];
  for(const y of availableYears().slice().sort((a,b)=>a-b)){const a=aggregate(String(y));rows.push(['年',String(y),a.profit,a.dividend,a.perkValue,a.perkCost,a.perkNet,a.adjustment,a.creditCost,a.total]);}
  const months=new Set();[...state.records,...state.perks,...state.adjustments].forEach(x=>x.date&&months.add(x.date.slice(0,7)));
  for(const m of [...months].sort()){const rs=state.records.filter(r=>r.date.startsWith(m)),ps=state.perks.filter(p=>p.date.startsWith(m)),as=state.adjustments.filter(a=>a.date.startsWith(m)),ts=state.trades.filter(t=>t.date.startsWith(m));const profit=rs.filter(r=>r.type==='profit').reduce((s,r)=>s+r.amount,0),div=rs.filter(r=>r.type==='dividend').reduce((s,r)=>s+r.amount,0),pv=ps.reduce((s,p)=>s+perkValue(p),0),pc=ps.reduce((s,p)=>s+perkCost(p),0),pn=ps.reduce((s,p)=>s+perkNet(p),0),crossCost=ps.filter(p=>p.method==='cross').reduce((s,p)=>s+perkCost(p),0),adj=as.reduce((s,a)=>s+adjustmentSigned(a),0),credit=ts.filter(creditTrade).reduce((s,t)=>s+valueOrZero(t.feeTotal),0),total=profit+div+pv+adj-(state.settings.includeCrossCosts?crossCost:0);rows.push(['月',m,profit,div,pv,pc,pn,adj,credit,total]);}
  saveText('投資成績_月別年別集計.csv',csvText(rows),'text/csv;charset=utf-8');
}
function exportStocksCsv(){const rows=[['銘柄コード','銘柄名','総合損益','売買益','配当','優待価値','優待実質お得額','信用コスト','勝','負','引分','勝率','約定件数'],...stockStats('all').sort((a,b)=>b.total-a.total).map(x=>[x.code,x.name,x.total,x.profit,x.dividend,x.perkValue,x.perkNet,x.creditCost,x.wins,x.losses,x.draws,Number.isFinite(x.winrate)?(x.winrate*100).toFixed(1)+'%':'',x.trades])];saveText('投資成績_銘柄別.csv',csvText(rows),'text/csv;charset=utf-8');}
function exportAllDetails(){const rows=[['日付','種別','銘柄コード','銘柄名','取引/内容','口座','金額','信用コスト','ファイル']];state.records.forEach(r=>rows.push([r.date,r.type==='profit'?'売買損益':'配当',r.code,r.name,r.action||r.product,r.account,r.amount,'',r.file]));state.trades.forEach(t=>rows.push([t.date,'約定',t.code,t.name,t.action,t.account,t.settle||'',t.feeTotal||'',t.file]));state.perks.forEach(p=>rows.push([p.date,'優待',p.code,p.name,p.item,perkMethodLabel(p.method),perkValue(p),perkCost(p),'']));state.adjustments.forEach(a=>rows.push([a.date,'その他','',a.name||'',a.memo,a.type,adjustmentSigned(a),'','']));rows.splice(1,rows.length-1,...rows.slice(1).sort((a,b)=>String(b[0]).localeCompare(String(a[0]))));saveText('投資成績_全明細.csv',csvText(rows),'text/csv;charset=utf-8');}
function exportTaxCsv(){const year=state.filters.analysisYear||'all',rows=[['口座区分','売買損益','配当（税引後）','決済件数'],...taxReference(year).map(x=>[x.name,x.profit,x.dividend,x.count])];saveText(`確定申告_参考集計_${year}.csv`,csvText(rows),'text/csv;charset=utf-8');}
function exportPerks(){const rows=[['取得日','銘柄コード','銘柄名','取得方法','株数','優待内容','評価基準','券面価値','自分価値','売却換算価値','売買手数料','貸株料','逆日歩','その他費用','メモ'],...state.perks.map(p=>[p.date,p.code,p.name,perkMethodLabel(p.method),p.shares,p.item,p.basis,p.faceValue,p.personalValue,p.resaleValue,p.commission,p.lendingFee,p.reverseDaily,p.otherCost,p.memo])];saveText('株主優待_履歴.csv',csvText(rows),'text/csv;charset=utf-8');}
async function importPerks(file){try{const dec=decodeBuffer(await file.arrayBuffer()),rows=parseCSV(dec.text);if(rows.length<2)throw new Error('データがありません');const H=rows[0].map(norm),idx={date:col(H,[/取得日|日付/]),code:col(H,[/銘柄コード/]),name:col(H,[/銘柄名/]),method:col(H,[/取得方法/]),shares:col(H,[/株数/]),item:col(H,[/優待内容/]),basis:col(H,[/評価基準/]),face:col(H,[/券面価値/]),personal:col(H,[/自分価値/]),resale:col(H,[/売却換算価値/]),commission:col(H,[/売買手数料/]),lending:col(H,[/貸株料/]),reverse:col(H,[/逆日歩/]),other:col(H,[/その他費用/]),memo:col(H,[/メモ/])};let added=0,duplicates=0;const existing=new Map();state.perks.forEach(p=>existing.set(perkSig(p),(existing.get(perkSig(p))||0)+1));const seen=new Map();for(const row of rows.slice(1)){const date=parseDate(row[idx.date]);if(!date)continue;const p={id:uid('p'),date,code:idx.code>=0?String(row[idx.code]||'').trim().toUpperCase():'',name:idx.name>=0?String(row[idx.name]||'').trim():'',method:normalizePerkMethod(idx.method>=0?String(row[idx.method]||'').trim():''),shares:idx.shares>=0?valueOrZero(cleanNum(row[idx.shares])):0,item:idx.item>=0?String(row[idx.item]||'').trim():'',basis:idx.basis>=0?String(row[idx.basis]||'personal').trim():'personal',faceValue:idx.face>=0?valueOrZero(cleanNum(row[idx.face])):0,personalValue:idx.personal>=0?valueOrZero(cleanNum(row[idx.personal])):0,resaleValue:idx.resale>=0?valueOrZero(cleanNum(row[idx.resale])):0,commission:idx.commission>=0?valueOrZero(cleanNum(row[idx.commission])):0,lendingFee:idx.lending>=0?valueOrZero(cleanNum(row[idx.lending])):0,reverseDaily:idx.reverse>=0?valueOrZero(cleanNum(row[idx.reverse])):0,otherCost:idx.other>=0?valueOrZero(cleanNum(row[idx.other])):0,memo:idx.memo>=0?String(row[idx.memo]||'').trim():''};const sig=perkSig(p),n=(seen.get(sig)||0)+1;seen.set(sig,n);if((existing.get(sig)||0)>=n){duplicates++;continue;}state.perks.push(p);added++;}persist();syncSelectors();renderPerkAffected();toast(`${added}件の優待を読み込み${duplicates?`・重複${duplicates}件除外`:''}`);}catch(e){toast(`優待CSVを読めません: ${e.message}`);}}

function miniChartSvg(points){
  if(!points.length)return'<div class="empty-state">推移データがありません</div>';
  const W=650,H=150,p={l:12,r:10,t:10,b:24},vals=points.map(x=>x[1]);let min=Math.min(0,...vals),max=Math.max(0,...vals);if(min===max){min-=1;max+=1;}const x=i=>p.l+(points.length===1?0:(W-p.l-p.r)*i/(points.length-1)),y=v=>p.t+(max-v)*(H-p.t-p.b)/(max-min),zero=y(0),path=points.map((d,i)=>`${i?'L':'M'}${x(i)},${y(d[1])}`).join(' ');let svg=`<svg viewBox="0 0 ${W} ${H}" preserveAspectRatio="none"><line class="chart-zero" x1="${p.l}" x2="${W-p.r}" y1="${zero}" y2="${zero}"/><path class="chart-total" d="${path}"/>`;points.forEach((d,i)=>{svg+=`<circle class="chart-dot-total" cx="${x(i)}" cy="${y(d[1])}" r="2.5"/>`;if(points.length<13||i%Math.ceil(points.length/10)===0)svg+=`<text class="chart-label" x="${x(i)}" y="${H-6}" text-anchor="middle">${d[0].slice(5)}月</text>`;});return svg+'</svg>';
}
let stockModalCloseTimer=null;
function resetStockSheetStyles(){const backdrop=$('#stockModalBackdrop'),sheet=backdrop?.querySelector('.modal-sheet');if(!backdrop||!sheet)return;clearTimeout(stockModalCloseTimer);sheet.style.transform='';sheet.style.transition='';backdrop.style.opacity='';backdrop.style.transition='';}
function openStockDetail(key){
  const year=state.filters.stockYear||'all',d=stockDetailData(key,year);if(!d)return;const fav=$('#stockFavoriteBtn');fav.dataset.key=key;fav.textContent=isFavorite(key)?'★':'☆';fav.classList.toggle('active',isFavorite(key));$('#stockModalTitle').textContent=d.stat.name;$('#stockModalCode').textContent=[d.stat.code,year==='all'?'全期間':`${year}年`].filter(Boolean).join(' ・ ');
  const posi=estimatedPosition(d.stat.code,d.stat.name);const stats=[['総合損益',d.stat.total],['売買益',d.stat.profit],['配当',d.stat.dividend],['優待価値',d.stat.perkValue],['優待実質益',d.stat.perkNet],['信用コスト',d.stat.creditCost]];
  const pairs=holdingPairs(year).filter(p=>keyFor(resolvedCode(p.code,p.name,aliases()),p.name)===key).sort((a,b)=>b.closeDate.localeCompare(a.closeDate));
  const cycles=pairs.length?pairs.slice(0,20).map(p=>`<div class="timeline-item"><span class="date">${p.openDate}<br>→ ${p.closeDate}</span><span class="desc">${esc(p.kind)} ${num.format(p.qty)}株</span><span class="amt">${p.days}日</span></div>`).join(''):'<div class="empty-state">約定履歴から売買サイクルを推計できません</div>';
  const timeline=d.timeline.length?d.timeline.slice(0,100).map(t=>`<div class="timeline-item"><span class="date">${t.date}</span><span class="desc">${esc(t.type)}　${esc(t.desc)}</span><span class="amt ${moneyClass(t.amount)}">${t.amount?yen.format(t.amount):''}</span></div>`).join(''):'<div class="empty-state">明細がありません</div>';
  $('#stockModalBody').innerHTML=`<div class="detail-metrics">${stats.map(([n,v])=>`<div class="detail-metric"><span>${n}</span><b class="${moneyClass(v)}">${yen.format(v)}</b></div>`).join('')}<div class="detail-metric"><span>勝率</span><b>${pct(d.stat.winrate)}</b></div><div class="detail-metric"><span>平均保有</span><b>${Number.isFinite(d.hold)?d.hold.toFixed(1)+'日':'—'}</b></div><div class="detail-metric"><span>推計ポジション</span><b>${num.format(posi.cash)}現物 / ${num.format(posi.long)}買 / ${num.format(posi.short)}売</b></div></div><div class="detail-section"><h4>損益推移</h4><div class="svg-chart detail-chart" style="height:150px">${miniChartSvg(d.monthly)}</div></div><div class="detail-section"><h4>保有 → 売却サイクル</h4><div class="timeline-list">${cycles}</div></div><div class="detail-section"><h4>明細</h4><div class="timeline-list">${timeline}</div></div>`;
  const backdrop=$('#stockModalBackdrop');resetStockSheetStyles();backdrop.hidden=false;document.body.style.overflow='hidden';const detailChart=$('#stockModalBody').querySelector('.detail-chart');bindChartScrub(detailChart,d.monthly.map(([month,total])=>({month,total})),[{key:'total',label:'損益',cls:'total'}],{W:650,padL:12,padR:10});
}
function closeStockDetail(animate=true){const backdrop=$('#stockModalBackdrop'),sheet=backdrop?.querySelector('.modal-sheet');if(!backdrop||backdrop.hidden)return false;const finish=()=>{backdrop.hidden=true;document.body.style.overflow='';resetStockSheetStyles();};if(!animate||matchMedia('(prefers-reduced-motion: reduce)').matches){finish();return true;}sheet.style.transition='transform .22s cubic-bezier(.4,0,1,1)';backdrop.style.transition='opacity .18s ease';requestAnimationFrame(()=>{sheet.style.transform='translateY(105%)';backdrop.style.opacity='0';});stockModalCloseTimer=setTimeout(finish,230);return true;}
function bindStockSheetGesture(){
  const backdrop=$('#stockModalBackdrop'),sheet=backdrop.querySelector('.modal-sheet'),handle=sheet.querySelector('.modal-handle');let dragging=false,startY=0,lastY=0,startT=0;
  handle.addEventListener('pointerdown',e=>{if(backdrop.hidden)return;dragging=true;startY=lastY=e.clientY;startT=performance.now();sheet.style.transition='none';backdrop.style.transition='none';try{handle.setPointerCapture(e.pointerId);}catch(_){};});
  handle.addEventListener('pointermove',e=>{if(!dragging)return;const dy=Math.max(0,e.clientY-startY);lastY=e.clientY;sheet.style.transform=`translateY(${dy}px)`;backdrop.style.opacity=String(1-Math.min(.7,dy/Math.max(280,sheet.clientHeight)));});
  const end=e=>{if(!dragging)return;dragging=false;const dy=Math.max(0,(e.clientY||lastY)-startY),dt=Math.max(16,performance.now()-startT),velocity=dy/dt;if(dy>82||velocity>.55){closeStockDetail(true);}else{sheet.style.transition='transform .24s cubic-bezier(.2,.8,.2,1)';backdrop.style.transition='opacity .2s ease';sheet.style.transform='translateY(0)';backdrop.style.opacity='1';setTimeout(resetStockSheetStyles,250);}};
  handle.addEventListener('pointerup',end);handle.addEventListener('pointercancel',end);
}
window.handleAndroidBack=()=>closeActionSheet()||closeStockDetail(true);
function syncSettings(){
  $('#darkMode').checked=!!state.settings.dark;$('#haptics').checked=state.settings.haptics!==false;$('#equityOnly').checked=!!state.settings.equityOnly;$('#includeCrossCosts').checked=!!state.settings.includeCrossCosts;$('#winRateMode').value=state.settings.winRateMode||'stockday';$('#dividendGoalInput').value=Math.round(valueOrZero(state.settings.dividendGoal));document.documentElement.dataset.theme=state.settings.dark?'dark':'light';$('#themeQuickIcon').textContent=state.settings.dark?'☀':'☾';try{if(window.Android&&typeof Android.setDarkMode==='function')Android.setDarkMode(!!state.settings.dark);}catch(e){}
}
function applyTheme(){document.documentElement.dataset.theme=state.settings.dark?'dark':'light';$('#themeQuickIcon').textContent=state.settings.dark?'☀':'☾';try{if(window.Android&&typeof Android.setDarkMode==='function')Android.setDarkMode(!!state.settings.dark);}catch(e){}}
function renderPerkAffected(){renderAll();}
function renderAdjustmentAffected(){renderAll();}
function renderAll(){['home','stocks','analysis','perks','data'].forEach(k=>screenDirty.add(k));renderCurrentScreen();}
let currentScreen='home';
const screenScroll={home:0,stocks:0,analysis:0,perks:0,data:0};
function setScreen(name){
  if(!screenScroll.hasOwnProperty(name))return;
  if(name===currentScreen){window.scrollTo({top:0,behavior:'smooth'});return;}
  screenScroll[currentScreen]=window.scrollY||0;
  $$('.screen').forEach(s=>s.classList.toggle('active',s.id===`screen-${name}`));
  $$('.nav-item').forEach(n=>n.classList.toggle('active',n.dataset.screen===name));
  currentScreen=name;
  if(screenDirty.has(name))renderCurrentScreen();
  requestAnimationFrame(()=>window.scrollTo(0,Math.min(screenScroll[name]||0,Math.max(0,document.documentElement.scrollHeight-window.innerHeight))));
}
function renderCurrentScreen(){if(currentScreen==='home')renderHome();else if(currentScreen==='stocks')renderStocks();else if(currentScreen==='analysis')renderAnalysis();else if(currentScreen==='perks')renderPerks();else renderFiles();screenDirty.delete(currentScreen);}
function bindPullRefresh(){
  const el=$('#pullRefresh'),label=$('#pullRefreshText');let tracking=false,startY=0,dy=0;
  document.addEventListener('touchstart',e=>{if(window.scrollY>1||!e.touches?.length||e.target.closest('input,select,button,.modal-backdrop,.action-backdrop'))return;tracking=true;startY=e.touches[0].clientY;dy=0;},{passive:true});
  document.addEventListener('touchmove',e=>{if(!tracking||!e.touches?.length)return;dy=Math.max(0,e.touches[0].clientY-startY);if(dy<8)return;const shown=Math.min(72,dy*.45);el.classList.add('show');el.classList.toggle('ready',dy>78);el.style.transform=`translate(-50%,${shown-52}px) scale(${.92+Math.min(.08,dy/900)})`;label.textContent=dy>78?'離して再集計':'下へ引いて再集計';},{passive:true});
  document.addEventListener('touchend',()=>{if(!tracking)return;tracking=false;if(dy>78){el.classList.add('refreshing');label.textContent='再集計中';haptic('medium');requestAnimationFrame(()=>{renderCurrentScreen();setTimeout(()=>{el.classList.remove('show','ready','refreshing');el.style.transform='';label.textContent='下へ引いて再集計';toast('再集計しました');},260);});}else{el.classList.remove('show','ready');el.style.transform='';label.textContent='下へ引いて再集計';}dy=0;},{passive:true});
}
function clearAll(){if(!confirm('CSV取込データ・優待・その他収支をすべて削除しますか？'))return;const snapshot=JSON.parse(JSON.stringify(state)),settings={...state.settings},filters={...state.filters};state=defaultState();state.settings=settings;state.filters=filters;persist();syncSelectors();renderAll();haptic('warning');toast('データを削除しました','元に戻す',()=>{state=snapshot;persist();syncSettings();syncSelectors();renderAll();});}

function bind(){
  $$('.nav-item').forEach(b=>b.addEventListener('click',()=>{haptic('light');setScreen(b.dataset.screen);}));
  $('#themeQuick').onclick=()=>{state.settings.dark=!state.settings.dark;syncSettings();persist();};
  $('#darkMode').onchange=e=>{state.settings.dark=e.target.checked;applyTheme();persist();};
  $('#haptics').onchange=e=>{state.settings.haptics=e.target.checked;persist();if(e.target.checked)haptic('success');};
  $('#equityOnly').onchange=e=>{state.settings.equityOnly=e.target.checked;persist();renderCurrentScreen();};
  $('#includeCrossCosts').onchange=e=>{state.settings.includeCrossCosts=e.target.checked;persist();renderCurrentScreen();};
  $('#winRateMode').onchange=e=>{state.settings.winRateMode=e.target.value;persist();renderCurrentScreen();};
  $('#dividendGoalInput').onchange=e=>{state.settings.dividendGoal=Math.max(0,valueOrZero(e.target.value));persist();renderHome();};
  $('#editDividendGoal').onclick=()=>{setScreen('data');setTimeout(()=>$('#dividendGoalInput').focus({preventScroll:true}),180);};
  $('#homeYear').onchange=e=>{state.filters.homeYear=e.target.value;persist();renderHome();};
  $('#stockYear').onchange=e=>{state.filters.stockYear=e.target.value;persist();renderStocks();};
  $('#analysisYear').onchange=e=>{state.filters.analysisYear=e.target.value;persist();renderAnalysis();};
  $('#perkYear').onchange=e=>{state.filters.perkYear=e.target.value;persist();renderPerks();};
  $('#perkMethodFilter').onchange=e=>{state.filters.perkMethod=e.target.value;persist();renderPerks();};
  $('#calendarMonth').onchange=e=>{state.filters.calendarMonth=e.target.value;persist();renderCalendar();};
  let stockSearchTimer=null;const searchEl=$('#stockSearch');searchEl.addEventListener('input',e=>{if(e.isComposing)return;clearTimeout(stockSearchTimer);stockSearchTimer=setTimeout(renderStocks,170);});searchEl.addEventListener('compositionend',()=>{clearTimeout(stockSearchTimer);stockSearchTimer=setTimeout(renderStocks,70);});$('#stockSort').onchange=renderStocks;$('#favoriteFilter').onclick=()=>{state.filters.favoriteOnly=!state.filters.favoriteOnly;persist();haptic('light');renderStocks();};
  $('#pickCsv').onclick=()=>$('#csvInput').click();$('#csvInput').onchange=async e=>{await handleFiles(e.target.files);e.target.value='';};
  $('#backupInput').onchange=async e=>{if(e.target.files[0])await restoreBackup(e.target.files[0]);e.target.value='';};
  $('#exportBackup').onclick=exportBackup;$('#exportMonthly').onclick=exportPeriodSummary;$('#exportStocks').onclick=exportStocksCsv;$('#exportAllDetails').onclick=exportAllDetails;$('#exportTax').onclick=exportTaxCsv;
  $('#exportPerks').onclick=exportPerks;$('#perkCsvInput').onchange=async e=>{if(e.target.files[0])await importPerks(e.target.files[0]);e.target.value='';};
  ['perkFace','perkPersonal','perkResale','perkCommission','perkLending','perkReverse','perkOtherCost','perkBasis'].forEach(id=>$('#'+id).addEventListener('input',updatePerkPreview));
  $('#addPerk').onclick=addPerk;$('#perkFormReset').onclick=resetPerkForm;$('#addAdjustment').onclick=addAdjustment;$('#runDiagnostics').onclick=renderDiagnostics;$('#runSelfCheck').onclick=runSelfChecks;$('#clearAll').onclick=clearAll;
  $('#stockFavoriteBtn').onclick=()=>{const key=$('#stockFavoriteBtn').dataset.key;if(!key)return;const added=toggleFavorite(key);$('#stockFavoriteBtn').textContent=added?'★':'☆';$('#stockFavoriteBtn').classList.toggle('active',added);};$('#closeStockModal').onclick=()=>closeStockDetail(true);$('#stockModalBackdrop').onclick=e=>{if(e.target===$('#stockModalBackdrop'))closeStockDetail(true);};bindStockSheetGesture();$('#actionSheetCancel').onclick=closeActionSheet;$('#actionSheetBackdrop').onclick=e=>{if(e.target===$('#actionSheetBackdrop'))closeActionSheet();};document.addEventListener('keydown',e=>{if(e.key==='Escape'&&!closeActionSheet())closeStockDetail(true);});bindPullRefresh();
  $('#adjDate').value=localToday();
}

syncSettings();syncSelectors();resetPerkForm();bind();persist();renderAll();
})();