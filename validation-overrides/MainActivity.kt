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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
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

private data class FigureBox(val left:Float,val top:Float,val right:Float,val bottom:Float)
private data class MultiFigureResult(val candidates:List<CandidateFusion.Candidate>)

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
    val embeddingRecognizer=remember { EmbeddingRecognizer(context.applicationContext) }
    val variantRecognizer=remember { VariantRecognizer(context.applicationContext) }
    val shapeRecognizer=remember { ShapeRecognizer(context.applicationContext) }
    DisposableEffect(Unit){ onDispose { recognizer.close(); embeddingRecognizer.close(); shapeRecognizer.close() } }
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
                Tab.PHOTO -> Photo(master,owned,recognizer,embeddingRecognizer,variantRecognizer,shapeRecognizer,::setOwned){selectedDex=it}
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

@Composable private fun Photo(
    master:List<Figure>,
    owned:Map<String,Int>,
    recognizer:PokemonRecognizer,
    embedding:EmbeddingRecognizer,
    fallback:VariantRecognizer,
    shapeRecognizer:ShapeRecognizer,
    setOwned:(String,Int)->Unit,
    open:(Int)->Unit
){
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var bitmap by remember{mutableStateOf<Bitmap?>(null)}
    var aiResult by remember{mutableStateOf<PokemonRecognizer.Result?>(null)}
    var highResult by remember{mutableStateOf<EmbeddingRecognizer.Result?>(null)}
    var fusedCandidates by remember{mutableStateOf<List<CandidateFusion.Candidate>>(emptyList())}
    var highVariants by remember{mutableStateOf<EmbeddingRecognizer.VariantResult?>(null)}
    var fallbackResult by remember{mutableStateOf<VariantRecognizer.Result?>(null)}
    var busy by remember{mutableStateOf(false)}
    var msg by remember{mutableStateOf("")}
    var cameraFile by remember{mutableStateOf<File?>(null)}
    var multiMode by remember{mutableStateOf(false)}
    var figureBoxes by remember{mutableStateOf<List<FigureBox>>(emptyList())}
    var multiResults by remember{mutableStateOf<List<MultiFigureResult>>(emptyList())}
    val visualReferences=remember(master){master.filter{it.dex>0&&it.feature.isNotBlank()}}

    fun analyze(u:Uri){
        if(busy)return
        aiResult=null;highResult=null;highVariants=null;fusedCandidates=emptyList();fallbackResult=null;multiResults=emptyList();msg="";busy=true
        scope.launch{
            try{
                val b=withContext(Dispatchers.IO){decodeBitmap(context,u,1280)}
                bitmap=b
                val high=runCatching{
                    withContext(Dispatchers.IO){
                        if(embedding.isAvailable()) embedding.recognize(b) else null
                    }
                }.getOrNull()
                highResult=high

                val ai=runCatching{
                    withContext(Dispatchers.IO){recognizer.recognize(b)}
                }.getOrNull()
                aiResult=ai

                val shape=runCatching {
                    withContext(Dispatchers.IO) { if(shapeRecognizer.isAvailable())shapeRecognizer.recognize(b) else ShapeRecognizer.Result(emptyList(),emptyList()) }
                }.getOrDefault(ShapeRecognizer.Result(emptyList(),emptyList()))

                val highTop=high?.speciesCandidates?.firstOrNull()
                val aiTop=ai?.candidates?.firstOrNull()
                val decision=RecognitionPolicy.decide(
                    highTop?.let { RecognitionPolicy.Candidate(it.dex, high?.acceptedSpecies==true, high?.veryStrongSpecies==true) },
                    aiTop?.let { RecognitionPolicy.Candidate(it.dex, ai?.accepted==true, ai?.accepted==true && it.score>=0.55f) }
                )
                val fused=if(shape.shape.isNotEmpty()) ShapeFusion.rank(
                    shape.shape,
                    high?.allSpeciesCandidates.orEmpty().map { ShapeFusion.Candidate(it.dex,it.score) },
                    ai?.allCandidates.orEmpty().map { ShapeFusion.Candidate(it.dex,it.score) },
                    master.map { it.dex }.toSet(), shape.kernel, shape.prototype, shape.prototypeTemperature, shapeRecognizer.appearance, shape.appearanceFeatures
                ).map { CandidateFusion.Candidate(it.dex,it.score) } else if(high!=null && ai!=null) CandidateFusion.rank(
                    high.allSpeciesCandidates.map { CandidateFusion.Candidate(it.dex,it.score) },
                    ai.allCandidates.map { CandidateFusion.Candidate(it.dex,it.score) },
                    master.map { it.dex }.toSet()
                ) else emptyList()
                fusedCandidates=fused
                val chosenDex=fused.firstOrNull()?.dex ?: decision.dex

                if(high!=null && chosenDex!=null){
                    val variants=withContext(Dispatchers.IO){embedding.rankVariants(high,chosenDex,master)}
                    highVariants=variants
                    val topVariant=variants.candidates.firstOrNull()
                    val speciesTrusted=decision.dex==chosenDex && decision.speciesTrusted
                    val variantTrusted=variants.accepted && decision.dex==chosenDex && decision.variantEligible

                    if(variantTrusted && topVariant!=null && topVariant.recordIds.size==1){
                        val id=topVariant.recordIds.first()
                        val fig=master.firstOrNull{it.id==id}
                        if(fig!=null){
                            setOwned(id,maxOf(1,owned[id]?:0))
                            msg="✓ 高精度AI＋実物DBで ${fig.pokemon} ${fig.variant} を登録しました"
                        }
                    }else{
                        val sameDex=master.filter{it.dex==chosenDex}
                        if(speciesTrusted && sameDex.size==1){
                            val fig=sameDex.first()
                            setOwned(fig.id,maxOf(1,owned[fig.id]?:0))
                            msg="✓ ${fig.pokemon} を高信頼度で判定して登録しました"
                        }else if(topVariant!=null && topVariant.recordIds.size>1){
                            msg="ポケモンは有力候補まで絞れました。同じ見た目の発売違いがあるため自動登録はしていません"
                        }else{
                            val name=sameDex.firstOrNull()?.pokemon?:"No.$chosenDex"
                            msg="$name が有力です。誤登録防止のため候補を確認してください"
                        }
                    }
                }else{
                    val visual=withContext(Dispatchers.IO){fallback.recognize(b,visualReferences)}
                    fallbackResult=visual
                    val top=visual.candidates.firstOrNull()
                    if(visual.accepted&&top!=null&&top.recordIds.size==1){
                        val id=top.recordIds.first()
                        val fig=master.firstOrNull{it.id==id}
                        if(fig!=null){
                            setOwned(id,maxOf(1,owned[id]?:0))
                            msg="✓ 従来実物DBで ${fig.pokemon} ${fig.variant} を登録しました"
                        }
                    }else if(visual.candidates.isNotEmpty()){
                        msg="高精度AIを利用できなかったため、従来DBの候補を表示しています"
                    }else{
                        msg="候補を見つけられませんでした"
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

    fun prepareMulti(u:Uri){
        if(busy)return
        busy=true;msg="";figureBoxes=emptyList();multiResults=emptyList()
        scope.launch{
            try{
                bitmap=withContext(Dispatchers.IO){decodeBitmap(context,u,1280)}
                msg="フィギュアごとに範囲をドラッグして囲んでください（最大5体）"
            }catch(e:Exception){msg="画像を開けませんでした: ${e.message ?: e.javaClass.simpleName}"}
            finally{busy=false;cameraFile?.let{runCatching{if(it.exists())it.delete()}};cameraFile=null}
        }
    }

    fun analyzeMulti(){
        val source=bitmap?:return
        if(busy||figureBoxes.isEmpty())return
        aiResult=null;highResult=null;highVariants=null;fusedCandidates=emptyList();fallbackResult=null
        busy=true;multiResults=emptyList();msg="${figureBoxes.size}体を個別に判定しています"
        val boxes=figureBoxes.toList()
        scope.launch{
            try{
                val results=withContext(Dispatchers.IO){
                    val supported=master.map{it.dex}.toSet()
                    boxes.map{box->
                        val left=(box.left*source.width).toInt().coerceIn(0,source.width-1)
                        val top=(box.top*source.height).toInt().coerceIn(0,source.height-1)
                        val right=(box.right*source.width).toInt().coerceIn(left+1,source.width)
                        val bottom=(box.bottom*source.height).toInt().coerceIn(top+1,source.height)
                        val crop=ImageViews.ownedCrop(source,left,top,right-left,bottom-top)
                        try{
                            val high=runCatching{if(embedding.isAvailable())embedding.recognize(crop)else null}.getOrNull()
                            val ai=runCatching{recognizer.recognize(crop)}.getOrNull()
                            val shape=runCatching{if(shapeRecognizer.isAvailable())shapeRecognizer.recognize(crop)else ShapeRecognizer.Result(emptyList(),emptyList())}.getOrDefault(ShapeRecognizer.Result(emptyList(),emptyList()))
                            val ranked=if(shape.shape.isNotEmpty()) ShapeFusion.rank(
                                shape.shape,
                                high?.allSpeciesCandidates.orEmpty().map{ShapeFusion.Candidate(it.dex,it.score)},
                                ai?.allCandidates.orEmpty().map{ShapeFusion.Candidate(it.dex,it.score)},
                                supported,shape.kernel,shape.prototype,shape.prototypeTemperature,shapeRecognizer.appearance,shape.appearanceFeatures
                            ) else if(high!=null&&ai!=null) CandidateFusion.rank(
                                high.allSpeciesCandidates.map{CandidateFusion.Candidate(it.dex,it.score)},
                                ai.allCandidates.map{CandidateFusion.Candidate(it.dex,it.score)}
                            ).map{ShapeFusion.Candidate(it.dex,it.score)}
                            else if(high!=null) high.allSpeciesCandidates.map{ShapeFusion.Candidate(it.dex,it.score)}
                            else ai?.allCandidates.orEmpty().filter{it.dex in supported}.map{ShapeFusion.Candidate(it.dex,it.score)}
                            MultiFigureResult(ranked.take(5).map{CandidateFusion.Candidate(it.dex,it.score)})
                        }finally{crop.recycle()}
                    }
                }
                multiResults=results
                msg="判定完了。各枠の候補を確認してください（複数体モードでは自動登録しません）"
            }catch(e:Exception){msg="複数体の判定に失敗しました: ${e.message ?: e.javaClass.simpleName}"}
            finally{busy=false}
        }
    }

    val pick=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){it?.let{u->if(multiMode)prepareMulti(u)else analyze(u)}}
    val take=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()){ok->
        if(ok){
            cameraFile?.let{file->
                val uri=FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",file)
                if(multiMode)prepareMulti(uri)else analyze(uri)
            }
        }else{
            cameraFile?.let{runCatching{if(it.exists())it.delete()}}
            cameraFile=null
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        item{
            Text("写真で判定",fontWeight=FontWeight.Black)
            Row(verticalAlignment=Alignment.CenterVertically){
                Switch(checked=multiMode,onCheckedChange={
                    multiMode=it;figureBoxes=emptyList();multiResults=emptyList();aiResult=null;highResult=null
                    highVariants=null;fusedCandidates=emptyList();fallbackResult=null;msg=""
                })
                Text("複数体モード（最大5体）",fontWeight=FontWeight.Bold)
            }
            Text(if(multiMode)"画像を選んだあと、各フィギュアをドラッグで囲んでください。重なりを含む範囲は別々に囲ってください。" else "1体だけ大きく写すと精度が上がります。背景はできるだけ単色がおすすめです。",style=MaterialTheme.typography.bodySmall)
            Row{
                Button({pick.launch("image/*")},enabled=!busy){Text("画像を選ぶ")}
                Spacer(Modifier.width(8.dp))
                Button({
                    val file=File(context.cacheDir,"pokepino_${System.currentTimeMillis()}.jpg")
                    cameraFile=file
                    take.launch(FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",file))
                },enabled=!busy){Text("写真を撮る")}
            }
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            bitmap?.let{b->
                if(multiMode) MultiSelectImage(b,figureBoxes){figureBoxes=it;multiResults=emptyList()}
                else Image(b.asImageBitmap(),null,Modifier.fillMaxWidth().height(260.dp),contentScale=ContentScale.Fit)
            }
            if(multiMode&&bitmap!=null){
                Row(verticalAlignment=Alignment.CenterVertically){
                    Text("選択 ${figureBoxes.size}/5",Modifier.weight(1f))
                    TextButton(onClick={if(figureBoxes.isNotEmpty())figureBoxes=figureBoxes.dropLast(1)},enabled=figureBoxes.isNotEmpty()){Text("最後の枠を戻す")}
                    Button(onClick=::analyzeMulti,enabled=!busy&&figureBoxes.isNotEmpty()){Text("選択範囲を判定")}
                }
            }
            if(msg.isNotBlank()) Text(msg,fontWeight=FontWeight.Bold)
        }

        if(multiResults.isNotEmpty()){
            item{Text("個体ごとの判定結果",fontWeight=FontWeight.Black)}
            itemsIndexed(multiResults){index,result->
                Card(Modifier.fillMaxWidth()){
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                        Text("囲み ${index+1}",fontWeight=FontWeight.Bold)
                        result.candidates.take(3).forEachIndexed{rank,candidate->
                            val figure=master.firstOrNull{it.dex==candidate.dex}
                            TextButton(onClick={open(candidate.dex)},enabled=figure!=null){Text("${rank+1}. ${figure?.pokemon ?: "No.${candidate.dex}"}")}
                        }
                        if(result.candidates.isEmpty())Text("候補なし。枠を広げて再判定してください",style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if(fusedCandidates.isNotEmpty()) {
            item{Text("総合判定の候補",fontWeight=FontWeight.Black)}
            items(fusedCandidates){candidate->
                val fig=master.firstOrNull{it.dex==candidate.dex}
                Card(Modifier.fillMaxWidth().clickable(enabled=fig!=null){open(candidate.dex)}){
                    Text(fig?.pokemon?:"No.${candidate.dex}",Modifier.padding(12.dp))
                }
            }
        }

        highResult?.let{r->
            item{Text("高精度・実物写真候補",fontWeight=FontWeight.Black)}
            items(r.speciesCandidates){candidate->
                val fig=master.firstOrNull{it.dex==candidate.dex}
                val name=fig?.pokemon?:"No.${candidate.dex}"
                Card(Modifier.fillMaxWidth().clickable(enabled=fig!=null){open(candidate.dex)}){
                    Row(Modifier.padding(12.dp)){
                        Column(Modifier.weight(1f)){
                            Text("No.%03d $name".format(candidate.dex),fontWeight=FontWeight.Bold)
                            Text(if(candidate==r.speciesCandidates.first())"実物DB 第一候補" else "実物DB候補",style=MaterialTheme.typography.labelSmall)
                        }
                        Text("${(candidate.score*100).toInt()}%")
                    }
                }
            }
        }

        aiResult?.let{r->
            item{Text("ポケモンAI補助候補",fontWeight=FontWeight.Black)}
            items(r.candidates){candidate->
                val fig=master.firstOrNull{it.dex==candidate.dex}
                val name=fig?.pokemon?:"No.${candidate.dex}"
                Card(Modifier.fillMaxWidth().clickable(enabled=fig!=null){open(candidate.dex)}){
                    Row(Modifier.padding(12.dp)){
                        Column(Modifier.weight(1f)){
                            Text("No.%03d $name".format(candidate.dex),fontWeight=FontWeight.Bold)
                            Text(if(candidate==r.candidates.first())"AI 第一候補" else "AI候補",style=MaterialTheme.typography.labelSmall)
                        }
                        Text("${(candidate.score*100).toInt()}%")
                    }
                }
            }
        }

        highVariants?.let{x->
            val rows=x.candidates.flatMap{candidate->
                candidate.recordIds.mapNotNull{id->
                    master.firstOrNull{it.id==id}?.let{candidate to it}
                }
            }
            if(rows.isNotEmpty()){
                item{Text("指人形バリエーション候補",fontWeight=FontWeight.Black)}
                items(rows,key={it.second.id}){row->
                    val candidate=row.first
                    val fig=row.second
                    Card(Modifier.fillMaxWidth()){
                        Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){
                            Column(Modifier.weight(1f)){
                                Text(fig.variant,fontWeight=FontWeight.Bold)
                                Text("${fig.series} ${fig.year}",style=MaterialTheme.typography.labelSmall)
                                Text(
                                    if(candidate.recordIds.size>1)"同型候補・類似 ${(candidate.score*100).toInt()}%"
                                    else "類似 ${(candidate.score*100).toInt()}%",
                                    style=MaterialTheme.typography.labelSmall
                                )
                            }
                            Button({
                                setOwned(fig.id,maxOf(1,owned[fig.id]?:0))
                                msg="✓ ${fig.variant} を登録しました"
                            }){Text("これで登録")}
                        }
                    }
                }
            }
        }

        fallbackResult?.let{x->
            if(x.candidates.isNotEmpty()){
                item{Text("従来DB候補",fontWeight=FontWeight.Black)}
                items(x.candidates){candidate->
                    val fig=candidate.recordIds.asSequence().mapNotNull{id->master.firstOrNull{it.id==id}}.firstOrNull()
                    if(fig!=null) Card(Modifier.fillMaxWidth().clickable{open(fig.dex)}){
                        Row(Modifier.padding(12.dp)){
                            Text("${fig.pokemon} ${fig.variant}",Modifier.weight(1f))
                            Text("${(candidate.score*100).toInt()}%")
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun MultiSelectImage(bitmap:Bitmap,boxes:List<FigureBox>,onBoxes:(List<FigureBox>)->Unit){
    var start by remember(bitmap){mutableStateOf<Offset?>(null)}
    var current by remember(bitmap){mutableStateOf<Offset?>(null)}
    Canvas(Modifier.fillMaxWidth().height(260.dp).pointerInput(bitmap,boxes.size){
        detectDragGestures(
            onDragStart={start=it;current=it},
            onDragCancel={start=null;current=null},
            onDragEnd={
                val a=start;val b=current
                if(a!=null&&b!=null&&boxes.size<5){
                    val scale=minOf(size.width/bitmap.width.toFloat(),size.height/bitmap.height.toFloat())
                    val imageW=bitmap.width*scale;val imageH=bitmap.height*scale
                    val ox=(size.width-imageW)/2f;val oy=(size.height-imageH)/2f
                    val x0=minOf(a.x,b.x).coerceIn(ox,ox+imageW);val x1=maxOf(a.x,b.x).coerceIn(ox,ox+imageW)
                    val y0=minOf(a.y,b.y).coerceIn(oy,oy+imageH);val y1=maxOf(a.y,b.y).coerceIn(oy,oy+imageH)
                    if(x1-x0>=24f&&y1-y0>=24f)onBoxes(boxes+FigureBox((x0-ox)/imageW,(y0-oy)/imageH,(x1-ox)/imageW,(y1-oy)/imageH))
                }
                start=null;current=null
            },
            onDrag={change,_->current=change.position}
        )
    }){
        val scale=minOf(size.width/bitmap.width.toFloat(),size.height/bitmap.height.toFloat())
        val imageW=bitmap.width*scale;val imageH=bitmap.height*scale
        val ox=(size.width-imageW)/2f;val oy=(size.height-imageH)/2f
        drawImage(bitmap.asImageBitmap(),dstSize=androidx.compose.ui.unit.IntSize(imageW.toInt(),imageH.toInt()),dstOffset=androidx.compose.ui.unit.IntOffset(ox.toInt(),oy.toInt()))
        boxes.forEachIndexed{index,box->
            val rect=Rect(ox+box.left*imageW,oy+box.top*imageH,ox+box.right*imageW,oy+box.bottom*imageH)
            drawRect(Color(0xFFE64B3C),rect.topLeft,rect.size,style=Stroke(width=4f))
            drawContext.canvas.nativeCanvas.drawText("${index+1}",rect.left+6f,rect.top+22f,android.graphics.Paint().apply{color=android.graphics.Color.WHITE;textSize=22f;isFakeBoldText=true})
        }
        val a=start;val b=current
        if(a!=null&&b!=null){
            val rect=Rect(minOf(a.x,b.x),minOf(a.y,b.y),maxOf(a.x,b.x),maxOf(a.y,b.y))
            drawRect(Color(0xFF1B8F5A),rect.topLeft,rect.size,style=Stroke(width=4f))
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
