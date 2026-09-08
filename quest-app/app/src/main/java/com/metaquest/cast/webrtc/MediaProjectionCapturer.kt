package com.metaquest.cast.webrtc

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Log
import android.view.Surface
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference

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
    private val pendingScreenshotCallback = AtomicReference<((Bitmap?) -> Unit)?>(null)

    /**
     * Request the next uncompressed hardware compositor frame as an HD Bitmap
     */
    fun captureNextFrameHd(callback: (Bitmap?) -> Unit) {
        pendingScreenshotCallback.set(callback)
    }

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

            // Check if an uncompressed HD screenshot was requested
            val cb = pendingScreenshotCallback.getAndSet(null)
            if (cb != null) {
                try {
                    videoFrame.retain()
                    val i420 = videoFrame.buffer.toI420()
                    if (i420 != null) {
                        val bitmap = convertI420ToBitmap(i420, videoFrame.rotation)
                        i420.release()
                        videoFrame.release()
                        Log.i(tag, "Successfully captured native HD frame (${bitmap.width}x${bitmap.height})")
                        cb(bitmap)
                    } else {
                        videoFrame.release()
                        cb(null)
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Failed to capture HD screenshot from videoFrame: ${e.message}", e)
                    cb(null)
                }
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

    /**
     * Converts a raw WebRTC I420Buffer into an uncompressed high-definition Android Bitmap
     */
    private fun convertI420ToBitmap(i420: VideoFrame.I420Buffer, rotation: Int): Bitmap {
        val width = i420.width
        val height = i420.height
        val y = i420.dataY
        val u = i420.dataU
        val v = i420.dataV
        val strideY = i420.strideY
        val strideU = i420.strideU
        val strideV = i420.strideV

        val nv21 = ByteArray(width * height * 3 / 2)
        var pos = 0

        // 1. Copy Y plane
        for (row in 0 until height) {
            y.position(row * strideY)
            y.get(nv21, pos, width)
            pos += width
        }

        // 2. Interleave V and U (NV21 format: V followed by U)
        val chromaHeight = (height + 1) / 2
        val chromaWidth = (width + 1) / 2
        for (row in 0 until chromaHeight) {
            v.position(row * strideV)
            u.position(row * strideU)
            for (col in 0 until chromaWidth) {
                nv21[pos++] = v.get()
                nv21[pos++] = u.get()
            }
        }

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 98, out)
        val jpegBytes = out.toByteArray()

        var bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
        return bitmap
    }
}
