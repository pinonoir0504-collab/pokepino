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
import androidx.activity.ComponentActivity
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

class CollectionActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("owned", MODE_PRIVATE) }
    private val picked by lazy { getSharedPreferences("picked_gallery_photo", MODE_PRIVATE) }
    private lateinit var webView: WebView

    private fun buildCatalog(): String {
        val names = HashMap<String, Int>()
        val rows = JSONArray()
        val seenId = HashSet<String>()
        val seenProduct = HashSet<String>()
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
            if (id.isBlank() || !seenId.add(id)) return
            val identity = listOf(pokemon, variant, series, year).joinToString("|")
            if (!seenProduct.add(identity)) return
            rows.put(JSONArray().put(id).put(dex).put(pokemon).put(variant)
                .put(series).put(year).put(kind).put(photo).put(url))
        }
        fun releaseKey(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .lowercase()
            .replace(Regex("2024|2025|2026|発売予定|ポケモンキッズ|スペシャル|30周年|30|編|vol\\.?|[^\\p{L}\\p{N}]+"), "")
        val official = JSONObject(assets.open("official_catalog_2024_2026.json").bufferedReader().use { it.readText() })
            .getJSONArray("series")
        val officialKeys = HashSet<String>()
        for (i in 0 until official.length()) {
            val s = official.getJSONObject(i)
            officialKeys.add(releaseKey(s.getString("series")) + ":" + s.getString("releaseDate").take(4))
        }
        val byLength = names.entries.sortedByDescending { it.key.length }
        for (s in 0 until official.length()) {
            val series = official.getJSONObject(s)
            val list = series.getJSONArray("items")
            val year = series.getString("releaseDate")
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
        // Generic legacy records are species placeholders, not proven release variants.
        // Preserve any legacy items the user had actually marked as owned, clearly flagged.
        for (collection in listOf(references, legacy)) {
            for (i in 0 until collection.length()) {
                val r = collection.optJSONArray(i) ?: continue
                if (r.optString(4) == "ポケモンキッズ一覧") continue
                if (collection === references &&
                    (releaseKey(r.optString(4)) + ":" + r.optString(5).take(4)) in officialKeys) continue
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
        val galleryData = assets.open("official_galleries_2024_2026.json").bufferedReader().use { it.readText() }
        val galleryUrls = HashSet<String>()
        val galleryObjects = JSONObject(galleryData).getJSONObject("series")
        for (key in galleryObjects.keys()) {
            val imgs = galleryObjects.getJSONObject(key).getJSONArray("images")
            for (i in 0 until imgs.length()) galleryUrls.add(imgs.getString(i))
        }
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
            @JavascriptInterface fun getGalleries(): String = galleryData
            @JavascriptInterface fun getPickedPhotos(): String {
                val obj = JSONObject()
                for ((key, value) in picked.all) if (value is String) obj.put(key, value)
                return obj.toString()
            }
            @JavascriptInterface fun setPickedPhoto(id: String, url: String) {
                if (!id.matches(Regex("OFF-[0-9]{2}-[0-9]{2}"))) return
                if (url !in galleryUrls) return
                picked.edit().putString(id, url).apply()
            }
            @JavascriptInterface fun getOwned(): String {
                val result = JSONObject()
                for ((key, value) in prefs.all) if (value is Int && value > 0) result.put(key, value)
                return result.toString()
            }
            @JavascriptInterface fun setOwned(id: String, n: Int) {
                if (id.length > 100 || n < 0 || n > 999) return
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
        webView.evaluateJavascript("window.goBackFromAndroid && window.goBackFromAndroid()", null)
    }
}
