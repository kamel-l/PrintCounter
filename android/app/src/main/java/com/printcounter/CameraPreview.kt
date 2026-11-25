package com.printcounter

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.FrameLayout

import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.uimanager.annotations.ReactProp
import com.facebook.react.uimanager.events.RCTEventEmitter

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

class CameraPreview(context: Context) : FrameLayout(context) {

    companion object {
        private const val TAG = "PrintCounterCamera"
        private const val CAMERA_PERMISSION_REQUEST_CODE = 0xCA11
        private const val LINE_SAMPLE_STEP = 4
    }

    // ---------- Seuils configurables (props) ----------
    private var lineDarkThreshold = 75
    private var lineCoverageThreshold = 0.35
    private var movementGateRatio = 0.20
    private var requiredDetectFrames = 2
    private var requiredClearFrames = 5
    private var detectLightLines = false

    // ---------- Pause utilisateur / arrêt technique ----------
    @Volatile private var analyzing = true
    @Volatile private var isAnalyzing = true

    private val previewView = PreviewView(context).apply {
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        )
        scaleType = PreviewView.ScaleType.FILL_CENTER
        // PERFORMANCE = SurfaceView, plus fiable avec la New Architecture
        implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        setBackgroundColor(Color.TRANSPARENT)
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var analysisExecutor: ExecutorService? = null
    private var cameraStarted = false
    private var cameraStartInProgress = false
    private var startRetryPosted = false

    private var markIsActive = false
    private var detectedCount = 0
    private var candidateFrames = 0
    private var clearFrames = 0

    private var lastLogTime = 0L
    private var lastFrameLogTime = 0L

    private val retryRunnable = Runnable {
        startRetryPosted = false
        if (isAttachedToWindow) startCamera()
    }

    private val onPreviewLayoutChange = View.OnLayoutChangeListener {
            view, left, top, right, bottom, _, _, _, _ ->

        Log.d(
            TAG,
            "PreviewView size: ${right - left}x${bottom - top}, visible=${view.isShown}"
        )
        if (isAttachedToWindow && !cameraStarted) startCamera()
    }

    init {
        addView(previewView)
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        previewView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        previewView.addOnLayoutChangeListener(onPreviewLayoutChange)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        Log.d(TAG, "CameraPreview size: ${w}x${h}")
    }

    // ---------- Props ----------

    @ReactProp(name = "lineDarkThreshold", defaultInt = 75)
    fun setLineDarkThreshold(value: Int) {
        lineDarkThreshold = value.coerceIn(0, 255)
    }

    @ReactProp(name = "lineCoverageThreshold", defaultFloat = 0.35f)
    fun setLineCoverageThreshold(value: Float) {
        lineCoverageThreshold = value.coerceIn(0f, 1f).toDouble()
    }

    @ReactProp(name = "movementGateRatio", defaultFloat = 0.20f)
    fun setMovementGateRatio(value: Float) {
        movementGateRatio = value.coerceIn(0.05f, 0.9f).toDouble()
    }

    @ReactProp(name = "requiredDetectFrames", defaultInt = 2)
    fun setRequiredDetectFrames(value: Int) {
        requiredDetectFrames = value.coerceAtLeast(1)
    }

    @ReactProp(name = "requiredClearFrames", defaultInt = 5)
    fun setRequiredClearFrames(value: Int) {
        requiredClearFrames = value.coerceAtLeast(1)
    }

    @ReactProp(name = "detectLightLines", defaultBoolean = false)
    fun setDetectLightLines(value: Boolean) {
        detectLightLines = value
    }

    @ReactProp(name = "analyzing", defaultBoolean = true)
    fun setAnalyzing(value: Boolean) {
        analyzing = value
        if (!value) {
            candidateFrames = 0
            clearFrames = 0
            markIsActive = false
        }
    }

    @ReactProp(name = "retryTrigger", defaultInt = 0)
    fun setRetryTrigger(value: Int) {
        if (value <= 0) return
        cameraStarted = false
        cameraStartInProgress = false
        startRetryPosted = false
        previewView.removeCallbacks(retryRunnable)
        startCamera()
    }

    @ReactProp(name = "resetTrigger", defaultInt = 0)
    fun setResetTrigger(value: Int) {
        if (value <= 0) return
        detectedCount = 0
        candidateFrames = 0
        clearFrames = 0
        markIsActive = false
        Log.d(TAG, "Counter reset via resetTrigger")
    }

    // ---------- Cycle de vie ----------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        cameraStarted = false
        cameraStartInProgress = false
        startCamera()
    }

    // Activity en priorité : elle est toujours correctement RESUMED sous RN
    private fun getLifecycleOwner(): LifecycleOwner? {
        (context as? ReactContext)?.currentActivity?.let {
            if (it is LifecycleOwner) {
                Log.d(
                    TAG,
                    "LifecycleOwner via currentActivity: " +
                        "${it.javaClass.simpleName}, state=${it.lifecycle.currentState}"
                )
                return it
            }
        }

        findViewTreeLifecycleOwner()?.let {
            Log.d(
                TAG,
                "LifecycleOwner via viewTree: " +
                    "${it.javaClass.simpleName}, state=${it.lifecycle.currentState}"
            )
            return it
        }

        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is LifecycleOwner) {
                Log.d(
                    TAG,
                    "LifecycleOwner via ContextWrapper: " +
                        "${current.javaClass.simpleName}, state=${current.lifecycle.currentState}"
                )
                return current
            }
            current = current.baseContext
        }
        return current as? LifecycleOwner
    }

    // ---------- Permission ----------

    private fun ensureCameraPermission(): Boolean {
        if (
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) return true

        Log.w(TAG, "Camera permission NOT granted")
        sendCameraError("permissionDenied", "CAMERA permission not granted")

        val activity = (context as? ReactContext)?.currentActivity
            ?: (context as? Activity)

        if (activity != null && !activity.isFinishing) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST_CODE
            )
        }
        return false
    }

    // ---------- Démarrage caméra ----------

    private fun startCamera() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        Log.d(
            TAG,
            "startCamera: granted=$granted, started=$cameraStarted, " +
                "inProgress=$cameraStartInProgress, attached=$isAttachedToWindow"
        )

        if (cameraStarted || cameraStartInProgress) return
        if (!isAttachedToWindow) return
        if (!ensureCameraPermission()) return

        val lifecycleOwner = getLifecycleOwner()
        if (lifecycleOwner == null) {
            Log.e(TAG, "LifecycleOwner NOT found")
            scheduleCameraStartRetry()
            return
        }

        cameraStartInProgress = true

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                if (!isAttachedToWindow) {
                    cameraStartInProgress = false
                    return@addListener
                }

                val provider = future.get()
                cameraProvider = provider

                val preview = Preview.Builder()
                    .build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(Size(640, 480))
                    .build()

                if (analysisExecutor == null || analysisExecutor?.isShutdown == true) {
                    analysisExecutor = Executors.newSingleThreadExecutor()
                }

                isAnalyzing = true
                analysis.setAnalyzer(analysisExecutor!!) { image ->
                    Log.d(TAG, "Analyzer callback fired: ${image.width}x${image.height}")
                    try {
                        if (isAnalyzing && analyzing) analyzeFrame(image)
                    } catch (exception: Exception) {
                        Log.e(TAG, "Frame analysis failed", exception)
                    } finally {
                        image.close()
                    }
                }

                provider.unbindAll()

                val camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )

                // Observer l'état réel de la caméra
                camera.cameraInfo.cameraState.observe(lifecycleOwner) { state ->
                    Log.d(
                        TAG,
                        "CameraState: type=${state.type}, " +
                            "error=${state.error?.code} ${state.error?.cause?.message}"
                    )
                }

                previewUseCase = preview
                analysisUseCase = analysis
                cameraStarted = true
                cameraStartInProgress = false

                previewView.removeOnLayoutChangeListener(onPreviewLayoutChange)

                Log.d(TAG, "Camera preview and image analysis started")
            } catch (exception: Exception) {
                Log.e(TAG, "Camera initialization failed", exception)
                cameraStartInProgress = false
                sendCameraError("initFailed", exception.message ?: "Camera init failed")
                scheduleCameraStartRetry()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun scheduleCameraStartRetry() {
        if (!isAttachedToWindow || cameraStarted || startRetryPosted) return
        startRetryPosted = true
        previewView.postDelayed(retryRunnable, 500)
    }

    // ---------- Analyse ----------

    private fun isPixelMatch(luminance: Int): Boolean =
        if (detectLightLines) luminance > lineDarkThreshold
        else                 luminance < lineDarkThreshold

    private fun analyzeFrame(image: ImageProxy) {
        val plane = image.planes.firstOrNull() ?: return
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return

        val frameNow = SystemClock.elapsedRealtime()

        if (frameNow - lastFrameLogTime >= 2000) {
            lastFrameLogTime = frameNow
            Log.d(
                TAG,
                "FRAME ${width}x${height}, rotation=${image.imageInfo.rotationDegrees}"
            )
        }

        val rotated = image.imageInfo.rotationDegrees == 90 ||
            image.imageInfo.rotationDegrees == 270

        val movementLength = if (rotated) width else height
        val lineLength = if (rotated) height else width

        val gateHalfWidth = max(2, (movementLength * movementGateRatio / 2.0).toInt())
        val movementCenter = movementLength / 2
        val movementStart = max(0, movementCenter - gateHalfWidth)
        val movementEnd = minOf(movementLength - 1, movementCenter + gateHalfWidth)

        val lineStart = (lineLength * 0.20).toInt()
        val lineEnd = (lineLength * 0.80).toInt()

        var lineDetectedInFrame = false
        var strongestCoverage = 0.0

        for (position in movementStart..movementEnd) {
            var matched = 0
            var total = 0

            for (linePosition in lineStart until lineEnd step LINE_SAMPLE_STEP) {
                val x: Int
                val y: Int
                if (rotated) {
                    x = position
                    y = linePosition
                } else {
                    x = linePosition
                    y = position
                }

                val index = y * rowStride + x * pixelStride
                if (index < 0 || index >= buffer.limit()) continue

                val luminance = buffer.get(index).toInt() and 0xFF
                total++
                if (isPixelMatch(luminance)) matched++
            }

            if (total == 0) continue
            val coverage = matched.toDouble() / total.toDouble()
            if (coverage > strongestCoverage) strongestCoverage = coverage
            if (coverage >= lineCoverageThreshold) lineDetectedInFrame = true
        }

        if (lineDetectedInFrame) {
            candidateFrames++
            clearFrames = 0
        } else {
            clearFrames++
            candidateFrames = 0
        }

        if (!markIsActive && candidateFrames >= requiredDetectFrames) {
            markIsActive = true
            detectedCount++
            Log.i(
                TAG,
                "LINE DETECTED count=$detectedCount " +
                    "coverage=${"%.3f".format(strongestCoverage)}"
            )
            sendMarkDetected(detectedCount, strongestCoverage)
        }

        if (markIsActive && clearFrames >= requiredClearFrames) {
            markIsActive = false
            Log.d(TAG, "Line cleared - ready for next line")
        }

        if (frameNow - lastLogTime >= 1000) {
            lastLogTime = frameNow
            Log.d(
                TAG,
                "Line detection: detected=$lineDetectedInFrame, " +
                    "coverage=${"%.3f".format(strongestCoverage)}, " +
                    "active=$markIsActive, " +
                    "candidateFrames=$candidateFrames, " +
                    "clearFrames=$clearFrames"
            )
        }
    }

    // ---------- Événements vers JS ----------

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
                Log.e(TAG, "Could not send mark event to JS", exception)
            }
        }
    }

    private fun sendCameraError(reason: String, message: String) {
        val reactContext = context as? ReactContext ?: return
        post {
            try {
                val payload = Arguments.createMap().apply {
                    putString("reason", reason)
                    putString("message", message)
                }
                reactContext
                    .getJSModule(RCTEventEmitter::class.java)
                    .receiveEvent(id, "topCameraError", payload)
            } catch (exception: Exception) {
                Log.e(TAG, "Could not send camera error to JS", exception)
            }
        }
    }

    // ---------- Détachement ----------

    override fun onDetachedFromWindow() {
        previewView.removeCallbacks(retryRunnable)
        previewView.removeOnLayoutChangeListener(onPreviewLayoutChange)

        try {
            isAnalyzing = false
            analysisUseCase?.clearAnalyzer()

            val useCases = listOfNotNull(previewUseCase, analysisUseCase)
            if (useCases.isNotEmpty()) {
                cameraProvider?.unbind(*useCases.toTypedArray())
            }

            analysisExecutor?.shutdownNow()
        } catch (exception: Exception) {
            Log.e(TAG, "Camera cleanup failed", exception)
        }

        previewUseCase = null
        analysisUseCase = null
        cameraProvider = null
        analysisExecutor = null
        cameraStarted = false
        cameraStartInProgress = false
        startRetryPosted = false

        super.onDetachedFromWindow()
    }
}