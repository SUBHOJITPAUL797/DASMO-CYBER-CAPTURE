package com.example.ui.components

import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.model.CyberConfig
import com.example.model.CyberStreamStats
import com.example.ui.theme.*
import com.example.viewmodel.CyberCaptureViewModel

@Composable
fun CyberViewfinder(
    viewModel: CyberCaptureViewModel,
    config: CyberConfig,
    stats: CyberStreamStats,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF070B12))
            .border(1.5.dp, if (stats.isStreaming) StudioPrimary else StudioBorderSubtle, RoundedCornerShape(16.dp))
            .testTag("cyber_viewfinder_container")
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    val newZoom = (config.zoomFactor * zoom).coerceIn(1.0f, 8.0f)
                    viewModel.setZoom(newZoom)
                }
            }
    ) {
        // CameraX Surface View
        AndroidView(
            factory = {
                viewModel.initCameraManager(lifecycleOwner, previewView)
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay: Tactical Cyber HUD Grid & Reticle
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            if (config.showGrid) {
                // Rule of Thirds Grid
                val gridColor = StudioPrimary.copy(alpha = 0.2f)
                drawLine(gridColor, Offset(w / 3f, 0f), Offset(w / 3f, h), strokeWidth = 1.dp.toPx())
                drawLine(gridColor, Offset(2 * w / 3f, 0f), Offset(2 * w / 3f, h), strokeWidth = 1.dp.toPx())
                drawLine(gridColor, Offset(0f, h / 3f), Offset(w, h / 3f), strokeWidth = 1.dp.toPx())
                drawLine(gridColor, Offset(0f, 2 * h / 3f), Offset(w, 2 * h / 3f), strokeWidth = 1.dp.toPx())
            }

            if (config.showTargetReticle) {
                val cx = w / 2f
                val cy = h / 2f
                val reticleRadius = 45.dp.toPx()
                val reticleColor = if (stats.isStreaming) StudioPrimary else StudioPrimary.copy(alpha = 0.5f)

                // Center Reticle
                drawCircle(
                    color = reticleColor,
                    radius = reticleRadius,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.5.dp.toPx())
                )
                drawCircle(
                    color = reticleColor,
                    radius = 4.dp.toPx(),
                    center = Offset(cx, cy)
                )
                drawLine(reticleColor, Offset(cx - reticleRadius - 15f, cy), Offset(cx - reticleRadius + 15f, cy), strokeWidth = 1.5.dp.toPx())
                drawLine(reticleColor, Offset(cx + reticleRadius - 15f, cy), Offset(cx + reticleRadius + 15f, cy), strokeWidth = 1.5.dp.toPx())
                drawLine(reticleColor, Offset(cx, cy - reticleRadius - 15f), Offset(cx, cy - reticleRadius + 15f), strokeWidth = 1.5.dp.toPx())
                drawLine(reticleColor, Offset(cx, cy + reticleRadius - 15f), Offset(cx, cy + reticleRadius + 15f), strokeWidth = 1.5.dp.toPx())

                // Corner Brackets
                val pad = 16.dp.toPx()
                val bracketLen = 24.dp.toPx()
                val bracketColor = if (stats.isStreaming) StudioPrimary else StudioPrimary.copy(alpha = 0.4f)

                // Top-Left
                drawLine(bracketColor, Offset(pad, pad), Offset(pad + bracketLen, pad), strokeWidth = 2.dp.toPx())
                drawLine(bracketColor, Offset(pad, pad), Offset(pad, pad + bracketLen), strokeWidth = 2.dp.toPx())
                // Top-Right
                drawLine(bracketColor, Offset(w - pad, pad), Offset(w - pad - bracketLen, pad), strokeWidth = 2.dp.toPx())
                drawLine(bracketColor, Offset(w - pad, pad), Offset(w - pad, pad + bracketLen), strokeWidth = 2.dp.toPx())
                // Bottom-Left
                drawLine(bracketColor, Offset(pad, h - pad), Offset(pad + bracketLen, h - pad), strokeWidth = 2.dp.toPx())
                drawLine(bracketColor, Offset(pad, h - pad), Offset(pad, h - pad - bracketLen), strokeWidth = 2.dp.toPx())
                // Bottom-Right
                drawLine(bracketColor, Offset(w - pad, h - pad), Offset(w - pad - bracketLen, h - pad), strokeWidth = 2.dp.toPx())
                drawLine(bracketColor, Offset(w - pad, h - pad), Offset(w - pad, pad + bracketLen), strokeWidth = 2.dp.toPx())
            }
        }

        // Viewfinder Top Badge Overlay
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Text(
                text = "DASMO STUDIO · [${config.resolution.label}] ${if (config.activeFilter != com.example.model.CyberFilter.NONE) "· ${config.activeFilter.displayName}" else ""}",
                color = StudioPrimary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.TopStart)
            )

            Text(
                text = "ZOOM ${String.format("%.1f", config.zoomFactor)}x",
                color = StudioTextSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }

        // Viewfinder Bottom Indicator
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Text(
                text = if (stats.isStreaming) "LIVE AIR LINK STREAMING" else "STUDIO READY · TAP START",
                color = if (stats.isStreaming) StudioSuccess else StudioTextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomStart)
            )

            if (stats.isStreaming) {
                Text(
                    text = "${stats.fps} FPS · ${stats.bitrateKbps} kbps",
                    color = StudioPrimary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.BottomEnd)
                )
            }
        }
    }
}
