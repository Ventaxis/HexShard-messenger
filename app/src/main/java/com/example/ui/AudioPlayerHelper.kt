package com.example.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

import android.media.MediaDataSource

class ByteArrayMediaDataSource(private val data: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= data.size) return -1
        val len = Math.min(size.toLong(), data.size - position).toInt()
        System.arraycopy(data, position.toInt(), buffer, offset, len)
        return len
    }
    override fun getSize(): Long = data.size.toLong()
    override fun close() {}
}

class AudioPlayerHelper(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null
    
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying
    
    private val _currentPosition = MutableStateFlow(0)
    val currentPosition: StateFlow<Int> = _currentPosition

    fun playAudio(bytes: ByteArray, onComplete: () -> Unit) {
        if (mediaPlayer == null) {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                try {
                    setDataSource(ByteArrayMediaDataSource(bytes))
                    prepareAsync()
                    setOnPreparedListener {
                        start()
                        _isPlaying.value = true
                    }
                    setOnCompletionListener {
                        _isPlaying.value = false
                        _currentPosition.value = 0
                        onComplete()
                    }
                } catch (e: Exception) {
                    timber.log.Timber.e(e, "Error setting data source")
                }
            }
        } else {
            mediaPlayer?.start()
            _isPlaying.value = true
        }
    }

    fun pauseAudio() {
        mediaPlayer?.pause()
        _isPlaying.value = false
    }

    fun stopAudio() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        _isPlaying.value = false
        _currentPosition.value = 0
    }

    suspend fun updateProgress() {
        while (true) {
            if (_isPlaying.value) {
                _currentPosition.value = mediaPlayer?.currentPosition ?: 0
            }
            delay(100)
        }
    }
}
