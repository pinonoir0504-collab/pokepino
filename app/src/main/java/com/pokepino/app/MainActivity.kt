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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File\nimport java.io.ByteArrayInputStream\nimport java.util.zip.GZIPInputStream

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
        val a = JSONArray(json)
        val out = ArrayList<Figure>(a.length())
        repeat(a.length()) { i ->
            val r = a.getJSONArray(i)
            val refs = r.optJSONArray(8)?.let { x ->
                (0 until x.length()).mapNotNull { j ->
                    x.optString(j).takeIf(String::isNotBlank)
                }
            } ?: emptyList()
            out += Figure(
                id = r.optString(0),
                dex = r.optInt(1),
                pokemon = r.optString(2),
                variant = r.optString(3),
                series = r.optString(4),
                year = r.optString(5),
                visualGroupId = r.optString(6, r.optString(0)),
                status = r.optString(7, "species_only"),
                refs = refs
            )
        }
        return out
    }

