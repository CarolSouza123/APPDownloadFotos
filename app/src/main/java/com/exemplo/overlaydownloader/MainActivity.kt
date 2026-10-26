package com.exemplo.overlaydownloader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {

    private lateinit var etLink: EditText
    private lateinit var btnDownload: Button
    private lateinit var btnCopyLogs: Button
    private lateinit var tvLogs: TextView

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etLink = findViewById(R.id.etLink)
        btnDownload = findViewById(R.id.btnDownload)
        btnCopyLogs = findViewById(R.id.btnCopyLogs)
        tvLogs = findViewById(R.id.tvLogs)

        appendLog("App inicializado com sucesso.")

        btnDownload.setOnClickListener {
            val link = etLink.text.toString().trim()
            if (link.isNotEmpty()) {
                val cleanUrl = extractInstagramUrl(link)
                if (cleanUrl != null) {
                    startProcess(cleanUrl)
                } else {
                    appendLog("ERRO: Link inválido.")
                }
            } else {
                appendLog("AVISO: Cole um link antes de clicar.")
            }
        }

        btnCopyLogs.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Logs", tvLogs.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Logs copiados para a área de transferência!", Toast.LENGTH_SHORT).show()
        }

        // Verifica se foi aberto via "Compartilhar"
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            appendLog("Link recebido via Compartilhar.")
            etLink.setText(sharedText)
            val cleanUrl = extractInstagramUrl(sharedText)
            if (cleanUrl != null) {
                startProcess(cleanUrl)
            } else {
                appendLog("ERRO: Link recebido não é do Instagram.")
            }
        }
    }

    private fun appendLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        Handler(Looper.getMainLooper()).post {
            tvLogs.append("[$time] $msg\n")
        }
    }

    private fun extractInstagramUrl(text: String): String? {
        val pattern = Pattern.compile("https?://(www\\.)?instagram\\.com/(p|reel|tv)/[a-zA-Z0-9_-]+")
        val matcher = pattern.matcher(text)
        return if (matcher.find()) matcher.group(0) else null
    }

    private fun startProcess(postUrl: String) {
        appendLog("Iniciando busca para: $postUrl")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                appendLog("Tentativa 1: Lendo metadados diretos...")
                val directUrl = fetchFromDirectHtml(postUrl)

                if (!directUrl.isNullOrEmpty()) {
                    appendLog("Imagem encontrada via HTML direto!")
                    saveImage(directUrl)
                    return@launch
                }

                appendLog("Tentativa 2: Consultando API auxiliar...")
                val fallbackUrl = fetchFromFallback(postUrl)
                if (!fallbackUrl.isNullOrEmpty()) {
                    appendLog("Imagem encontrada via API auxiliar!")
                    saveImage(fallbackUrl)
                    return@launch
                }

                appendLog("ERRO: Nenhuma mídia pública encontrada.")
            } catch (e: Exception) {
                appendLog("EXCEÇÃO: ${e.javaClass.simpleName} - ${e.message}")
            }
        }
    }

    private fun fetchFromDirectHtml(postUrl: String): String? {
        return try {
            val request = Request.Builder()
                .url(postUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()

            val response = httpClient.newCall(request).execute()
            appendLog("HTML status code: ${response.code}")
            if (!response.isSuccessful) return null

            val html = response.body?.string() ?: return null
            val matcher = Pattern.compile("<meta property=\"og:image\" content=\"([^\"]+)\"").matcher(html)
            if (matcher.find()) {
                matcher.group(1)?.replace("&amp;", "&")
            } else {
                null
            }
        } catch (e: Exception) {
            appendLog("Falha HTML: ${e.message}")
            null
        }
    }

    private fun fetchFromFallback(postUrl: String): String? {
        return try {
            val clean = postUrl.split("?").first()
            val request = Request.Builder()
                .url("https://api.vkrdown.com/api/insta?url=$clean")
                .build()

            val response = httpClient.newCall(request).execute()
            appendLog("Fallback status code: ${response.code}")
            if (!response.isSuccessful) return null

            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            val data = json.optJSONObject("data")
            val media = data?.optJSONArray("media")
            if (media != null && media.length() > 0) {
                media.getJSONObject(0).optString("url")
            } else {
                null
            }
        } catch (e: Exception) {
            appendLog("Falha Fallback: ${e.message}")
            null
        }
    }

    private fun saveImage(mediaUrl: String) {
        try {
            appendLog("Baixando bytes da imagem...")
            val response = httpClient.newCall(Request.Builder().url(mediaUrl).build()).execute()
            val inputStream: InputStream? = response.body?.byteStream()

            if (inputStream != null) {
                val fileName = "INSTA_${System.currentTimeMillis()}.jpg"
                val resolver = contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/OverlayDownloads")
                }

                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        inputStream.copyTo(out)
                    }
                    appendLog("✓ SUCESSO! Foto salva em Pictures/OverlayDownloads.")
                } else {
                    appendLog("ERRO: Falha ao alocar URI na galeria.")
                }
            } else {
                appendLog("ERRO: Resposta da imagem vazia.")
            }
        } catch (e: Exception) {
            appendLog("ERRO ao salvar: ${e.message}")
        }
    }
}
