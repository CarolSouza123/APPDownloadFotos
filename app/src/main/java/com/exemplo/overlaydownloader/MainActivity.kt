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
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                val url = extractInstagramUrl(sharedText)

                if (url != null) {
                    showToast("Baixando imagem com qualidade original...")
                    processDownload(url)
                } else {
                    showToast("Nenhum link do Instagram válido encontrado.")
                    finish()
                }
            } else {
                showToast("Para usar, abra o Instagram e toque em Compartilhar.")
                finish()
            }
        } catch (e: Exception) {
            showToast("Erro ao iniciar: ${e.message}")
            finish()
        }
    }

    private fun extractInstagramUrl(text: String): String? {
        val pattern = Pattern.compile("https?://(www\\.)?instagram\\.com/(p|reel|tv)/[a-zA-Z0-9_-]+")
        val matcher = pattern.matcher(text)
        return if (matcher.find()) matcher.group(0) else null
    }

    private fun processDownload(postUrl: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Endpoint atualizado v10 da Cobalt
                val apiUrl = "https://api.cobalt.tools/"
                val jsonPayload = JSONObject().apply {
                    put("url", postUrl)
                    put("downloadMode", "auto")
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

                if (response.isSuccessful && !responseData.isNullOrEmpty()) {
                    val json = JSONObject(responseData)
                    
                    var downloadUrl = json.optString("url", "")
                    
                    // Se for carrossel com múltiplas imagens
                    if (downloadUrl.isEmpty() && json.has("picker")) {
                        val picker = json.optJSONArray("picker")
                        if (picker != null && picker.length() > 0) {
                            downloadUrl = picker.getJSONObject(0).optString("url", "")
                        }
                    }

                    if (downloadUrl.isNotEmpty()) {
                        saveMediaToStorage(downloadUrl)
                    } else {
                        showToast("Servidor não retornou a URL da imagem.")
                        finish()
                    }
                } else {
                    showToast("Serviço temporariamente indisponível (${response.code}).")
                    finish()
                }
            } catch (e: Exception) {
                showToast("Falha na conexão: ${e.localizedMessage}")
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
                    showToast("Foto salva na Galeria com sucesso!")
                } else {
                    showToast("Erro ao criar arquivo na galeria.")
                }
            } else {
                showToast("Não foi possível transferir o arquivo.")
            }
        } catch (e: Exception) {
            showToast("Erro ao salvar arquivo: ${e.localizedMessage}")
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
