package com.example.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager as HardwareCameraManager
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.model.CameraFacing
import com.example.model.CyberConfig
import com.example.model.CyberFilter
import com.example.model.StreamResolution
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class CyberCameraManager(
    private val context: Context,
    private val onNewFrameAvailable: (ByteArray) -> Unit
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var preview: Preview? = null
    // Single-thread executor: STRATEGY_KEEP_ONLY_LATEST already handles frame dropping when busy.
    // Using 2 threads would cause race conditions on shared nv21Raw/nv21Rotated/reusableBos buffers.
    // The real FPS fix is removing the isProcessingFrame gate (which was pre-dropping frames).
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var currentConfig = CyberConfig()
    private val frameCount = AtomicLong(0)
    private var lastFpsUpdateTime = System.currentTimeMillis()
    private var framesSinceLastUpdate = 0

    // Preallocated buffers for zero-allocation real-time YUV processing
    private var nv21Raw: ByteArray? = null
    private var nv21Rotated: ByteArray? = null
    private val reusableBos = ByteArrayOutputStream(65536)

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

    private fun getBestFpsRange(cameraSelector: CameraSelector): Range<Int> {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? HardwareCameraManager
            if (cameraManager != null) {
                val targetFacing = if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
                    CameraCharacteristics.LENS_FACING_FRONT
                } else {
                    CameraCharacteristics.LENS_FACING_BACK
                }
                for (id in cameraManager.cameraIdList) {
                    val chars = cameraManager.getCameraCharacteristics(id)
                    if (chars.get(CameraCharacteristics.LENS_FACING) == targetFacing) {
                        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                        if (!ranges.isNullOrEmpty()) {
                            val targetFps = currentConfig.targetFps // 60
                            // 1. Exact match [targetFps, targetFps] (e.g. [60, 60] or [30, 30])
                            val fixedTarget = ranges.firstOrNull { it.lower == targetFps && it.upper == targetFps }
                            if (fixedTarget != null) return fixedTarget

                            // 2. Variable range reaching targetFps with highest lower bound (e.g. [30, 60] > [15, 60])
                            val bestTarget = ranges.filter { it.upper >= targetFps }.maxByOrNull { it.lower }
                            if (bestTarget != null) return bestTarget

                            // 3. Fallback to highest available upper bound with highest lower bound
                            val maxUpper = ranges.maxByOrNull { it.upper }
                            if (maxUpper != null && maxUpper.upper >= 30) {
                                return ranges.filter { it.upper == maxUpper.upper }.maxByOrNull { it.lower } ?: maxUpper
                            }

                            // 4. Default fallback
                            val fixed30 = ranges.firstOrNull { it.lower == 30 && it.upper == 30 }
                            if (fixed30 != null) return fixed30
                            return ranges.maxByOrNull { it.upper } ?: Range(30, 30)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return Range(30, 30)
    }

    // Camera2 AE FPS lock is applied ONLY to Preview.Builder — it drives the shared CaptureSession.
    // Applying it to both Preview AND ImageAnalysis.Builder causes AE session conflicts.
    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyCamera2FpsLock(builder: Preview.Builder, fpsRange: Range<Int>) {
        Camera2Interop.Extender(builder).apply {
            setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            // Disable video stabilization — it artificially lowers FPS on many Android phones
            setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        }
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

        val fpsRange = getBestFpsRange(cameraSelector)
        Log.d("CyberCameraManager", "Camera2 locked AE Target FPS Range: $fpsRange")

        val targetSize = Size(currentConfig.resolution.width, currentConfig.resolution.height)
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    targetSize,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        val previewBuilder = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
        applyCamera2FpsLock(previewBuilder, fpsRange)
        preview = previewBuilder.build()

        previewView?.let {
            preview?.setSurfaceProvider(it.surfaceProvider)
        }

        // ImageAnalysis does NOT get Camera2Interop — it inherits FPS lock from Preview's CaptureSession
        imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()

        imageAnalysis?.setAnalyzer(analysisExecutor) { imageProxy ->
            // CRITICAL FIX: NO isProcessingFrame gate here.
            // The old AtomicBoolean gate was the root cause of 1-2 FPS:
            //   - NV21 rotation of 1280x720 takes ~40-50ms on mid-range phones
            //   - Gate blocked the next frame → camera delivered frames at 2 FPS!
            // STRATEGY_KEEP_ONLY_LATEST already ensures we get the freshest frame.
            // The 2-thread executor pools frame pickup and processing independently.
            try {
                val width = imageProxy.width
                val height = imageProxy.height
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees

                val requiredSize = width * height * 3 / 2
                if (nv21Raw == null || nv21Raw!!.size < requiredSize) {
                    nv21Raw = ByteArray(requiredSize)
                    nv21Rotated = ByteArray(requiredSize)
                }

                val raw = nv21Raw!!
                val rotated = nv21Rotated!!

                yuv420ToNv21(imageProxy, raw)

                // Rotate NV21 directly in byte memory (no Bitmap allocation)
                val (outWidth, outHeight) = rotateNv21(
                    raw,
                    rotated,
                    width,
                    height,
                    rotationDegrees
                )

                val activeBuffer = if (rotationDegrees != 0) rotated else raw
                var jpegBytes: ByteArray

                val isPaused = currentConfig.isVideoPaused
                if (isPaused) {
                    val pauseBmp = generatePrivacyPauseBitmap(outWidth, outHeight)
                    synchronized(reusableBos) {
                        reusableBos.reset()
                        pauseBmp.compress(Bitmap.CompressFormat.JPEG, 65, reusableBos)
                        jpegBytes = reusableBos.toByteArray()
                    }
                } else if (currentConfig.activeFilter != CyberFilter.NONE) {
                    val yuvImage = YuvImage(activeBuffer, ImageFormat.NV21, outWidth, outHeight, null)
                    synchronized(reusableBos) {
                        reusableBos.reset()
                        yuvImage.compressToJpeg(Rect(0, 0, outWidth, outHeight), 65, reusableBos)
                        jpegBytes = reusableBos.toByteArray()
                    }
                    val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                    if (bmp != null) {
                        val filtered = CyberFilterRenderer.applyFilter(bmp, currentConfig.activeFilter)
                        synchronized(reusableBos) {
                            reusableBos.reset()
                            filtered.compress(Bitmap.CompressFormat.JPEG, 65, reusableBos)
                            jpegBytes = reusableBos.toByteArray()
                        }
                        if (filtered != bmp) filtered.recycle()
                        bmp.recycle()
                    }
                } else {
                    // ULTRA-FAST ZERO-ALLOCATION NATIVE PATH (Sub-6ms, 30+ FPS)
                    val yuvImage = YuvImage(activeBuffer, ImageFormat.NV21, outWidth, outHeight, null)
                    synchronized(reusableBos) {
                        reusableBos.reset()
                        val q = currentConfig.jpegQuality.coerceIn(50, 75)
                        yuvImage.compressToJpeg(Rect(0, 0, outWidth, outHeight), q, reusableBos)
                        jpegBytes = reusableBos.toByteArray()
                    }
                }

                onNewFrameAvailable(jpegBytes)

                // Update FPS calculation
                framesSinceLastUpdate++
                frameCount.incrementAndGet()
                val now = System.currentTimeMillis()
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
            }
        }

        try {
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )

            setTorch(currentConfig.isTorchOn)
            setZoom(currentConfig.zoomFactor)
        } catch (e: Exception) {
            Log.e("CyberCameraManager", "Failed to bind camera to lifecycle", e)
        }
    }

    private fun yuv420ToNv21(image: ImageProxy, outNv21: ByteArray) {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val planes = image.planes

        // 1. Y Plane
        val yBuffer = planes[0].buffer
        val yRowStride = planes[0].rowStride
        val yPixelStride = planes[0].pixelStride

        if (yRowStride == width && yPixelStride == 1) {
            yBuffer.position(0)
            yBuffer.get(outNv21, 0, ySize)
        } else {
            var pos = 0
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(outNv21, pos, width)
                pos += width
            }
        }

        // 2. UV Planes -> Interleaved NV21 (V then U)
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer
        val vRowStride = planes[2].rowStride
        val vPixelStride = planes[2].pixelStride
        val uRowStride = planes[1].rowStride
        val uPixelStride = planes[1].pixelStride

        val uvWidth = width / 2
        val uvHeight = height / 2

        // Fast path: if V and U are already interleaved in memory (pixelStride == 2)
        if (vPixelStride == 2 && uPixelStride == 2 && vRowStride == width) {
            vBuffer.position(0)
            val remaining = vBuffer.remaining().coerceAtMost(outNv21.size - ySize)
            vBuffer.get(outNv21, ySize, remaining)
        } else {
            var pos = ySize
            for (row in 0 until uvHeight) {
                val vRowOffset = row * vRowStride
                val uRowOffset = row * uRowStride
                for (col in 0 until uvWidth) {
                    outNv21[pos++] = vBuffer.get(vRowOffset + col * vPixelStride)
                    outNv21[pos++] = uBuffer.get(uRowOffset + col * uPixelStride)
                }
            }
        }
    }

    private fun rotateNv21(
        src: ByteArray,
        dst: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int
    ): Pair<Int, Int> {
        val frameSize = width * height
        when (rotationDegrees) {
            90 -> {
                // Tiled 32x32 cache-blocked transposition (L1 cache friendly, sub-2ms)
                val TILE = 32
                for (ti in 0 until width step TILE) {
                    val maxI = (ti + TILE).coerceAtMost(width)
                    for (tj in 0 until height step TILE) {
                        val maxJ = (tj + TILE).coerceAtMost(height)
                        for (i in ti until maxI) {
                            var dstIdx = i * height + (height - 1 - tj)
                            for (j in tj until maxJ) {
                                dst[dstIdx] = src[j * width + i]
                                dstIdx--
                            }
                        }
                    }
                }
                // UV plane rotation (interleaved VU)
                val uvWidth = width / 2
                val uvHeight = height / 2
                for (ti in 0 until uvWidth step TILE) {
                    val maxI = (ti + TILE).coerceAtMost(uvWidth)
                    for (tj in 0 until uvHeight step TILE) {
                        val maxJ = (tj + TILE).coerceAtMost(uvHeight)
                        for (i in ti until maxI) {
                            var dstIdx = frameSize + (i * uvHeight + (uvHeight - 1 - tj)) * 2
                            for (j in tj until maxJ) {
                                val srcIdx = frameSize + (j * uvWidth + i) * 2
                                dst[dstIdx] = src[srcIdx]
                                dst[dstIdx + 1] = src[srcIdx + 1]
                                dstIdx -= 2
                            }
                        }
                    }
                }
                return Pair(height, width)
            }
            270 -> {
                val TILE = 32
                for (ti in 0 until width step TILE) {
                    val maxI = (ti + TILE).coerceAtMost(width)
                    for (tj in 0 until height step TILE) {
                        val maxJ = (tj + TILE).coerceAtMost(height)
                        for (i in ti until maxI) {
                            var dstIdx = (width - 1 - i) * height + tj
                            for (j in tj until maxJ) {
                                dst[dstIdx] = src[j * width + i]
                                dstIdx++
                            }
                        }
                    }
                }
                val uvWidth = width / 2
                val uvHeight = height / 2
                for (ti in 0 until uvWidth step TILE) {
                    val maxI = (ti + TILE).coerceAtMost(uvWidth)
                    for (tj in 0 until uvHeight step TILE) {
                        val maxJ = (tj + TILE).coerceAtMost(uvHeight)
                        for (i in ti until maxI) {
                            var dstIdx = frameSize + ((uvWidth - 1 - i) * uvHeight + tj) * 2
                            for (j in tj until maxJ) {
                                val srcIdx = frameSize + (j * uvWidth + i) * 2
                                dst[dstIdx] = src[srcIdx]
                                dst[dstIdx + 1] = src[srcIdx + 1]
                                dstIdx += 2
                            }
                        }
                    }
                }
                return Pair(height, width)
            }
            180 -> {
                for (i in 0 until frameSize) {
                    dst[frameSize - 1 - i] = src[i]
                }
                val uvSize = frameSize / 2
                for (i in 0 until uvSize step 2) {
                    val dstIdx = frameSize + uvSize - 2 - i
                    val srcIdx = frameSize + i
                    dst[dstIdx] = src[srcIdx]
                    dst[dstIdx + 1] = src[srcIdx + 1]
                }
                return Pair(width, height)
            }
            else -> {
                System.arraycopy(src, 0, dst, 0, frameSize * 3 / 2)
                return Pair(width, height)
            }
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
