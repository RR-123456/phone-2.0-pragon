package com.pragon.mobile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streams the phone's mic as raw PCM16 mono over RemoteBridge, the same
 * format PhoneView's web mic sends over /ws/phone-audio - here it rides the
 * same /ws/mobile socket as everything else, as binary frames.
 */
object MicStreamer {
    private const val SAMPLE_RATE = 16000
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun isRunning() = running.get()

    fun start(ctx: Context) {
        if (running.get() || !hasPermission(ctx)) return
        running.set(true)
        thread = Thread {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(2048)
            val rec = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
                )
            } catch (e: SecurityException) { running.set(false); return@Thread }
            if (rec.state != AudioRecord.STATE_INITIALIZED) { running.set(false); return@Thread }
            val buf = ByteArray(minBuf)
            try {
                rec.startRecording()
                while (running.get()) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) RemoteBridge.sendMic(if (n == buf.size) buf else buf.copyOf(n))
                }
            } finally {
                try { rec.stop() } catch (e: Exception) { }
                rec.release()
            }
        }.also { it.start() }
    }

    fun stop() {
        running.set(false)
        thread = null
    }
}
