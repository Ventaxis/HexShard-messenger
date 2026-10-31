package com.example.ui

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import java.io.File
import java.util.UUID

class VoiceMessageHelper(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null

    fun startRecording() {
        val fileName = "voice_msg_${UUID.randomUUID()}.ogg"
        audioFile = File(context.cacheDir, fileName)

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.OGG)
            setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            setAudioSamplingRate(16000) // 16kHz
            setAudioEncodingBitRate(32000) // 32kbps
            setOutputFile(audioFile?.absolutePath)
            prepare()
            start()
        }
    }

    fun stopRecording(): File? {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            timber.log.Timber.e(e, "Error stopping MediaRecorder")
        }
        mediaRecorder = null
        return audioFile
    }

    fun cancelRecording() {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            // Ignore stop errors if cancelled early
        }
        mediaRecorder = null
        audioFile?.delete()
        audioFile = null
    }
}
