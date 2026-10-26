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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Se foi aberto via menu Compartilhar
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            val url = extractInstagramUrl(sharedText)

            if (url != null) {
                Toast.makeText(this, "Baixando imagem com qualidade original...", Toast.LENGTH_SHORT).show()
                downloadFromInstagramUrl(url)
            } else {
                Toast.makeText(this, "Nenhum link do Instagram reconhecido.", Toast.LENGTH_LONG).show()
                finish()
            }
        } else {
            Toast.makeText(this, "Abra o Instagram e use o botão Compartilhar para usar este app.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun extractInstagramUrl(text: String): String? {
        val pattern = Pattern.compile("https?://(www\\.)?instagram\\.com/(p|reel|tv)/[a-zA-Z0-9_-]+")
        val matcher = pattern.matcher(text)
        return if (matcher.find()) matcher.group(0) else null
    }

    private fun downloadFromInstagramUrl(postUrl: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Resolvedor público de alta resolução
                val apiUrl = "https://api.cobalt.tools/api/json"
                val jsonPayload = JSONObject().apply {
                    put("url", postUrl)
                }

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = jsonPayload.toString().toRequestBody(mediaType)

                val request = Request.Builder()
                    .url(apiUrl)
                    .addHeader("Accept", "application/json")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseData = response.body?.string()

                if (responseData != null) {
                    val json = JSONObject(responseData)
                    val directDownloadUrl = json.optString("url")

                    if (directDownloadUrl.isNotEmpty()) {
                        saveMediaToStorage(directDownloadUrl)
                    } else if (json.has("picker")) {
                        // Caso seja um carrossel (múltiplas fotos)
                        val pickerArray = json.getJSONArray("picker")
                        if (pickerArray.length() > 0) {
                            val firstItemUrl = pickerArray.getJSONObject(0).getString("url")
                            saveMediaToStorage(firstItemUrl)
                        }
                    } else {
                        showToast("Não foi possível obter a mídia original.")
                        finish()
                    }
                } else {
                    showToast("Erro de comunicação com o servidor.")
                    finish()
                }
            } catch (e: Exception) {
                showToast("Falha ao processar: ${e.localizedMessage}")
                finish()
            }
        }
    }

    private fun saveMediaToStorage(mediaUrl: String) {
        try {
            val request = Request.Builder().url(mediaUrl).build()
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
                    showToast("Imagem salva na Galeria!")
                }
            }
        } catch (e: Exception) {
            showToast("Erro ao gravar arquivo: ${e.localizedMessage}")
        } finally {
            finish()
        }
    }

    private fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
