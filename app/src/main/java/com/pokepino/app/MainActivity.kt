package com.pokepino.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val figures = loadDb()
        val brand = loadBrand()
        setContent { PokepinoApp(figures, brand) }
    }

    private fun loadDb(): List<Figure> {
        val parts = listOf(
            "dbgz_00.txt",
            "dbgz_01a.txt","dbgz_01b.txt",
            "dbgz_02a.txt","dbgz_02b.txt",
            "dbgz_03a.txt","dbgz_03b.txt"
        )
        val b64 = buildString {
            parts.forEach { name ->
                append(assets.open(name).bufferedReader().use { it.readText() })
            }
        }
        val gz = Base64.decode(b64, Base64.DEFAULT)
        val json = GZIPInputStream(ByteArrayInputStream(gz))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

        fun parseRows(a:JSONArray):MutableList<Figure> {
            val result=ArrayList<Figure>(a.length())
            repeat(a.length()) { i ->
                val r = a.optJSONArray(i) ?: return@repeat
                val refs = r.optJSONArray(8)?.let { x ->
                    (0 until x.length()).mapNotNull { j ->
                        x.optString(j).takeIf(String::isNotBlank)
                    }
                } ?: emptyList()
                val meta:JSONObject? = r.optJSONObject(9)
                result += Figure(
                    id = r.optString(0),
                    dex = r.optInt(1),
                    pokemon = r.optString(2),
                    variant = r.optString(3),
                    series = r.optString(4),
                    year = r.optString(5),
                    visualGroupId = r.optString(6, r.optString(0)),
                    status = r.optString(7, "species_only"),
                    refs = refs,
                    kidsNo = meta?.optInt("kidsNo",0) ?: 0,
                    feature = meta?.optString("feature","") ?: "",
                    source = meta?.optString("source","") ?: ""
                )
            }
            return result
        }

        val base=parseRows(JSONArray(json))
        val generated=runCatching {
            assets.open("catalog_v2.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { parseRows(JSONArray(it)) } ?: mutableListOf()

        // If a generated reference points to the same source image as an existing
        // catalog record, enrich the stable existing ID instead of showing it twice.
        val generatedByRef=HashMap<String,Figure>()
        generated.forEach { g -> g.refs.forEach { ref -> generatedByRef.putIfAbsent(ref,g) } }
        val consumedGeneratedIds=HashSet<String>()
        val enrichedBase=base.map { b ->
            val g=b.refs.asSequence().mapNotNull { generatedByRef[it] }.firstOrNull()
            if(g!=null){
                consumedGeneratedIds += g.id
                b.copy(
                    visualGroupId=g.visualGroupId,
                    status=if(g.feature.isNotBlank())"direct_reference_ready" else b.status,
                    kidsNo=if(g.kidsNo>0)g.kidsNo else b.kidsNo,
                    feature=if(g.feature.isNotBlank())g.feature else b.feature,
                    source=if(g.source.isNotBlank())g.source else b.source
                )
            }else b
        }

        // The global index page is only a discovery page. If a concrete release
        // page has the same visual group, don't expose the index row as a fake release.
        val concreteGroups=generated
            .filter { it.series!="ポケモンキッズ一覧" }
            .mapTo(HashSet()) { it.visualGroupId }
        val extra=generated.filter { g ->
            g.id !in consumedGeneratedIds &&
            !(g.series=="ポケモンキッズ一覧" && g.visualGroupId in concreteGroups)
        }

        return (enrichedBase+extra)
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
    }

    private fun loadBrand(): Bitmap? = null
}

data class Figure(
    val id:String, val dex:Int, val pokemon:String, val variant:String, val series:String,
    val year:String, val visualGroupId:String, val status:String,
    val refs:List<String>, val kidsNo:Int=0, val feature:String="", val source:String=""
)

enum class Tab { DEX, OWNED, PHOTO }

private val Cream = Color(0xFFFFFAED)
private val Red = Color(0xFFE64B3C)
private val Gold = Color(0xFFFFC94A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PokepinoApp(master:List<Figure>, brand:Bitmap?) {
    val context=LocalContext.current
    val prefs=remember { context.getSharedPreferences("owned",0) }
    var owned by remember { mutableStateOf(master.associate { it.id to prefs.getInt(it.id,0) }) }
    var tab by remember { mutableStateOf(Tab.DEX) }
    var selectedDex by remember { mutableStateOf<Int?>(null) }
    var splash by remember { mutableStateOf(true) }
    fun setOwned(id:String,n:Int){ val v=n.coerceAtLeast(0); prefs.edit().putInt(id,v).apply(); owned=owned.toMutableMap().also{it[id]=v} }
    val recognizer=remember { PokemonRecognizer(context.applicationContext) }
    val variantRecognizer=remember { VariantRecognizer(context.applicationContext) }
    DisposableEffect(Unit){ onDispose { recognizer.close() } }
    MaterialTheme(colorScheme=lightColorScheme(primary=Red,secondary=Gold,background=Cream)) {
        if(splash){ Splash(brand){splash=false}; return@MaterialTheme }
        selectedDex?.let { dex -> Detail(master.filter{it.dex==dex},owned,::setOwned){selectedDex=null}; return@MaterialTheme }
        Scaffold(
            topBar={ TopAppBar(title={ Row(verticalAlignment=Alignment.CenterVertically){ Brand(brand,42.dp); Spacer(Modifier.width(8.dp)); Column{Text("ポケピーノ",fontWeight=FontWeight.Black);Text("ポケモンキッズ図鑑",style=MaterialTheme.typography.labelSmall)} } }) },
            bottomBar={ NavigationBar {
                NavigationBarItem(selected=tab==Tab.DEX,onClick={tab=Tab.DEX},modifier=Modifier.testTag("tab_dex"),icon={Text("▦")},label={Text("図鑑")})
                NavigationBarItem(selected=tab==Tab.OWNED,onClick={tab=Tab.OWNED},modifier=Modifier.testTag("tab_owned"),icon={Text("✓")},label={Text("所持")})
                NavigationBarItem(selected=tab==Tab.PHOTO,onClick={tab=Tab.PHOTO},modifier=Modifier.testTag("tab_photo"),icon={Text("◎")},label={Text("判定")})
            }}
        ){ pad -> Box(Modifier.padding(pad).fillMaxSize()){
            when(tab){
                Tab.DEX -> Dex(master,owned,brand){selectedDex=it}
                Tab.OWNED -> Owned(master,owned){selectedDex=it}
                Tab.PHOTO -> Photo(master,owned,recognizer,variantRecognizer,::setOwned){selectedDex=it}
            }
        }}
    }
}

@Composable private fun Brand(brand:Bitmap?,size:androidx.compose.ui.unit.Dp){ if(brand!=null) Image(brand.asImageBitmap(),null,Modifier.size(size),contentScale=ContentScale.Crop) }
@Composable private fun Splash(brand:Bitmap?,done:()->Unit){ LaunchedEffect(Unit){delay(1300);done()}; Box(Modifier.fillMaxSize().background(Color(0xFF118EEA)),contentAlignment=Alignment.Center){ if(brand!=null) Image(brand.asImageBitmap(),"起動画面",Modifier.fillMaxWidth(),contentScale=ContentScale.Fit) } }

@Composable private fun Dex(master:List<Figure>,owned:Map<String,Int>,brand:Bitmap?,open:(Int)->Unit){
    var q by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(0) }
    val grouped=remember(master,q,filter,owned){ master.filter{it.dex>0}.groupBy{it.dex}.values.map{it.first().dex to it}.filter{(_,v)->
        val f=v.first(); val match=q.isBlank()||f.pokemon.contains(q,true)||f.dex.toString()==q.trim(); val has=v.any{(owned[it.id]?:0)>0}; match && (filter==0 || filter==1&&has || filter==2&&!has)
    }.sortedBy{it.first} }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        item { if(brand!=null) Image(brand.asImageBitmap(),null,Modifier.fillMaxWidth().height(180.dp),contentScale=ContentScale.Crop); Spacer(Modifier.height(8.dp)); OutlinedTextField(q,{q=it},Modifier.fillMaxWidth(),label={Text("名前・図鑑No.で検索")}); Row{ listOf("全部","所持","未所持").forEachIndexed{i,s-> FilterChip(filter==i,{filter=i},{Text(s)}); Spacer(Modifier.width(6.dp)) } }; Text("${master.count{it.dex>0}}バリエーション / ${master.filter{it.dex>0}.map{it.dex}.distinct().size}ポケモン",fontWeight=FontWeight.Bold) }
        items(grouped,key={it.first}){(dex,v)-> val f=v.first(); val n=v.count{(owned[it.id]?:0)>0}; Card(Modifier.fillMaxWidth().testTag("dex_$dex").clickable{open(dex)}){ Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){ Text("No.%03d".format(dex),fontWeight=FontWeight.Black); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)){Text(f.pokemon,fontWeight=FontWeight.Bold);Text("${v.size}種 / 所持 $n",style=MaterialTheme.typography.labelSmall)}; Text(if(n>0)"✓" else "○") } } }
    }
}

@Composable private fun Owned(master:List<Figure>,owned:Map<String,Int>,open:(Int)->Unit){ val list=master.filter{(owned[it.id]?:0)>0}; LazyColumn(Modifier.fillMaxSize().testTag("owned_screen").padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){ item{Text("所持 ${list.size}件",Modifier.testTag("owned_count"),fontWeight=FontWeight.Black)}; items(list,key={it.id}){f-> Card(Modifier.fillMaxWidth().testTag("owned_item_${f.id}").clickable{open(f.dex)}){Column(Modifier.padding(12.dp)){Text("No.%03d ${f.pokemon}".format(f.dex),fontWeight=FontWeight.Bold);Text(f.variant);Text("${f.series} ${f.year}",style=MaterialTheme.typography.labelSmall)}}} } }

@Composable private fun Photo(master:List<Figure>,owned:Map<String,Int>,recognizer:PokemonRecognizer,variant:VariantRecognizer,setOwned:(String,Int)->Unit,open:(Int)->Unit){
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var bitmap by remember{mutableStateOf<Bitmap?>(null)}
    var result by remember{mutableStateOf<PokemonRecognizer.Result?>(null)}
    var vr by remember{mutableStateOf<VariantRecognizer.Result?>(null)}
    var busy by remember{mutableStateOf(false)}
    var msg by remember{mutableStateOf("")}
    var cameraFile by remember{mutableStateOf<File?>(null)}
    val visualReferences=remember(master){master.filter{it.dex>0&&it.feature.isNotBlank()}}

    fun analyze(u:Uri){
        if(busy)return
        result=null;vr=null;msg="";busy=true
        scope.launch{
            try{
                val b=withContext(Dispatchers.IO){decodeBitmap(context,u,1280)}
                bitmap=b

                // Primary path: compare directly against the real-figure catalog.
                // This is offline and measured substantially better on figure photos
                // than the generic Pokemon species classifier.
                val visual=withContext(Dispatchers.IO){
                    variant.recognize(b,visualReferences)
                }
                vr=visual
                val visualTop=visual.candidates.firstOrNull()
                val visualTopFigures=visualTop?.recordIds
                    ?.mapNotNull{id->master.firstOrNull{it.id==id}}
                    ?: emptyList()
                val visualDex=visualTopFigures.map{it.dex}.filter{it>0}.distinct().singleOrNull()

                if(visual.accepted&&visualTop!=null&&visualDex!=null){
                    if(visualTop.recordIds.size==1){
                        val id=visualTop.recordIds.first()
                        val fig=master.firstOrNull{it.id==id}
                        if(fig!=null){
                            setOwned(id,maxOf(1,owned[id]?:0))
                            msg="✓ ${fig.pokemon} ${fig.variant} を実物DBで判定して登録しました"
                        }else{
                            msg="実物DBで高確度一致しました"
                        }
                    }else{
                        msg="実物DBで高確度一致しましたが、写真だけでは区別できない同型版があります"
                    }
                }else{
                    // Secondary path: the generic species AI is used only when the
                    // real-figure matcher cannot safely decide. Model download/network
                    // failure must not break offline figure matching.
                    val aiAttempt=runCatching{
                        withContext(Dispatchers.IO){recognizer.recognize(b)}
                    }
                    val ai=aiAttempt.getOrNull()
                    result=ai
                    val top=ai?.candidates?.firstOrNull()

                    if(ai?.accepted==true&&top!=null){
                        val vars=master.filter{it.dex==top.dex}
                        val localRefs=vars.filter{it.feature.isNotBlank()}
                        val local=if(localRefs.isNotEmpty()){
                            withContext(Dispatchers.IO){variant.recognize(b,localRefs)}
                        }else null

                        if(vars.size==1){
                            val fig=vars.first()
                            setOwned(fig.id,maxOf(1,owned[fig.id]?:0))
                            msg="✓ ${fig.pokemon} ${fig.variant} をAI判定して登録しました"
                        }else if(local!=null){
                            vr=local
                            val candidate=local.candidates.firstOrNull()
                            if(local.accepted&&candidate!=null&&candidate.recordIds.size==1){
                                val id=candidate.recordIds.first()
                                val fig=master.firstOrNull{it.id==id}
                                setOwned(id,maxOf(1,owned[id]?:0))
                                msg="✓ ${fig?.pokemon?:"候補"} ${fig?.variant?:""} をAI＋実物DBで登録しました"
                            }else if(candidate!=null&&candidate.recordIds.size>1){
                                msg="ポケモンは特定できましたが、同じ見た目の発売違いがあります"
                            }else{
                                msg="ポケモンは${vars.firstOrNull()?.pokemon?:"候補"}が有力です。版違いは候補から確認してください"
                            }
                        }else{
                            msg="ポケモンは${vars.firstOrNull()?.pokemon?:"候補"}が有力ですが、版違い用の実物参照がありません"
                        }
                    }else{
                        if(visual.candidates.isNotEmpty()){
                            val aiNote=if(aiAttempt.isFailure)"（AI補助は通信またはモデル取得に失敗）" else ""
                            msg="実物DBの候補を表示しています。確信度不足のため自動登録していません$aiNote"
                        }else if(aiAttempt.isFailure){
                            msg="判定できませんでした。AI補助データも取得できませんでした"
                        }else{
                            msg="候補を見つけられませんでした"
                        }
                    }
                }
            }catch(e:Exception){
                msg="処理エラー: ${e.message ?: e.javaClass.simpleName}"
            }finally{
                busy=false
                cameraFile?.let{runCatching{if(it.exists())it.delete()}}
                cameraFile=null
            }
        }
    }

    val pick=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){it?.let(::analyze)}
    val take=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()){ok->
        if(ok){
            cameraFile?.let{file->
                analyze(FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",file))
            }
        }else{
            cameraFile?.let{runCatching{if(it.exists())it.delete()}}
            cameraFile=null
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        item{
            Text("写真で判定",fontWeight=FontWeight.Black)
            Text("1体だけ大きく、なるべく無地背景で撮影してください。",style=MaterialTheme.typography.bodySmall)
            Row{
                Button({pick.launch("image/*")},enabled=!busy){Text("画像を選ぶ")}
                Spacer(Modifier.width(8.dp))
                Button({
                    val f=File(context.cacheDir,"pokepino_${System.currentTimeMillis()}.jpg")
                    cameraFile=f
                    take.launch(FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",f))
                },enabled=!busy){Text("写真を撮る")}
            }
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            bitmap?.let{Image(it.asImageBitmap(),null,Modifier.fillMaxWidth().height(260.dp),contentScale=ContentScale.Fit)}
            if(msg.isNotBlank()) Text(msg,fontWeight=FontWeight.Bold)
        }
        result?.let{r->
            item{Text("ポケモン候補 (${r.engine})",fontWeight=FontWeight.Black)}
            items(r.candidates){candidate->
                val vars=master.filter{it.dex==candidate.dex}
                val name=vars.firstOrNull()?.pokemon?:"No.${candidate.dex}"
                Card(Modifier.fillMaxWidth().clickable(enabled=vars.isNotEmpty()){open(candidate.dex)}){
                    Row(Modifier.padding(12.dp)){
                        Column(Modifier.weight(1f)){
                            Text("No.%03d $name".format(candidate.dex),fontWeight=FontWeight.Bold)
                            Text(if(r.accepted&&candidate==r.candidates.first())"第一候補" else "候補",style=MaterialTheme.typography.labelSmall)
                        }
                        Text("${(candidate.score*100).toInt()}%")
                    }
                }
            }
        }
        vr?.let{x->
            val rows=x.candidates.flatMap{candidate->
                candidate.recordIds.mapNotNull{id->
                    master.firstOrNull{it.id==id}?.let{candidate to it}
                }
            }
            if(rows.isNotEmpty()){
                item{Text("指人形バリエーション候補",fontWeight=FontWeight.Black)}
                items(rows,key={it.second.id}){row->
                    val candidate=row.first
                    val f=row.second
                    Card(Modifier.fillMaxWidth()){
                        Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){
                            Column(Modifier.weight(1f)){
                                Text(f.variant,fontWeight=FontWeight.Bold)
                                Text("${f.series} ${f.year}",style=MaterialTheme.typography.labelSmall)
                                Text(
                                    if(candidate.recordIds.size>1)"見た目同一候補・類似 ${(candidate.score*100).toInt()}%"
                                    else "類似 ${(candidate.score*100).toInt()}%",
                                    style=MaterialTheme.typography.labelSmall
                                )
                            }
                            Button({
                                setOwned(f.id,maxOf(1,owned[f.id]?:0))
                                msg="✓ ${f.variant} を登録しました"
                            }){Text("これで登録")}
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Detail(vars:List<Figure>,owned:Map<String,Int>,setOwned:(String,Int)->Unit,back:()->Unit){
    val first=vars.firstOrNull()?:return
    Scaffold(modifier=Modifier.testTag("detail_screen"),topBar={TopAppBar(title={Text("No.%03d ${first.pokemon}".format(first.dex))},navigationIcon={TextButton(onClick=back,modifier=Modifier.testTag("detail_back")){Text("←")}})}){pad->
        LazyColumn(Modifier.padding(pad).padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            items(vars,key={it.id}){f->
                val n=owned[f.id]?:0
                Card(Modifier.fillMaxWidth()){
                    Column(Modifier.padding(12.dp)){
                        Text(f.variant,fontWeight=FontWeight.Bold)
                        Text("${f.series} ${f.year}",style=MaterialTheme.typography.bodySmall)
                        if(f.kidsNo>0) Text("ポケモンキッズ No.${f.kidsNo}",style=MaterialTheme.typography.labelSmall)
                        Text(if(f.status=="direct_reference_ready")"実物参照特徴量あり" else "種判定中心",style=MaterialTheme.typography.labelSmall)
                        Row(verticalAlignment=Alignment.CenterVertically){
                            Text(if(n>0)"所持 ×$n" else "未所持",Modifier.weight(1f).testTag("owned_state_${f.id}"))
                            OutlinedButton({if(n>0)setOwned(f.id,n-1)},enabled=n>0){Text("−")}
                            Spacer(Modifier.width(6.dp))
                            Button(onClick={setOwned(f.id,n+1)},modifier=Modifier.testTag("plus_${f.id}")){Text("＋")}
                        }
                    }
                }
            }
        }
    }
}

private fun decodeBitmap(context:Context,uri:Uri,maxSide:Int):Bitmap{
    if(android.os.Build.VERSION.SDK_INT>=28){
        val s=ImageDecoder.createSource(context.contentResolver,uri)
        return ImageDecoder.decodeBitmap(s){d,info,_->
            val m=maxOf(info.size.width,info.size.height)
            if(m>maxSide){
                val scale=maxSide.toFloat()/m
                d.setTargetSize((info.size.width*scale).toInt(),(info.size.height*scale).toInt())
            }
            d.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }
    val opts=BitmapFactory.Options().apply{inJustDecodeBounds=true}
    context.contentResolver.openInputStream(uri).use{BitmapFactory.decodeStream(it,null,opts)}
    var sample=1
    while(maxOf(opts.outWidth/sample,opts.outHeight/sample)>maxSide*2)sample*=2
    var b=context.contentResolver.openInputStream(uri).use{BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply{inSampleSize=sample})}?:error("画像を読めません")
    val o=context.contentResolver.openInputStream(uri).use{if(it==null)1 else ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION,1)}
    val m=Matrix()
    when(o){3->m.postRotate(180f);6->m.postRotate(90f);8->m.postRotate(270f)}
    if(!m.isIdentity)b=Bitmap.createBitmap(b,0,0,b.width,b.height,m,true)
    val mx=maxOf(b.width,b.height)
    if(mx>maxSide){
        val s=maxSide.toFloat()/mx
        b=Bitmap.createScaledBitmap(b,(b.width*s).toInt(),(b.height*s).toInt(),true)
    }
    return b
}
