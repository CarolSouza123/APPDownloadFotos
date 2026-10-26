package com.exemplo.overlaydownloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class OverlayService : Service() {

    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "overlay_channel"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var layoutParams: WindowManager.LayoutParams

    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f
    private var isMoving: Boolean = false

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val instagramLinkRegex = Regex(
        """https?://(?:www\.)?instagram\.com/(?:p|reel|reels)/[A-Za-z0-9_\-]+"""
    )

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        setupOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::overlayView.isInitialized) {
            try {
                windowManager.removeView(overlayView)
            } catch (ignored: IllegalArgumentException) {
                // A view já foi removida, não há nada para desfazer aqui.
            }
        }
        serviceScope.cancel()
        isRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = getString(R.string.notification_channel_description)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    private fun setupOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_button_layout, null)

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        val button = overlayView.findViewById<ImageButton>(R.id.overlayButton)
        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isMoving = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - initialTouchX).toInt()
                    val deltaY = (event.rawY - initialTouchY).toInt()
                    if (abs(deltaX) > 10 || abs(deltaY) > 10) {
                        isMoving = true
                    }
                    layoutParams.x = initialX + deltaX
                    layoutParams.y = initialY + deltaY
                    windowManager.updateViewLayout(overlayView, layoutParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isMoving) {
                        handleDownloadClick()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(overlayView, layoutParams)
    }

    private fun handleDownloadClick() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip == null || clip.itemCount == 0) {
            showToast(R.string.error_no_link)
            return
        }
        val clipboardText = clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
        val cleanUrl = instagramLinkRegex.find(clipboardText)?.value
        if (cleanUrl.isNullOrEmpty()) {
            showToast(R.string.error_invalid_link)
            return
        }

        showToast(R.string.downloading)
        serviceScope.launch {
            try {
                val imageUrl = fetchOriginalImageUrl(cleanUrl)
                    ?: throw IOException("Original image URL not found")
                downloadAndSave(imageUrl)
                showToast(R.string.success_downloaded)
            } catch (error: Exception) {
                showToast(R.string.error_download_failed)
            }
        }
    }

    private fun fetchOriginalImageUrl(postUrl: String): String? {
        val jsonUrl = "$postUrl?__a=1&__d=dis"
        val jsonRequest = Request.Builder()
            .url(jsonUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()

        httpClient.newCall(jsonRequest).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (body.startsWith("{")) {
                val fromJson = extractFromJson(body)
                if (!fromJson.isNullOrEmpty()) {
                    return fromJson
                }
            }
        }

        val htmlRequest = Request.Builder()
            .url(postUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html")
            .build()

        httpClient.newCall(htmlRequest).execute().use { response ->
            val html = response.body?.string().orEmpty()
            val displayUrl = Regex("""\"display_url\":\"([^\"]+)\"""")
                .find(html)?.groupValues?.get(1)
            if (!displayUrl.isNullOrEmpty()) {
                return displayUrl.replace("\\/", "/")
            }
            val openGraph = Regex("""property="og:image" content="([^"]+)"""")
                .find(html)?.groupValues?.get(1)
            if (!openGraph.isNullOrEmpty()) {
                return openGraph.replace("&amp;", "&")
            }
        }

        return null
    }

    private fun extractFromJson(body: String): String? {
        return try {
            val root = JSONObject(body)

            if (root.has("graphql")) {
                val media = root
                    .getJSONObject("graphql")
                    .getJSONObject("shortcode_media")
                if (media.has("display_url")) {
                    return media.getString("display_url")
                }
            }

            if (root.has("items")) {
                val items = root.getJSONArray("items")
                if (items.length() > 0) {
                    val firstItem = items.getJSONObject(0)
                    val fromItem = extractFromImageVersions(firstItem)
                    if (!fromItem.isNullOrEmpty()) {
                        return fromItem
                    }
                }
            }

            extractFromImageVersions(root)
        } catch (error: Exception) {
            null
        }
    }

    private fun extractFromImageVersions(node: JSONObject): String? {
        if (!node.has("image_versions2")) {
            return null
        }
        val candidates = node
            .getJSONObject("image_versions2")
            .getJSONArray("candidates")
        var bestUrl: String? = null
        var bestWidth = -1
        for (index in 0 until candidates.length()) {
            val candidate = candidates.getJSONObject(index)
            val width = if (candidate.has("width")) candidate.getInt("width") else 0
            val url = if (candidate.has("url")) candidate.getString("url") else ""
            if (url.isNotEmpty() && width >= bestWidth) {
                bestWidth = width
                bestUrl = url
            }
        }
        return bestUrl
    }

    private fun downloadAndSave(imageUrl: String) {
        val request = Request.Builder()
            .url(imageUrl)
            .header("User-Agent", USER_AGENT)
            .build()

        val bytes: ByteArray = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Image request failed with code ${response.code}")
            }
            response.body?.bytes() ?: throw IOException("Empty image body")
        }

        val isPng = imageUrl.substringBefore("?").endsWith(".png", ignoreCase = true)
        val extension = if (isPng) "png" else "jpg"
        val mimeType = if (isPng) "image/png" else "image/jpeg"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "overlay_${System.currentTimeMillis()}.$extension")
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/OverlayDownloads"
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert failed")

        resolver.openOutputStream(uri)?.use { output ->
            output.write(bytes)
        } ?: throw IOException("MediaStore output stream is null")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val finished = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(uri, finished, null, null)
        }
    }

    private fun showToast(messageResId: Int) {
        mainHandler.post {
            Toast.makeText(this, messageResId, Toast.LENGTH_SHORT).show()
        }
    }
}
