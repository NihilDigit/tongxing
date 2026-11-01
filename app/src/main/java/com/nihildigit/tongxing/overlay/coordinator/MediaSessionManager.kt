package com.nihildigit.tongxing.overlay.coordinator

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.nihildigit.tongxing.overlay.HeadsetButtonReceiver

/**
 * 管理 MediaSession、静音播放、音频焦点和媒体按键注册。
 * 从 OverlayService 提取，保持蓝牙耳机媒体键拦截能力。
 */
class MediaSessionManager(
    private val context: Context,
    private val onMediaButtonClick: () -> Unit
) {
    companion object {
        private const val TAG = "MediaSessionManager"
    }

    private var mediaSession: MediaSession? = null
    private var mediaButtonHandler: HeadsetButtonHandler? = null
    private var silentAudioTrack: AudioTrack? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var mediaButtonReceiverComponent: ComponentName? = null

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun setup() {
        mediaButtonHandler = HeadsetButtonHandler(
            onClick = { onMediaButtonClick() }
        )

        val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            setClass(context, HeadsetButtonReceiver::class.java)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, mediaButtonIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        registerMediaButtonReceiver()
        requestMediaButtonAudioFocus()
        startSilentPlayback()

        mediaSession = MediaSession(context, TAG).apply {
            setCallback(mediaButtonHandler!!, Handler(Looper.getMainLooper()))
            @Suppress("DEPRECATION")
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            @Suppress("DEPRECATION")
            setMediaButtonReceiver(pendingIntent)
            setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "TongXing Assistant (Media Hack)")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "TongXing")
                    .build()
            )
            setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                    .setActions(
                        PlaybackState.ACTION_PLAY_PAUSE or
                            PlaybackState.ACTION_PLAY or
                            PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_SKIP_TO_NEXT or
                            PlaybackState.ACTION_SKIP_TO_PREVIOUS
                    )
                    .build()
            )
            isActive = true
        }
        Log.i(TAG, "MediaSession setup complete")
    }

    fun release() {
        mediaSession?.release()
        mediaSession = null
        mediaButtonHandler = null
        stopSilentPlayback()
        abandonMediaButtonAudioFocus()
        unregisterMediaButtonReceiver()
    }

    private fun startSilentPlayback() {
        if (silentAudioTrack != null) return

        val sampleRate = 44100
        val frameCount = sampleRate
        val silence = ShortArray(frameCount)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(silence.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        val written = track.write(silence, 0, silence.size)
        if (written <= 0) {
            Log.e(TAG, "startSilentPlayback write failed written=$written")
            track.release()
            return
        }

        track.setLoopPoints(0, frameCount, -1)
        track.play()
        silentAudioTrack = track
        Log.w(TAG, "Silent playback started (frames=$frameCount)")
    }

    private fun stopSilentPlayback() {
        val track = silentAudioTrack ?: return
        runCatching {
            track.stop()
        }
        track.release()
        silentAudioTrack = null
        Log.w(TAG, "Silent playback stopped")
    }

    private fun requestMediaButtonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { /* keep session alive */ }
                .build()
            audioFocusRequest = request
            val result = audioManager.requestAudioFocus(request)
            Log.w(TAG, "requestAudioFocus result=$result")
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            Log.w(TAG, "requestAudioFocus(legacy) result=$result")
        }
    }

    private fun abandonMediaButtonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = audioFocusRequest ?: return
            val result = audioManager.abandonAudioFocusRequest(request)
            Log.w(TAG, "abandonAudioFocus result=$result")
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun registerMediaButtonReceiver() {
        val component = ComponentName(context, HeadsetButtonReceiver::class.java)
        mediaButtonReceiverComponent = component
        @Suppress("DEPRECATION")
        audioManager.registerMediaButtonEventReceiver(component)
        Log.w(TAG, "registerMediaButtonEventReceiver component=$component")
    }

    private fun unregisterMediaButtonReceiver() {
        val component = mediaButtonReceiverComponent ?: return
        @Suppress("DEPRECATION")
        audioManager.unregisterMediaButtonEventReceiver(component)
        Log.w(TAG, "unregisterMediaButtonEventReceiver component=$component")
        mediaButtonReceiverComponent = null
    }
}

/**
 * HeadsetButtonHandler:
 * - 单击：Toggle ASR（开始/停止）并清空 TTS 队列
 *
 * 使用 MediaSession 捕获全局媒体键。
 */
private class HeadsetButtonHandler(
    private val onClick: () -> Unit
) : MediaSession.Callback() {

    companion object {
        private const val TAG = "HeadsetButtonHandler"
    }

    override fun onPlay() {
        Log.w(TAG, "onPlay")
        onClick()
    }

    override fun onPause() {
        Log.w(TAG, "onPause")
        onClick()
    }

    override fun onSkipToNext() {
        Log.w(TAG, "onSkipToNext")
        onClick()
    }

    override fun onSkipToPrevious() {
        Log.w(TAG, "onSkipToPrevious")
        onClick()
    }

    override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
        Log.w(TAG, "onMediaButtonEvent received")
        val event = mediaButtonEvent.getParcelableExtra<android.view.KeyEvent>(Intent.EXTRA_KEY_EVENT)
            ?: return super.onMediaButtonEvent(mediaButtonEvent)

        Log.w(TAG, "keyCode=${event.keyCode} action=${event.action}")

        val supportedKeys = setOf(
            android.view.KeyEvent.KEYCODE_HEADSETHOOK,
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
            android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
            android.view.KeyEvent.KEYCODE_MEDIA_NEXT,
            android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
        )
        if (event.keyCode !in supportedKeys) {
            Log.d(TAG, "Unsupported keyCode: ${event.keyCode}")
            return super.onMediaButtonEvent(mediaButtonEvent)
        }

        // 只响应 ACTION_UP，避免重复触发
        if (event.action == android.view.KeyEvent.ACTION_UP) {
            Log.d(TAG, "Click triggered")
            onClick()
            return true
        }

        return true  // 消费 ACTION_DOWN，防止系统默认行为
    }
}
