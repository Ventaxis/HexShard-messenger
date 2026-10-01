package com.example.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Robust, lifecycle-aware looping muted WebM player for profile background headers.
 * - Looping: enabled
 * - Muted: volume 0f
 * - Non-blocking: background downloads and async preparation
 * - Lifecycle-aware: pauses on ON_PAUSE, resumes on ON_RESUME, fully releases on dispose
 */
@Composable
fun WebmBackgroundPlayer(
    videoUrl: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var localVideoPath by remember(videoUrl) { mutableStateOf<String?>(null) }
    var isPlayerReady by remember { mutableStateOf(false) }

    // Download/cache tiny WebM file locally (<512 KB) to guarantee smooth looping and zero re-buffering
    LaunchedEffect(videoUrl) {
        val cachedPath = withContext(Dispatchers.IO) {
            try {
                if (videoUrl.startsWith("content://") || videoUrl.startsWith("file://")) {
                    return@withContext videoUrl
                }
                val safeHash = videoUrl.hashCode().toString().replace("-", "n")
                val targetFile = File(context.cacheDir, "bg_webm_${safeHash}.webm")
                if (targetFile.exists() && targetFile.length() > 0) {
                    return@withContext targetFile.absolutePath
                }

                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()

                val req = Request.Builder().url(videoUrl).get().build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    val body = resp.body
                    if (body != null) {
                        val tempFile = File.createTempFile("down_webm_", ".tmp", context.cacheDir)
                        FileOutputStream(tempFile).use { out ->
                            body.byteStream().use { input ->
                                input.copyTo(out)
                            }
                        }
                        if (tempFile.renameTo(targetFile) || targetFile.exists()) {
                            tempFile.delete()
                            return@withContext targetFile.absolutePath
                        }
                        return@withContext tempFile.absolutePath
                    }
                }
                resp.close()
                null
            } catch (e: Exception) {
                Timber.w(e, "Failed to cache WebM background locally")
                null
            }
        }
        localVideoPath = cachedPath ?: videoUrl
    }

    val sourcePath = localVideoPath

    Box(modifier = modifier.background(Color(0xFF0F1418))) {
        if (sourcePath != null) {
            AndroidView(
                factory = { ctx ->
                    val textureView = TextureView(ctx)
                    var mediaPlayer: MediaPlayer? = null
                    var savedSurface: Surface? = null

                    fun initPlayer(surface: Surface) {
                        try {
                            mediaPlayer?.release()
                            val player = MediaPlayer().apply {
                                setSurface(surface)
                                setAudioAttributes(
                                    AudioAttributes.Builder()
                                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                                        .setUsage(AudioAttributes.USAGE_MEDIA)
                                        .build()
                                    )
                                setVolume(0f, 0f) // Muted playback
                                isLooping = true // Continuous looping
                                setOnPreparedListener { mp ->
                                    mp.setVolume(0f, 0f)
                                    mp.start()
                                    isPlayerReady = true
                                }
                                setOnErrorListener { _, what, extra ->
                                    Timber.w("WebM MediaPlayer error: what=$what, extra=$extra")
                                    true
                                }
                                if (sourcePath.startsWith("content://") || sourcePath.startsWith("http")) {
                                    setDataSource(ctx, Uri.parse(sourcePath))
                                } else {
                                    setDataSource(sourcePath)
                                }
                                prepareAsync()
                            }
                            mediaPlayer = player
                        } catch (e: Exception) {
                            Timber.e(e, "Error initializing WebM MediaPlayer")
                        }
                    }

                    textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                            val surface = Surface(st)
                            savedSurface = surface
                            initPlayer(surface)
                        }

                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}

                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            try {
                                mediaPlayer?.stop()
                                mediaPlayer?.reset()
                                mediaPlayer?.release()
                                mediaPlayer = null
                            } catch (_: Exception) {}
                            savedSurface?.release()
                            savedSurface = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }

                    // Attach lifecycle observer to pause/resume playback
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_RESUME -> {
                                try {
                                    if (mediaPlayer?.isPlaying == false) {
                                        mediaPlayer?.start()
                                    }
                                } catch (_: Exception) {}
                            }
                            Lifecycle.Event.ON_PAUSE -> {
                                try {
                                    if (mediaPlayer?.isPlaying == true) {
                                        mediaPlayer?.pause()
                                    }
                                } catch (_: Exception) {}
                            }
                            Lifecycle.Event.ON_DESTROY -> {
                                try {
                                    mediaPlayer?.stop()
                                    mediaPlayer?.reset()
                                    mediaPlayer?.release()
                                    mediaPlayer = null
                                } catch (_: Exception) {}
                            }
                            else -> {}
                        }
                    }

                    lifecycleOwner.lifecycle.addObserver(observer)
                    textureView.tag = observer
                    textureView
                },
                update = { view ->
                    // View update if needed
                },
                onRelease = { view ->
                    val observer = view.tag as? LifecycleEventObserver
                    if (observer != null) {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
