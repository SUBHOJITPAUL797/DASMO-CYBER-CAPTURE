package com.example.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import com.example.model.CyberFilter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

object CyberFilterRenderer {

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SS", Locale.US)
    private val matrixCharacters = "010101XYZDASMOTAK9876543210ABCDEF"

    private val greenPaint = Paint().apply {
        color = Color.rgb(0, 255, 120)
        textSize = 28f
        typeface = Typeface.MONOSPACE
        isAntiAlias = true
    }

    private val cyanPaint = Paint().apply {
        color = Color.rgb(0, 229, 255)
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val textCyanPaint = Paint().apply {
        color = Color.rgb(0, 229, 255)
        textSize = 26f
        typeface = Typeface.MONOSPACE
        isAntiAlias = true
    }

    private val textWhitePaint = Paint().apply {
        color = Color.WHITE
        textSize = 24f
        typeface = Typeface.MONOSPACE
        isAntiAlias = true
    }

    fun applyFilter(source: Bitmap, filter: CyberFilter): Bitmap {
        if (filter == CyberFilter.NONE) return source

        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val w = output.width
        val h = output.height

        when (filter) {
            CyberFilter.CYBER_HUD -> {
                // Tactical HUD Reticle
                val cx = w / 2f
                val cy = h / 2f
                val radius = (w.coerceAtMost(h) * 0.22f)

                // Outer circle & ticks
                canvas.drawCircle(cx, cy, radius, cyanPaint)
                canvas.drawCircle(cx, cy, radius * 0.6f, cyanPaint)
                canvas.drawLine(cx - radius - 20, cy, cx + radius + 20, cy, cyanPaint)
                canvas.drawLine(cx, cy - radius - 20, cx, cy + radius + 20, cyanPaint)

                // Corner brackets
                val pad = 40f
                val len = 60f
                // Top Left
                canvas.drawLine(pad, pad, pad + len, pad, cyanPaint)
                canvas.drawLine(pad, pad, pad, pad + len, cyanPaint)
                // Top Right
                canvas.drawLine(w - pad, pad, w - pad - len, pad, cyanPaint)
                canvas.drawLine(w - pad, pad, w - pad, pad + len, cyanPaint)
                // Bottom Left
                canvas.drawLine(pad, h - pad, pad + len, h - pad, cyanPaint)
                canvas.drawLine(pad, h - pad, pad, h - pad - len, cyanPaint)
                // Bottom Right
                canvas.drawLine(w - pad, h - pad, w - pad - len, h - pad, cyanPaint)
                canvas.drawLine(w - pad, h - pad, w - pad, h - pad - len, cyanPaint)

                // Telemetry text
                val timeStr = timeFormat.format(Date())
                canvas.drawText("DASMO CYBER CAPTURE // SYS_LOCK", pad + 10, pad + 35, textCyanPaint)
                canvas.drawText("TGT_RES: ${w}x$h | AZM: 142.8°", pad + 10, pad + 70, textWhitePaint)
                canvas.drawText("TC: $timeStr", w - 340f, pad + 35, textCyanPaint)
                canvas.drawText("STATUS: ZERO-USB AIR LINK", pad + 10, h - pad - 15, textCyanPaint)
            }

            CyberFilter.MATRIX_RAIN -> {
                // Matrix digital stream rain
                val cols = (w / 35).coerceAtLeast(10)
                for (c in 0 until cols) {
                    val x = c * 35f + 10f
                    val rows = Random.nextInt(4, 14)
                    for (r in 0 until rows) {
                        val y = (Random.nextInt(h)).toFloat()
                        val char = matrixCharacters[Random.nextInt(matrixCharacters.length)].toString()
                        greenPaint.alpha = Random.nextInt(120, 255)
                        canvas.drawText(char, x, y, greenPaint)
                    }
                }
                // Scanlines
                val scanPaint = Paint().apply {
                    color = Color.argb(40, 0, 255, 100)
                    strokeWidth = 1.5f
                }
                var sy = 0f
                while (sy < h) {
                    canvas.drawLine(0f, sy, w.toFloat(), sy, scanPaint)
                    sy += 6f
                }
            }

            CyberFilter.CHROMA_GREEN -> {
                // Chroma Green border frame
                val chromaPaint = Paint().apply {
                    color = Color.rgb(0, 255, 0)
                    style = Paint.Style.STROKE
                    strokeWidth = 30f
                }
                canvas.drawRect(15f, 15f, w - 15f, h - 15f, chromaPaint)
                canvas.drawText("CHROMA MATRIX GREEN // KEY READY", 60f, 60f, textCyanPaint)
            }

            CyberFilter.NIGHT_VISION -> {
                // Night vision green tint & vignette
                val cm = ColorMatrix(floatArrayOf(
                    0.1f, 0.4f, 0.1f, 0f, 0f,
                    0.2f, 0.9f, 0.2f, 0f, 40f,
                    0.1f, 0.3f, 0.1f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                ))
                val nvPaint = Paint().apply {
                    colorFilter = ColorMatrixColorFilter(cm)
                }
                canvas.drawBitmap(output, 0f, 0f, nvPaint)

                // Scanline grid
                val gridPaint = Paint().apply {
                    color = Color.argb(30, 0, 255, 0)
                    strokeWidth = 2f
                }
                var gy = 0f
                while (gy < h) {
                    canvas.drawLine(0f, gy, w.toFloat(), gy, gridPaint)
                    gy += 8f
                }
                canvas.drawText("NV-GEN3 PHOSPHOR // GAIN: +18dB", 40f, 50f, greenPaint)
            }

            CyberFilter.MONOKAI_CYBER -> {
                // Cyberpunk Neon tint
                val cm = ColorMatrix(floatArrayOf(
                    0.4f, 0.2f, 0.8f, 0f, 20f,
                    0.1f, 0.8f, 0.4f, 0f, 10f,
                    0.8f, 0.2f, 1.2f, 0f, 40f,
                    0f, 0f, 0f, 1f, 0f
                ))
                val neonPaint = Paint().apply {
                    colorFilter = ColorMatrixColorFilter(cm)
                }
                canvas.drawBitmap(output, 0f, 0f, neonPaint)
                canvas.drawText("CYBER NEON 2077", 40f, 50f, textCyanPaint)
            }

            CyberFilter.STUDIO_WARM -> {
                // Soft Warmth Studio grading
                val cm = ColorMatrix(floatArrayOf(
                    1.15f, 0.05f, 0.0f, 0f, 15f,
                    0.05f, 1.05f, 0.0f, 0f, 8f,
                    0.0f, 0.05f, 0.90f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                ))
                val warmPaint = Paint().apply {
                    colorFilter = ColorMatrixColorFilter(cm)
                }
                canvas.drawBitmap(output, 0f, 0f, warmPaint)
            }

            CyberFilter.BLUR_SIM -> {
                // Vignette privacy border & studio frame
                val blurBorderPaint = Paint().apply {
                    color = Color.argb(160, 10, 14, 26)
                    style = Paint.Style.STROKE
                    strokeWidth = 60f
                }
                canvas.drawRect(30f, 30f, w - 30f, h - 30f, blurBorderPaint)
                canvas.drawText("PRIVACY FOCUS MODE ACTIVE", 80f, 80f, textCyanPaint)
            }

            CyberFilter.GLITCH_EFFECT -> {
                // Digital sync chromatic glitch lines
                for (i in 0 until 5) {
                    val gy = Random.nextInt(0, h - 30)
                    val gh = Random.nextInt(6, 25)
                    val offset = Random.nextInt(-30, 30)
                    val srcRect = Rect(0, gy, w, gy + gh)
                    val dstRect = Rect(offset, gy, w + offset, gy + gh)
                    canvas.drawBitmap(output, srcRect, dstRect, null)
                }
                canvas.drawText("VAPOR_GLITCH // RESYNC", 40f, 50f, textCyanPaint)
            }
            else -> {}
        }

        return output
    }
}
