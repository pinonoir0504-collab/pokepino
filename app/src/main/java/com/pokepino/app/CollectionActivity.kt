package com.pokepino.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

class CollectionActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("owned", MODE_PRIVATE) }
    private lateinit var webView: WebView

    private fun reportBackup(ok: Boolean, message: String) {
        if (::webView.isInitialized) {
            webView.evaluateJavascript(
                "window.pokepinoBackupResult && window.pokepinoBackupResult(" +
                    ok + "," + JSONObject.quote(message) + ")", null
            )
        }
    }
    private fun backupJson(): String {
        val counts = JSONObject()
        for ((id, value) in prefs.all) if (value is Int && value > 0) {
            counts.put(id, value)
        }
        return JSONObject().put("format", "pokepino-collection")
            .put("schema", 1)
            .put("exportedAt", java.time.Instant.now().toString())
            .put("owned", counts).toString(2)
    }
    private fun mergeBackup(text: String): Int {
        val root = JSONObject(text)
        require(root.optString("format") == "pokepino-collection" &&
            root.optInt("schema") == 1) { "ポケピーノ形式のファイルではありません" }
        val items = root.optJSONObject("owned")
            ?: throw IllegalArgumentException("所持データがありません")
        require(items.length() <= 10000) { "件数が多すぎます" }
        val checked = LinkedHashMap<String, Int>()
        for (id in items.keys()) {
            val count = items.opt(id)
            require(id.isNotBlank() && id.length <= 100 &&
                count is Int && count in 0..999) { "不正な所持数が含まれています" }
            checked[id] = count
        }
        // Never delete locally owned figures on import.
        val editor = prefs.edit()
        var changed = 0
        for ((id, count) in checked) {
            if (count > prefs.getInt(id, 0)) {
                editor.putInt(id, count)
                changed++
            }
        }
        check(editor.commit()) { "所持データの保存に失敗しました" }
        return changed
    }
    private val exportFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) runCatching {
            contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                it.write(backupJson())
            } ?: error("保存先を開けません")
        }.onSuccess { reportBackup(true, "所持データを書き出しました") }
         .onFailure { reportBackup(false, it.message ?: "書き出し失敗") }
    }
    private val restoreFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) runCatching {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("ファイルを開けません")
            require(bytes.size <= 2_000_000) { "ファイルが大きすぎます" }
            mergeBackup(String(bytes, Charsets.UTF_8))
        }.onSuccess {
            if (::webView.isInitialized) webView.evaluateJavascript(
                "window.reloadOwnedFromAndroid && window.reloadOwnedFromAndroid()", null)
            reportBackup(true, "復元しました（" + it + "件を更新）")
        }.onFailure { reportBackup(false, it.message ?: "復元に失敗しました") }
    }

    private fun buildCatalog(): String {
        val names = HashMap<String, Int>()
        val rows = JSONArray()
        val seenId = HashSet<String>()
        val seenProduct = HashSet<String>()
        val photoOwners = HashMap<String, String>()
        val original = listOf("dbgz_00.txt", "dbgz_01a.txt", "dbgz_01b.txt", "dbgz_02a.txt",
            "dbgz_02b.txt", "dbgz_03a.txt", "dbgz_03b.txt")
            .joinToString("") { assets.open(it).bufferedReader().use { r -> r.readText() } }
        val legacyText = GZIPInputStream(ByteArrayInputStream(Base64.decode(original, Base64.DEFAULT)))
            .bufferedReader().use { it.readText() }
        val legacy = JSONArray(legacyText)
        val references = JSONArray(assets.open("catalog_v2.json").bufferedReader().use { it.readText() })
        for (collection in listOf(legacy, references)) {
            for (i in 0 until collection.length()) {
                val r = collection.optJSONArray(i) ?: continue
                val name = r.optString(2)
                val dex = r.optInt(1)
                if (dex > 0 && name.isNotBlank() && !names.containsKey(name)) names[name] = dex
            }
        }
        fun insert(id:String, dex:Int, pokemon:String, variant:String, series:String, year:String,
                   kind:String, photo:String, url:String) {
            if (id.isBlank() || id in seenId) return
            val identity = listOf(pokemon, variant, series, year).joinToString("|")
            if (identity in seenProduct && prefs.getInt(id, 0) <= 0) return
            seenId.add(id)
            seenProduct.add(identity)
            val review = setOf("poke1_139.jpg", "poke_XY_098.jpg",
                "poke_SS_774.jpg", "poke_SS_816.jpg", "poke_SV_889.jpg",
                "poke_kimeBW01_530.jpg", "poke2_220.jpg")
            val imageStatus = when {
                photo.isBlank() -> "none"
                review.any { photo.endsWith(it) } -> "needs_review"
                photoOwners[photo] != null && photoOwners[photo] != pokemon -> "needs_review"
                else -> "reference_unverified"
            }
            if (photo.isNotBlank()) photoOwners.putIfAbsent(photo, pokemon)
            rows.put(JSONArray().put(id).put(dex).put(pokemon).put(variant)
                .put(series).put(year).put(kind).put(photo).put(url).put(imageStatus))
        }
        fun releaseKey(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .lowercase()
            .replace(Regex("2024|2025|2026|発売予定|ポケモンキッズ|スペシャル|30周年|30|編|vol\\.?|[^\\p{L}\\p{N}]+"), "")
        val official = JSONObject(assets.open("official_catalog_2024_2026.json").bufferedReader().use { it.readText() })
            .getJSONArray("series")
        val officialKeys = HashSet<String>()
        val modernSources = HashSet<String>()
        val futureSources = HashSet<String>()
        for (i in 0 until official.length()) {
            val s = official.getJSONObject(i)
            officialKeys.add(releaseKey(s.getString("series")) + ":" + s.getString("releaseDate").take(4))
            modernSources.add(s.getString("source"))
            if (s.optBoolean("planned") || s.getString("releaseDate") > "2026-10-01") {
                futureSources.add(s.getString("source"))
            }
        }
        val byLength = names.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.key.length }.thenBy { it.key })
        for (s in 0 until official.length()) {
            val series = official.getJSONObject(s)
            val list = series.getJSONArray("items")
            val year = series.getString("releaseDate")
            // The user's inventory is as of 2026-10-01, not advertised future stock.
            if (series.optBoolean("planned", false) || year > "2026-10-01") continue
            for (i in 0 until list.length()) {
                val variant = list.getString(i)
                val preferred = when {
                    variant.contains("ソウブレイズ") -> "ソウブレイズ"
                    variant.startsWith("ピカチュウ&") || variant.startsWith("ピカチュウ＆") -> "ピカチュウ"
                    else -> ""
                }
                val species = if (preferred.isNotBlank()) names[preferred]?.let { preferred to it }
                    else null
                val speciesFound = species ?: byLength.firstOrNull { variant.contains(it.key) }?.let { it.key to it.value }
                val name = speciesFound?.first ?: variant.substringBefore('（').substringBefore('／')
                val id = "OFF-" + s.toString().padStart(2, '0') + "-" + i.toString().padStart(2, '0')
                insert(id, speciesFound?.second ?: 0, name, variant, series.getString("series"), year,
                    if (series.optBoolean("planned")) "planned" else "official", "", series.getString("source"))
            }
        }
        // Double-audited official release groups missing from the previous historical index.
        val auditExtra = JSONObject(
            assets.open("audit_verified_official_2014_2022.json")
                .bufferedReader().use { it.readText() }
        ).getJSONArray("series")
        val newOfficialSources = HashSet<String>()
        for (s in 0 until auditExtra.length()) {
            val release = auditExtra.getJSONObject(s)
            val source = release.getString("source")
            val date = release.getString("releaseDate")
            if (date > "2026-10-01") continue
            val title = release.getString("series")
            newOfficialSources.add(source)
            val items = release.getJSONArray("items")
            for (i in 0 until items.length()) {
                val variant = items.getString(i)
                val species = when {
                    variant.startsWith("ピカチュウ＆") || variant.startsWith("ピカチュウ&") -> "ピカチュウ"
                    variant.contains("サトシのメルタン") -> "メルタン"
                    variant.contains("ソウブレイズ") -> "ソウブレイズ"
                    else -> byLength.firstOrNull { variant.contains(it.key) }?.key
                }
                val pokemon = species ?: variant.substringBefore('（').substringBefore('(')
                val id = "CHK-" + s.toString().padStart(2,'0') + "-" + i.toString().padStart(3,'0')
                insert(id, names[pokemon] ?: 0, pokemon, variant, title, date,
                    "official", "", source)
            }
        }
        Log.i("PokepinoCatalog", "New official releases: " + auditExtra.length())
        // Track documented historic launches separately from visually identical reissues.
        val historical = JSONObject(
            assets.open("historical_releases_1996_2023.json")
                .bufferedReader().use { it.readText() }
        ).getJSONArray("series")
        val historicalKeys = HashSet<String>()
        for (s in 0 until historical.length()) {
            val release = historical.getJSONObject(s)
            val name = release.getString("series")
            val date = release.getString("releaseDate")
            val source = release.getString("source")
            val grade = release.getString("sourceGrade")
            val list = release.getJSONArray("items")
            historicalKeys.add(releaseKey(name) + ":" + date.take(4))
            for (i in 0 until list.length()) {
                val variant = list.getString(i)
                val preferred = when {
                    variant.startsWith("ピカチュウ＆") || variant.startsWith("ピカチュウ&") -> "ピカチュウ"
                    variant.contains("ソウブレイズ") -> "ソウブレイズ"
                    else -> ""
                }
                val named = if (preferred.isNotEmpty())
                    names[preferred]?.let { preferred to it } else null
                val species = named ?: byLength.firstOrNull {
                    variant.contains(it.key)
                }?.let { it.key to it.value }
                val pokemon = species?.first ?: variant
                    .substringBefore('（').substringBefore('(').substringBefore('＆')
                val key = "HIS-" + s.toString().padStart(2, '0') +
                    "-" + i.toString().padStart(3, '0')
                // The 1996 first wave has official total but independent source for names.
                val status = if (grade == "official") "official"
                    else "reference"
                insert(key, species?.second ?: 0, pokemon, variant, name, date,
                    status, "", source)
            }
        }
        Log.i("PokepinoCatalog", "Historic launches indexed: " + historical.length())
        // Generic legacy records are species placeholders, not proven release variants.
        // Preserve any legacy items the user had actually marked as owned, clearly flagged.
        for (collection in listOf(references, legacy)) {
            for (i in 0 until collection.length()) {
                val r = collection.optJSONArray(i) ?: continue
                if (r.optString(4) == "ポケモンキッズ一覧") continue
                if (collection === references) {
                    val source = r.optJSONObject(9)?.optString("source").orEmpty()
                    // Old reference rows can have already-announced December 2026 figures.
                    if (source in futureSources || source in modernSources ||
                        source in newOfficialSources) continue
                    val releaseDate = r.optString(5)
                    if (releaseDate.length >= 7 && releaseDate > "2026-10-01" &&
                        releaseDate.startsWith("2026-")) continue
                    val releaseMatch = releaseKey(r.optString(4)) + ":" + r.optString(5).take(4)
                    if (releaseMatch in officialKeys || releaseMatch in historicalKeys) continue
                }
                if (collection === legacy && prefs.getInt(r.optString(0),0) <= 0) continue
                val refs = r.optJSONArray(8)
                var photo = ""
                if (refs != null) for (j in 0 until refs.length()) {
                    val url = refs.optString(j)
                    if (url.startsWith("https://") && !url.contains("pokeapi", true) &&
                        !url.contains("noimage", true) && !url.contains("placeholder", true)) {
                        photo = url
                        break
                    }
                }
                val meta = r.optJSONObject(9)
                val variant = r.optString(3)
                val originalName = r.optString(2)
                // Two confirmed substring-matcher mistakes in the historical source.
                val corrected = when {
                    originalName == "ゴースト" && variant.contains("ソウブレイズ") ->
                        937 to "ソウブレイズ"
                    originalName == "ポリゴン" && (variant.contains("ポリゴン２") || variant.contains("ポリゴン2")) ->
                        233 to "ポリゴン２"
                    else -> r.optInt(1) to originalName
                }
                insert(r.optString(0), corrected.first, corrected.second,
                    if (collection === legacy) "旧版の仮登録（発売年・造形未確定）" else variant,
                    r.optString(4), r.optString(5),
                    if (collection === legacy) "legacy_unverified" else "reference", photo,
                    meta?.optString("source", "") ?: "")
            }
        }
        // One provable hole in the 1997 WHF 60-figure lineup: No.078 ハクリュー.
        insert("WHF1997-078",148,"ハクリュー","No078 ハクリュー（クリア版）",
            "次世代ワールドホビーフェア限定バージョン1997","1997",
            "reference","","https://www.yubi-nin.jp/pokemon/poke_clear/WHF1997.html")
        Log.i("PokepinoCatalog", "Bundled records: " + rows.length() + ", official groups: " + official.length())
        return rows.toString()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val catalog = runCatching { buildCatalog() }.getOrElse {
            Log.e("PokepinoCatalog", "Failed to open catalog", it)
            "[]"
        }
        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(object {
            @JavascriptInterface fun getCatalog(): String = catalog
            @JavascriptInterface fun exportBackup() {
                runOnUiThread {
                    exportFile.launch("Pokepino_所持データ_" + java.time.LocalDate.now() + ".json")
                }
            }
            @JavascriptInterface fun restoreBackup() {
                runOnUiThread { restoreFile.launch(arrayOf("application/json", "text/plain")) }
            }
            @JavascriptInterface fun getOwned(): String {
                val result = JSONObject()
                for ((key, value) in prefs.all) if (value is Int && value > 0) result.put(key, value)
                return result.toString()
            }
            @JavascriptInterface fun setOwned(id: String, n: Int) {
                if (id.isBlank() || id.length > 100 || n < 0 || n > 999) return
                prefs.edit().putInt(id, n).apply()
            }
            @JavascriptInterface fun openReference(url: String) {
                val host = Uri.parse(url).host ?: return
                if (!url.startsWith("https://") || host !in listOf("www.yubi-nin.jp","yubi-nin.jp")) return
                runOnUiThread { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
            }
            @JavascriptInterface fun openOfficial(url: String) {
                if (!url.startsWith("https://www.bandai.co.jp/candy/")) return
                runOnUiThread { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
            }
        }, "Native")
        setContentView(webView)
        webView.loadUrl("file:///android_asset/collection.html")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!::webView.isInitialized) {
            finish()
            return
        }
        webView.evaluateJavascript(
            "window.goBackFromAndroid ? window.goBackFromAndroid() : false"
        ) { result ->
            // Return to Android when already on the collection root.
            if (result != "true") finish()
        }
    }
}
