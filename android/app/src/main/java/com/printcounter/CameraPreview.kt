
package com.printcounter

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import android.widget.FrameLayout

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.uimanager.events.RCTEventEmitter

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

class CameraPreview(context: Context) : FrameLayout(context) {

    override fun onSizeChanged(
    w: Int,
    h: Int,
    oldw: Int,
    oldh: Int
) {
    super.onSizeChanged(w, h, oldw, oldh)

    Log.d(TAG, "CameraPreview size: ${w}x${h}")
}

    companion object {
        private const val TAG = "PrintCounterCamera"

        // Initial detection settings. We will calibrate these using real frames.
        private const val DARK_PIXEL_THRESHOLD = 75
        private const val DETECT_RATIO = 0.06
        private const val RESET_RATIO = 0.025
        private const val GATE_WIDTH_RATIO = 0.04
        private const val SCAN_LENGTH_RATIO = 0.60
        
    }

    private val previewView = PreviewView(context).apply {
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        )
        scaleType = PreviewView.ScaleType.FILL_CENTER
        implementationMode = PreviewView.ImplementationMode.PERFORMANCE
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var analysisExecutor: ExecutorService? = null

    private var markIsActive = false
    private var detectedCount = 0
    private var lastLogTime = 0L
    private var lastFrameLogTime = 0L

    init {
        addView(previewView)

    previewView.addOnLayoutChangeListener {
            view, left, top, right, bottom,
            _, _, _, _ ->

        Log.d(
            TAG,
            "PreviewView size: ${right - left}x${bottom - top}, " +
            "visible=${view.isShown}"
        )
    }
    }

    private fun getLifecycleOwner(): LifecycleOwner? {
        findViewTreeLifecycleOwner()?.let { return it }

        var current: Context? = context

        while (current is ContextWrapper) {
            if (current is LifecycleOwner) {
                return current
            }
            current = current.baseContext
        }

        return current as? LifecycleOwner
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startCamera()
    }

    private fun startCamera() {
        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Camera permission not granted")
            return
        }

        val lifecycleOwner = getLifecycleOwner()

        if (lifecycleOwner == null) {
            Log.e(TAG, "LifecycleOwner not found")
            return
        }

        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({

            try {
                if (!isAttachedToWindow) return@addListener

                val provider = cameraProviderFuture.get()
                cameraProvider = provider

                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(
                        ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                    )
                    .build()

                if (analysisExecutor == null || analysisExecutor?.isShutdown == true) {
                    analysisExecutor = Executors.newSingleThreadExecutor()
                }

                analysis.setAnalyzer(analysisExecutor!!) { image ->
                    try {
                        analyzeFrame(image)
                    } catch (exception: Exception) {
                        Log.e(TAG, "Frame analysis failed", exception)
                    } finally {
                        image.close()
                    }
                }

                provider.unbindAll()

                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )

                previewUseCase = preview
                analysisUseCase = analysis

                Log.d(TAG, "Camera preview and image analysis started")

            } catch (exception: Exception) {
                Log.e(TAG, "Camera initialization failed", exception)
            }

        }, ContextCompat.getMainExecutor(context))
    }

    
        
private fun analyzeFrame(image: ImageProxy) {
    val plane = image.planes.firstOrNull() ?: return
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride

    val width = image.width
    val height = image.height

    val frameNow = SystemClock.elapsedRealtime()

if (frameNow - lastFrameLogTime >= 2000) {
    lastFrameLogTime = frameNow

    Log.d(
        TAG,
        "IMAGE FRAME RECEIVED: ${width}x${height}, " +
        "rotation=${image.imageInfo.rotationDegrees}"
    )
}

    if (width <= 0 || height <= 0) return

    val rotated = image.imageInfo.rotationDegrees == 90 ||
                  image.imageInfo.rotationDegrees == 270

    var darkCount = 0
    var totalSamples = 0

    if (!rotated) {
        val centerX = width / 2
        val halfGate = max(1, (width * GATE_WIDTH_RATIO / 2.0).toInt())

        val startY = (height * (1.0 - SCAN_LENGTH_RATIO) / 2.0).toInt()
        val endY = height - startY

        for (y in startY until endY step 2) {
            for (x in (centerX - halfGate)..(centerX + halfGate) step 2) {
                if (x !in 0 until width) continue

                val index = y * rowStride + x * pixelStride
                if (index >= buffer.limit()) continue

                totalSamples++

                val luminance = buffer.get(index).toInt() and 0xFF
                if (luminance < DARK_PIXEL_THRESHOLD) {
                    darkCount++
                }
            }
        }
    } else {
        val centerY = height / 2
        val halfGate = max(1, (height * GATE_WIDTH_RATIO / 2.0).toInt())

        val startX = (width * (1.0 - SCAN_LENGTH_RATIO) / 2.0).toInt()
        val endX = width - startX

        for (x in startX until endX step 2) {
            for (y in (centerY - halfGate)..(centerY + halfGate) step 2) {
                if (y !in 0 until height) continue

                val index = y * rowStride + x * pixelStride
                if (index >= buffer.limit()) continue

                totalSamples++

                val luminance = buffer.get(index).toInt() and 0xFF
                if (luminance < DARK_PIXEL_THRESHOLD) {
                    darkCount++
                }
            }
        }
    }

    if (totalSamples == 0) return

    val ratio = darkCount.toDouble() / totalSamples.toDouble()

    if (!markIsActive && ratio >= DETECT_RATIO) {
        markIsActive = true
        detectedCount++

        Log.i(
            TAG,
            "DARK MARK DETECTED! count=$detectedCount ratio=${"%.3f".format(ratio)}"
        )

        sendMarkDetected(detectedCount, ratio)

    } else if (markIsActive && ratio <= RESET_RATIO) {
        markIsActive = false
        Log.d(TAG, "Detection gate cleared")
    }

    val now = SystemClock.elapsedRealtime()
    if (now - lastLogTime >= 2000) {
        lastLogTime = now
        Log.d(TAG, "Dark pixel ratio=${"%.3f".format(ratio)}")
    }
}


    private fun sendMarkDetected(count: Int, ratio: Double) {
        val reactContext = context as? ReactContext ?: return

        post {
            try {
                val payload = Arguments.createMap().apply {
                    putInt("count", count)
                    putDouble("darkRatio", ratio)
                }

                reactContext
                    .getJSModule(RCTEventEmitter::class.java)
                    .receiveEvent(id, "topMarkDetected", payload)

            } catch (exception: Exception) {
                Log.e(TAG, "Could not send mark event to React Native", exception)
            }
        }
    }

    override fun onDetachedFromWindow() {
        try {
            analysisUseCase?.clearAnalyzer()

            val useCases = listOfNotNull(
                previewUseCase,
                analysisUseCase
            )

            if (useCases.isNotEmpty()) {
                cameraProvider?.unbind(*useCases.toTypedArray())
            }

            analysisExecutor?.shutdown()
        } catch (exception: Exception) {
            Log.e(TAG, "Camera cleanup failed", exception)
        }

        previewUseCase = null
        analysisUseCase = null
        cameraProvider = null
        analysisExecutor = null

        super.onDetachedFromWindow()
    }
}
