package com.example

import org.junit.Assert.*
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun `test default CyberConfig settings`() {
    val config = com.example.model.CyberConfig()
    assertEquals(com.example.model.StreamResolution.HD_720P, config.resolution)
    assertEquals(30, config.targetFps)
    assertEquals(com.example.model.CameraFacing.BACK, config.cameraFacing)
    assertFalse(config.isTorchOn)
    assertEquals(1.0f, config.zoomFactor, 0.001f)
    assertFalse(config.isVideoPaused)
    assertFalse(config.isMicMuted)
    assertTrue(config.isSpeakerEnabled)
    assertEquals(com.example.model.AudioRouting.SPEAKERPHONE, config.audioRouting)
    assertEquals(8080, config.serverPort)
  }

  @Test
  fun `test stream resolutions dimensions`() {
    val sd = com.example.model.StreamResolution.SD_480P
    assertEquals(640, sd.width)
    assertEquals(480, sd.height)

    val hd = com.example.model.StreamResolution.HD_720P
    assertEquals(1280, hd.width)
    assertEquals(720, hd.height)

    val fhd = com.example.model.StreamResolution.FHD_1080P
    assertEquals(1920, fhd.width)
    assertEquals(1080, fhd.height)

    val uhd = com.example.model.StreamResolution.UHD_4K
    assertEquals(3840, uhd.width)
    assertEquals(2160, uhd.height)
  }

  @Test
  fun `test CyberFilter enum integrity`() {
    val filters = com.example.model.CyberFilter.values()
    assertTrue(filters.contains(com.example.model.CyberFilter.NONE))
    assertTrue(filters.contains(com.example.model.CyberFilter.CYBER_HUD))
    assertTrue(filters.contains(com.example.model.CyberFilter.MATRIX_RAIN))
    assertTrue(filters.contains(com.example.model.CyberFilter.CHROMA_GREEN))
    assertTrue(filters.contains(com.example.model.CyberFilter.NIGHT_VISION))
    assertTrue(filters.contains(com.example.model.CyberFilter.MONOKAI_CYBER))
  }

  @Test
  fun `test PairedDevice entity creation`() {
    val device = com.example.model.PairedDevice(
      id = "test-uuid",
      name = "Subhojit-PC",
      ipAddress = "192.168.1.50",
      port = 8080
    )
    assertEquals("test-uuid", device.id)
    assertEquals("Subhojit-PC", device.name)
    assertEquals("192.168.1.50", device.ipAddress)
    assertEquals(8080, device.port)
  }
}
