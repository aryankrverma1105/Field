package com.sologix.attendance.location

import android.location.Location
import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

/**
 * Fix 4 Verification: Exercises MockLocationDetector on both branches:
 * - API 31+ (Build.VERSION_CODES.S / Android 12+) using location.isMock
 * - API < 31 (Android 8.0 Oreo to Android 11) using location.isFromMockProvider
 */
class MockLocationDetectorTest {

    @Test
    fun `API 34 Android 14 branch - detects mocked location via location isMock`() {
        val detector = MockLocationDetector(sdkIntProvider = { Build.VERSION_CODES.UPSIDE_DOWN_CAKE }) // API 34
        val location = mock(Location::class.java)
        `when`(location.isMock).thenReturn(true)

        val result = detector.isMock(location)

        assertTrue(result)
        verify(location).isMock
    }

    @Test
    fun `API 34 Android 14 branch - detects real non-mocked location via location isMock`() {
        val detector = MockLocationDetector(sdkIntProvider = { 34 })
        val location = mock(Location::class.java)
        `when`(location.isMock).thenReturn(false)

        val result = detector.isMock(location)

        assertFalse(result)
        verify(location).isMock
    }

    @Test
    fun `API 26 Android 8 branch - detects mocked location via legacy isFromMockProvider`() {
        val detector = MockLocationDetector(sdkIntProvider = { Build.VERSION_CODES.O }) // API 26
        val location = mock(Location::class.java)
        @Suppress("DEPRECATION")
        `when`(location.isFromMockProvider).thenReturn(true)

        val result = detector.isMock(location)

        assertTrue(result)
        @Suppress("DEPRECATION")
        verify(location).isFromMockProvider
    }

    @Test
    fun `API 26 Android 8 branch - detects real location via legacy isFromMockProvider`() {
        val detector = MockLocationDetector(sdkIntProvider = { 26 })
        val location = mock(Location::class.java)
        @Suppress("DEPRECATION")
        `when`(location.isFromMockProvider).thenReturn(false)

        val result = detector.isMock(location)

        assertFalse(result)
        @Suppress("DEPRECATION")
        verify(location).isFromMockProvider
    }
}
