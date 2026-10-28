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
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etLink = findViewById(R.id.etLink)
        btnDownload = findViewById(R.id.btnDownload)
        btnCopyLogs = findViewById(R.id.btnCopyLogs)
        tvLogs = findViewById(R.id.tvLogs)

        appendLog("Aplicação iniciada com sucesso.")

        btnDownload.setOnClickListener {
            val link = etLink.text.toString().trim()
            if (link.isNotEmpty()) {
                val cleanUrl = extractInstagramUrl(link)
                if (cleanUrl != null) {
                    startProcess(cleanUrl)
                } else {
                    appendLog("ERRO: Endereço inválido.")
                }
            } else {
                appendLog("AVISO: Insira uma hiperligação antes de prosseguir.")
            }
        }

        btnCopyLogs.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Logs", tvLogs.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Registos copiados para a área de transferência!", Toast.LENGTH_SHORT).show()
        }

        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            appendLog("Hiperligação recebida por partilha.")
            etLink.setText(sharedText)
            val cleanUrl = extractInstagramUrl(sharedText)
            if (cleanUrl != null) {
                startProcess(cleanUrl)
            } else {
                appendLog("ERRO: O texto partilhado não é do Instagram.")
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
        appendLog("A iniciar recolha para: $postUrl")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                appendLog("A analisar metadados da publicação...")
                val imageUrl = extractHighResImageUrl(postUrl)

                if (!imageUrl.isNullOrEmpty()) {
                    appendLog("Endereço válido obtido.")
                    saveImage(imageUrl)
                    return@launch
                }

                appendLog("ERRO: Não foi possível obter a hiperligação da fotografia.")
            } catch (e: Exception) {
                appendLog("EXCEÇÃO: ${e.javaClass.simpleName} - ${e.message}")
            }
        }
    }

    private fun extractHighResImageUrl(postUrl: String): String? {
        val cleanUrl = postUrl.split("?").first().trimEnd('/') + "/"

        val request = Request.Builder()
            .url(cleanUrl)
            .addHeader("User-Agent", "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)")
            .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()

        val response = httpClient.newCall(request).execute()
        appendLog("Resposta HTTP da página: ${response.code}")
        if (!response.isSuccessful) return null

        val html = response.body?.string() ?: return null

        // 1. Procura a meta tag og:image completa com todos os atributos decodificados
        val ogPattern = Pattern.compile("<meta property=\"og:image\" content=\"([^\"]+)\"")
        val ogMatcher = ogPattern.matcher(html)
        if (ogMatcher.find()) {
            val raw = ogMatcher.group(1)
            if (!raw.isNullOrEmpty()) {
                return cleanCdnUrl(raw)
            }
        }

        // 2. Procura hiperligações diretas na CDN do Instagram sem cortes
        val cdnPattern = Pattern.compile("https://scontent[^\"]+?(?:\\.jpg|\\.jpeg)[^\"]*")
        val cdnMatcher = cdnPattern.matcher(html)
        while (cdnMatcher.find()) {
            val candidate = cleanCdnUrl(cdnMatcher.group(0) ?: "")
            if (!candidate.contains("s150x150") && !candidate.contains("s320x320")) {
                return candidate
            }
        }

        return null
    }

    private fun cleanCdnUrl(url: String): String {
        return url.replace("&amp;", "&")
            .replace("\\u0026", "&")
            .replace("\\/", "/")
    }

    private fun saveImage(mediaUrl: String) {
        try {
            appendLog("A transferir ficheiro binário...")
            val request = Request.Builder()
                .url(mediaUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()

            val response = httpClient.newCall(request).execute()
            appendLog("Resposta da transferência: ${response.code}")

            if (!response.isSuccessful) {
                appendLog("ERRO: O servidor da imagem rejeitou o pedido (${response.code}).")
                return
            }

            val contentType = response.header("Content-Type", "")
            appendLog("Tipo de conteúdo: $contentType")

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
                    appendLog("✓ Concluído com sucesso! Ficheiro íntegro guardado.")
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(applicationContext, "✓ Fotografia guardada na galeria!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    appendLog("ERRO: Falha ao criar entrada na galeria.")
                }
            } else {
                appendLog("ERRO: Fluxo de dados vazio.")
            }
        } catch (e: Exception) {
            appendLog("ERRO durante a gravação: ${e.message}")
        }
    }
}
