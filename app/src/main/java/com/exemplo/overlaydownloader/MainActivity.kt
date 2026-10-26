package com.exemplo.overlaydownloader

import android.content.ContentValues
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                val url = extractInstagramUrl(sharedText)

                if (url != null) {
                    showToast("Buscando foto em alta resolução...")
                    processDownload(url)
                } else {
                    showToast("Link não reconhecido. Certifique-se de compartilhar um post do Instagram.")
                    finish()
                }
            } else {
                showToast("Para baixar, toque no aviãozinho do Instagram e escolha 'Compartilhar'.")
                finish()
            }
        } catch (e: Exception) {
            showToast("Erro ao abrir: ${e.localizedMessage}")
            finish()
        }
    }

    private fun extractInstagramUrl(text: String): String? {
        // Aceita posts normais (/p/), reels (/reel/), tv (/tv/) e remove parâmetros de rastreamento
        val pattern = Pattern.compile("https?://(www\\.)?instagram\\.com/(p|reel|tv)/[a-zA-Z0-9_-]+")
        val matcher = pattern.matcher(text)
        return if (matcher.find()) matcher.group(0) else null
    }

    private fun processDownload(postUrl: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Tentativa 1: Leitura direta das tags abertas de alta resolução do Instagram
                val directImageUrl = fetchFromDirectHtml(postUrl)

                if (!directImageUrl.isNullOrEmpty()) {
                    saveMediaToStorage(directImageUrl)
                    return@launch
                }

                // Tentativa 2: Fallback via endpoint aberto de mídia
                val fallbackUrl = fetchFromFallback(postUrl)
                if (!fallbackUrl.isNullOrEmpty()) {
                    saveMediaToStorage(fallbackUrl)
                    return@launch
                }

                showToast("Não foi possível encontrar a imagem. O post pode ser privado.")
                finish()

            } catch (e: Exception) {
                showToast("Falha na busca: ${e.localizedMessage}")
                finish()
            }
        }
    }

    private fun fetchFromDirectHtml(postUrl: String): String? {
        val request = Request.Builder()
            .url(postUrl)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .addHeader("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8")
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) return null

        val html = response.body?.string() ?: return null

        // Extrai a meta tag og:image que contém a foto original
        val ogPattern = Pattern.compile("<meta property=\"og:image\" content=\"([^\"]+)\"")
        val matcher = ogPattern.matcher(html)
        if (matcher.find()) {
            val rawUrl = matcher.group(1) ?: return null
            return rawUrl.replace("&amp;", "&")
        }
        return null
    }

    private fun fetchFromFallback(postUrl: String): String? {
        val cleanUrl = postUrl.split("?").first()
        val requestUrl = "https://api.vkrdown.com/api/insta?url=$cleanUrl"

        val request = Request.Builder()
            .url(requestUrl)
            .addHeader("User-Agent", "Mozilla/5.0")
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) return null

        val body = response.body?.string() ?: return null
        val json = JSONObject(body)

        if (json.optBoolean("success", false) || json.has("data")) {
            val data = json.optJSONObject("data")
            if (data != null) {
                val media = data.optJSONArray("media")
                if (media != null && media.length() > 0) {
                    return media.getJSONObject(0).optString("url")
                }
            }
        }
        return null
    }

    private fun saveMediaToStorage(mediaUrl: String) {
        try {
            showToast("Baixando arquivo...")

            val request = Request.Builder()
                .url(mediaUrl)
                .addHeader("User-Agent", "Mozilla/5.0")
                .build()

            val response = httpClient.newCall(request).execute()
            val inputStream: InputStream? = response.body?.byteStream()

            if (inputStream != null) {
                val fileName = "INSTA_${System.currentTimeMillis()}.jpg"
                val resolver = contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/OverlayDownloads")
                }

                val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (imageUri != null) {
                    resolver.openOutputStream(imageUri)?.use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                    showToast("✓ Foto salva na galeria!")
                } else {
                    showToast("Erro ao gravar arquivo na galeria.")
                }
            } else {
                showToast("Não foi possível carregar os dados da imagem.")
            }
        } catch (e: Exception) {
            showToast("Erro ao salvar: ${e.localizedMessage}")
        } finally {
            finish()
        }
    }

    private fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }
}
