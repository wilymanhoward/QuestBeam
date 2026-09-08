package com.metaquest.cast.webrtc

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Log
import android.view.Surface
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer

/**
 * Native Meta Horizon OS MediaProjection Video Capturer for WebRTC.
 * Captures the system compositor display and passes frames directly into WebRTC VideoSink.
 */
class MediaProjectionCapturer(
    private val mediaProjectionPermissionData: Intent,
    private val mediaProjectionCallback: MediaProjection.Callback
) : VideoCapturer {

    private val tag = "MediaProjectionCapturer"
    private var context: Context? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var capturerObserver: CapturerObserver? = null

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var width: Int = 1920
    private var height: Int = 1080
    private var fps: Int = 60
    private var isDisposed: Boolean = false
    private var frameCount: Long = 0

    private var surface: Surface? = null

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper?,
        appContext: Context?,
        observer: CapturerObserver?
    ) {
        this.surfaceHelper = surfaceTextureHelper
        this.context = appContext
        this.capturerObserver = observer
        this.mediaProjectionManager =
            context?.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        Log.d(tag, "MediaProjectionCapturer initialized with surfaceTextureHelper: $surfaceTextureHelper")
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        if (isDisposed) return
        this.width = width
        this.height = height
        this.fps = framerate

        Log.d(tag, "Starting screen capture: ${width}x${height} @ ${framerate}fps")
        capturerObserver?.onCapturerStarted(true)

        val helper = surfaceHelper
        if (helper == null) {
            Log.e(tag, "surfaceHelper is null! Cannot start capture.")
            return
        }

        helper.setTextureSize(width, height)
        surface = Surface(helper.surfaceTexture)

        // Register VideoSink listener on SurfaceTextureHelper to pump frames to capturerObserver
        helper.startListening { videoFrame ->
            frameCount++
            if (frameCount % 180L == 1L) {
                Log.d(tag, "Delivered frame #$frameCount (${videoFrame.rotatedWidth}x${videoFrame.rotatedHeight}) to WebRTC encoder")
            }
            capturerObserver?.onFrameCaptured(videoFrame)
        }

        // Instantiate MediaProjection from the granted intent
        mediaProjection = mediaProjectionManager?.getMediaProjection(
            android.app.Activity.RESULT_OK,
            mediaProjectionPermissionData
        )
        mediaProjection?.registerCallback(mediaProjectionCallback, helper.handler)

        // Meta Horizon OS screen mirroring flag: VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
        // (Do NOT use VIRTUAL_DISPLAY_FLAG_PUBLIC as it creates an empty presentation screen)
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "QuestCastMirror",
            width,
            height,
            320, // DPI density
            flags,
            surface,
            object : VirtualDisplay.Callback() {
                override fun onPaused() {
                    Log.w(tag, "VirtualDisplay onPaused")
                }
                override fun onResumed() {
                    Log.i(tag, "VirtualDisplay onResumed")
                }
                override fun onStopped() {
                    Log.w(tag, "VirtualDisplay onStopped")
                }
            },
            helper.handler
        )
        Log.d(tag, "VirtualDisplay created: $virtualDisplay with surface: $surface")
    }

    override fun stopCapture() {
        Log.d(tag, "Stopping screen capture...")
        surfaceHelper?.stopListening()

        virtualDisplay?.release()
        virtualDisplay = null

        surface?.release()
        surface = null

        mediaProjection?.stop()
        mediaProjection = null

        capturerObserver?.onCapturerStopped()
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        this.width = width
        this.height = height
        this.fps = framerate

        surfaceHelper?.setTextureSize(width, height)
        virtualDisplay?.resize(width, height, 320)
    }

    override fun dispose() {
        isDisposed = true
        stopCapture()
    }

    override fun isScreencast(): Boolean = true
}
