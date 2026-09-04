package com.example.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.model.CameraFacing
import com.example.model.CyberConfig
import com.example.model.CyberFilter
import com.example.model.StreamResolution
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class CyberCameraManager(
    private val context: Context,
    private val onNewFrameAvailable: (ByteArray) -> Unit
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    @Volatile
    private var currentConfig = CyberConfig()
    private val isProcessingFrame = AtomicBoolean(false)
    private val frameCount = AtomicLong(0)
    private var lastFpsUpdateTime = System.currentTimeMillis()
    private var framesSinceLastUpdate = 0

    private var cachedPauseBitmap: Bitmap? = null
    private var cachedPauseWidth = 0
    private var cachedPauseHeight = 0

    private val _measuredFps = MutableStateFlow(0f)
    val measuredFps: StateFlow<Float> = _measuredFps

    private val _latestSnapshot = MutableStateFlow<Bitmap?>(null)
    val latestSnapshot: StateFlow<Bitmap?> = _latestSnapshot

    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView? = null,
        config: CyberConfig
    ) {
        currentConfig = config
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases(lifecycleOwner, previewView)
            } catch (e: Exception) {
                Log.e("CyberCameraManager", "Use case binding failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView? = null
    ) {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val cameraSelector = if (currentConfig.cameraFacing == CameraFacing.FRONT) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        val targetSize = Size(currentConfig.resolution.width, currentConfig.resolution.height)

        preview = Preview.Builder()
            .setTargetResolution(targetSize)
            .build()

        previewView?.let {
            preview?.setSurfaceProvider(it.surfaceProvider)
        }

        imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(targetSize)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        val frameIntervalMs = 1000L / currentConfig.targetFps.coerceIn(15, 60)
        var lastFrameTime = 0L

        imageAnalysis?.setAnalyzer(analysisExecutor) { imageProxy ->
            val now = System.currentTimeMillis()
            if (now - lastFrameTime < frameIntervalMs || isProcessingFrame.get()) {
                imageProxy.close()
                return@setAnalyzer
            }

            lastFrameTime = now
            isProcessingFrame.set(true)

            try {
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                val bitmap = imageProxy.toBitmap()

                // Matrix for rotation & front mirror
                val matrix = Matrix().apply {
                    if (rotationDegrees != 0) {
                        postRotate(rotationDegrees.toFloat())
                    }
                    if (currentConfig.cameraFacing == CameraFacing.FRONT && currentConfig.isMirrored) {
                        postScale(-1f, 1f)
                    }
                }

                val rotatedBitmap = if (!matrix.isIdentity) {
                    val rotated = Bitmap.createBitmap(
                        bitmap,
                        0,
                        0,
                        bitmap.width,
                        bitmap.height,
                        matrix,
                        false
                    )
                    bitmap.recycle()
                    rotated
                } else {
                    bitmap
                }

                // Check if video stream is paused (Privacy Hold mode for WhatsApp/calls)
                val isPaused = currentConfig.isVideoPaused
                val finalBitmap = if (isPaused) {
                    generatePrivacyPauseBitmap(rotatedBitmap.width, rotatedBitmap.height)
                } else {
                    CyberFilterRenderer.applyFilter(
                        rotatedBitmap,
                        currentConfig.activeFilter
                    )
                }

                // Compress to JPEG with fast buffer reuse
                val bos = ByteArrayOutputStream(65536)
                finalBitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    currentConfig.jpegQuality.coerceIn(40, 85),
                    bos
                )
                val jpegBytes = bos.toByteArray()

                // Clean up memory deterministically
                if (isPaused) {
                    // rotatedBitmap not used in paused state, release it
                    rotatedBitmap.recycle()
                } else {
                    if (finalBitmap != rotatedBitmap) {
                        rotatedBitmap.recycle()
                        finalBitmap.recycle()
                    } else {
                        rotatedBitmap.recycle()
                    }
                }

                onNewFrameAvailable(jpegBytes)

                // Update FPS calculation
                framesSinceLastUpdate++
                frameCount.incrementAndGet()
                val elapsed = now - lastFpsUpdateTime
                if (elapsed >= 1000) {
                    val fps = (framesSinceLastUpdate * 1000f) / elapsed
                    _measuredFps.value = (fps * 10f).toInt() / 10f
                    framesSinceLastUpdate = 0
                    lastFpsUpdateTime = now
                }
            } catch (e: Exception) {
                Log.w("CyberCameraManager", "Error processing camera frame", e)
            } finally {
                imageProxy.close()
                isProcessingFrame.set(false)
            }
        }

        try {
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )

            // Apply torch & zoom
            setTorch(currentConfig.isTorchOn)
            setZoom(currentConfig.zoomFactor)
        } catch (e: Exception) {
            Log.e("CyberCameraManager", "Failed to bind camera to lifecycle", e)
        }
    }

    fun setTorch(enable: Boolean) {
        currentConfig = currentConfig.copy(isTorchOn = enable)
        try {
            if (camera?.cameraInfo?.hasFlashUnit() == true) {
                camera?.cameraControl?.enableTorch(enable)
            }
        } catch (e: Exception) {
            Log.w("CyberCameraManager", "Torch error", e)
        }
    }

    fun setZoom(zoomFactor: Float) {
        val zoom = zoomFactor.coerceIn(1.0f, 10.0f)
        currentConfig = currentConfig.copy(zoomFactor = zoom)
        try {
            camera?.cameraControl?.setZoomRatio(zoom)
        } catch (e: Exception) {
            Log.w("CyberCameraManager", "Zoom error", e)
        }
    }

    fun switchCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView? = null) {
        val newFacing = if (currentConfig.cameraFacing == CameraFacing.BACK) {
            CameraFacing.FRONT
        } else {
            CameraFacing.BACK
        }
        currentConfig = currentConfig.copy(cameraFacing = newFacing, isTorchOn = false)
        bindCameraUseCases(lifecycleOwner, previewView)
    }

    fun updateConfig(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView?,
        newConfig: CyberConfig
    ) {
        val needsRebind = (newConfig.cameraFacing != currentConfig.cameraFacing) ||
                (newConfig.resolution != currentConfig.resolution)

        currentConfig = newConfig

        if (needsRebind) {
            bindCameraUseCases(lifecycleOwner, previewView)
        } else {
            setTorch(newConfig.isTorchOn)
            setZoom(newConfig.zoomFactor)
        }
    }

    private fun generatePrivacyPauseBitmap(width: Int, height: Int): Bitmap {
        val targetW = width.coerceAtLeast(320)
        val targetH = height.coerceAtLeast(240)
        if (cachedPauseBitmap != null && cachedPauseWidth == targetW && cachedPauseHeight == targetH) {
            return cachedPauseBitmap!!
        }

        val bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        
        // Dark cyber slate background
        canvas.drawColor(android.graphics.Color.rgb(8, 12, 20))
        
        val paintGrid = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(40, 0, 229, 255)
            strokeWidth = 2f
            style = android.graphics.Paint.Style.STROKE
        }
        
        // Grid pattern
        val step = 40f
        var x = 0f
        while (x < targetW) {
            canvas.drawLine(x, 0f, x, targetH.toFloat(), paintGrid)
            x += step
        }
        var y = 0f
        while (y < targetH) {
            canvas.drawLine(0f, y, targetW.toFloat(), y, paintGrid)
            y += step
        }
        
        val cx = targetW / 2f
        val cy = targetH / 2f
        
        // Glowing Pause Banner Box
        val bannerPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(220, 20, 28, 46)
            style = android.graphics.Paint.Style.FILL
        }
        val borderPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.rgb(255, 61, 113) // Cyber red
            strokeWidth = 3f
            style = android.graphics.Paint.Style.STROKE
        }
        
        val rect = android.graphics.RectF(cx - 240f, cy - 90f, cx + 240f, cy + 90f)
        canvas.drawRoundRect(rect, 20f, 20f, bannerPaint)
        canvas.drawRoundRect(rect, 20f, 20f, borderPaint)
        
        // Pause Double Bars Icon
        val barPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.rgb(255, 61, 113)
            style = android.graphics.Paint.Style.FILL
        }
        canvas.drawRoundRect(android.graphics.RectF(cx - 25f, cy - 50f, cx - 10f, cy - 10f), 6f, 6f, barPaint)
        canvas.drawRoundRect(android.graphics.RectF(cx + 10f, cy - 50f, cx + 25f, cy - 10f), 6f, 6f, barPaint)
        
        // Text
        val textTitlePaint = android.graphics.Paint().apply {
            color = android.graphics.Color.rgb(255, 255, 255)
            textSize = 22f
            typeface = android.graphics.Typeface.MONOSPACE
            textAlign = android.graphics.Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText("VIDEO PAUSED // PRIVACY HOLD", cx, cy + 25f, textTitlePaint)
        
        val textSubPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.rgb(0, 229, 255)
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            textAlign = android.graphics.Paint.Align.CENTER
        }
        canvas.drawText("AUDIO & CALL LINK ACTIVE", cx, cy + 55f, textSubPaint)
        
        cachedPauseBitmap = bitmap
        cachedPauseWidth = targetW
        cachedPauseHeight = targetH

        return bitmap
    }

    fun release() {
        try {
            cameraProvider?.unbindAll()
            analysisExecutor.shutdown()
        } catch (_: Exception) {}
    }
}
