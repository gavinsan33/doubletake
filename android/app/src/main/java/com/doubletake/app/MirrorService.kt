package com.doubletake.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import com.doubletake.mobile.Host
import com.doubletake.mobile.Mobile
import java.io.File
import kotlin.concurrent.thread

private const val TAG = "doubletake"
private const val FPS = 30
private const val BITRATE_KBPS = 6000

/** Foreground service: owns MediaProjection + MediaCodec and runs the Go session. */
class MirrorService : Service(), Host {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var savedVolume = -1 // phone media volume to restore, -1 = untouched
    private var dm: DisplayManager? = null
    private var canvasW = 0
    private var canvasH = 0
    private var encW = 0
    private var encH = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Mobile.stop()
            return START_NOT_STICKY
        }
        val host = intent?.getStringExtra(EXTRA_HOST) ?: return START_NOT_STICKY
        val port = intent.getIntExtra(EXTRA_PORT, 7000)
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val grant = intent.getParcelableExtra<Intent>(EXTRA_GRANT) ?: return START_NOT_STICKY

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("mirror", "Mirroring", NotificationManager.IMPORTANCE_LOW))
        val stop = android.app.PendingIntent.getService(
            this, 0, Intent(this, MirrorService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val note = Notification.Builder(this, "mirror")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("Mirroring to $host")
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        startForeground(1, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        // Android 14+ requires the foreground service to be running before this call.
        projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, grant)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = Mobile.stop()
        }, null)

        Mobile.setDebug(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
        thread(name = "doubletake-session") {
            val err = try {
                Mobile.run(this, host, port.toLong(), FPS.toLong(), BITRATE_KBPS.toLong(), true,
                    File(filesDir, "creds").apply { mkdirs() }.path, "")
                null
            } catch (e: Exception) {
                e
            }
            Log.i(TAG, "session ended: $err")
            MainActivity.status(if (err == null) "Stopped" else "Error: ${err.message}")
            releaseCapture()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    // --- Host (called from the Go session thread) ---

    override fun log(msg: String) {
        Log.d(TAG, msg.trimEnd())
    }

    override fun requestCode(prompt: String): String = MainActivity.ask(prompt)

    /** Captures other apps' media audio (apps may opt out) as 44.1 kHz stereo PCM for the Go side. */
    override fun startAudio() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            error("microphone permission not granted (needed to capture app audio)")
        }
        val proj = projection ?: error("no projection")
        val capture = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(44100)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val min = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder()
            .setAudioFormat(format)
            .setAudioPlaybackCaptureConfig(capture)
            .setBufferSizeInBytes(maxOf(min, 44100 * 4 / 5)) // >= 200 ms
            .build()
        rec.startRecording()
        audioRecord = rec
        // Capture is taken before the volume stage, so silencing the phone leaves the TV unaffected.
        // Set the level rather than ADJUST_MUTE, which some OEM builds ignore.
        val am = getSystemService(AudioManager::class.java)
        savedVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        Log.i(TAG, "phone media volume $savedVolume -> ${am.getStreamVolume(AudioManager.STREAM_MUSIC)}")
        thread(name = "doubletake-audio") {
            val buf = ByteArray(441 * 4) // 10 ms
            try {
                while (audioRecord === rec) {
                    val n = rec.read(buf, 0, buf.size) and 3.inv() // whole stereo frames
                    if (n <= 0) break
                    Mobile.pushAudio(buf.copyOf(n))
                }
            } catch (e: Exception) {
                Log.i(TAG, "audio pump ended: $e")
            }
        }
    }

    override fun startEncoder(width: Long, height: Long) {
        val proj = projection ?: error("no projection")
        canvasW = width.toInt(); canvasH = height.toInt()
        val (w, h) = fit()
        val enc = newEncoder(w, h)
        display = proj.createVirtualDisplay("doubletake", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, enc.second, null, null)
        encW = w; encH = h
        // Rotation or folding changes the screen's aspect; follow it.
        dm = getSystemService(DisplayManager::class.java).also { it.registerDisplayListener(displayListener, null) }
    }

    /** Screen fitted inside the receiver's canvas, aspect preserved, even sizes for H.264. */
    private fun fit(): Pair<Int, Int> {
        val real = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val scale = minOf(canvasW.toDouble() / real.width(), canvasH.toDouble() / real.height())
        return Pair((real.width() * scale).toInt() and 1.inv(), (real.height() * scale).toInt() and 1.inv())
    }

    private fun newEncoder(w: Int, h: Int): Pair<MediaCodec, android.view.Surface> {
        Log.i(TAG, "encoder ${w}x$h for canvas ${canvasW}x$canvasH")
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE_KBPS * 1000)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // A static screen produces no frames; keep the receiver fed.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = enc.createInputSurface()
        enc.start()
        codec = enc
        thread(name = "doubletake-encoder") { drain(enc) }
        return enc to surface
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) {
            if (id == android.view.Display.DEFAULT_DISPLAY) resizeEncoder()
        }
    }

    // Swap in a new encoder at the new size. The Go sender re-sends the codec
    // config when the SPS changes, so the receiver picks up the new dimensions.
    @Synchronized
    private fun resizeEncoder() {
        val vd = display ?: return
        val (w, h) = fit()
        if (w == encW && h == encH) return
        val old = codec
        val (_, surface) = newEncoder(w, h) // sets `codec`, retiring the old pump
        vd.resize(w, h, resources.displayMetrics.densityDpi)
        vd.surface = surface
        encW = w; encH = h
        runCatching { old?.stop() }; runCatching { old?.release() }
    }

    private fun drain(enc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var config = ByteArray(0)
        try {
            while (codec === enc) {
                val i = enc.dequeueOutputBuffer(info, 100_000)
                if (i < 0) continue
                val out = enc.getOutputBuffer(i)!!
                val data = ByteArray(info.size).also { out.get(it) }
                val flags = info.flags
                enc.releaseOutputBuffer(i, false)
                if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    config = data
                    continue
                }
                val key = flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                // Go expects SPS/PPS ahead of every IDR; header-prepend is best-effort on some devices.
                val frame = if (key && !startsWithSps(data)) config + data else data
                if (codec !== enc) break // replaced by a resize; drop stale-size frames
                Mobile.pushFrame(frame)
                if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        } catch (e: Exception) {
            if (codec !== enc) return // retired by resizeEncoder
            // Codec released on stop, or the Go side is gone / backlogged.
            Log.i(TAG, "encoder pump ended: $e")
            Mobile.stop()
        }
    }

    private fun startsWithSps(d: ByteArray): Boolean {
        val o = if (d.size > 4 && d[2] == 0.toByte() && d[3] == 1.toByte()) 4 else 3
        return d.size > o && (d[o].toInt() and 0x1f) == 7
    }

    private fun releaseCapture() {
        audioRecord?.let { audioRecord = null; runCatching { it.stop() }; it.release() }
        if (savedVolume >= 0) {
            getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0)
            savedVolume = -1
        }
        dm?.unregisterDisplayListener(displayListener); dm = null
        display?.release(); display = null
        runCatching { codec?.stop() }; runCatching { codec?.release() }; codec = null
        projection?.stop(); projection = null
    }

    override fun onDestroy() {
        Mobile.stop()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.doubletake.app.STOP"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_GRANT = "grant"
    }
}
