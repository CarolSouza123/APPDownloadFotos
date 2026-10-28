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
            Toast.makeText(this, "Logs copiados!", Toast.LENGTH_SHORT).show()
        }

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
                appendLog("Tentativa 1: Extraindo foto em alta resolução via CDN...")
                val imageUrl = extractHighResImageUrl(postUrl)

                if (!imageUrl.isNullOrEmpty()) {
                    appendLog("Imagem original localizada com sucesso!")
                    saveImage(imageUrl)
                    return@launch
                }

                appendLog("ERRO: Não foi possível obter o arquivo original.")
            } catch (e: Exception) {
                appendLog("EXCEÇÃO: ${e.javaClass.simpleName} - ${e.message}")
            }
        }
    }

    private fun extractHighResImageUrl(postUrl: String): String? {
        val cleanUrl = postUrl.split("?").first().trimEnd('/') + "/"
        
        // Simula acesso por crawler oficial para o Instagram entregar metadados completos
        val request = Request.Builder()
            .url(cleanUrl)
            .addHeader("User-Agent", "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)")
            .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()

        val response = httpClient.newCall(request).execute()
        appendLog("Status da requisição: ${response.code}")
        if (!response.isSuccessful) return null

        val html = response.body?.string() ?: return null

        // 1. Procura URL da CDN do Instagram sem corte de proporção
        val cdnPattern = Pattern.compile("https://scontent[^\"'\\s<>]+?(?:\\.jpg|\\.jpeg)")
        val cdnMatcher = cdnPattern.matcher(html)
        var bestUrl: String? = null

        while (cdnMatcher.find()) {
            val candidate = cdnMatcher.group(0)
                ?.replace("\\u0026", "&")
                ?.replace("&amp;", "&")

            if (candidate != null && !candidate.contains("s150x150") && !candidate.contains("s320x320")) {
                bestUrl = candidate
                break
            }
        }

        if (bestUrl != null) return bestUrl

        // 2. Busca pela tag og:image padrão caso o CDN direto varie
        val ogPattern = Pattern.compile("<meta property=\"og:image\" content=\"([^\"]+)\"")
        val ogMatcher = ogPattern.matcher(html)
        if (ogMatcher.find()) {
            return ogMatcher.group(1)?.replace("&amp;", "&")?.replace("\\u0026", "&")
        }

        return null
    }

    private fun saveImage(mediaUrl: String) {
        try {
            appendLog("Baixando bytes da imagem original...")
            val request = Request.Builder()
                .url(mediaUrl)
                .addHeader("User-Agent", "Mozilla/5.0")
                .build()

            val response = httpClient.newCall(request).execute()
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
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(applicationContext, "✓ Foto salva na galeria!", Toast.LENGTH_SHORT).show()
                    }
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
