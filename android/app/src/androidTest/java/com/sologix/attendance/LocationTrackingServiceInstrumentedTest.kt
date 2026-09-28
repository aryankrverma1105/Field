package com.sologix.attendance

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.sologix.attendance.service.LocationTrackingService
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocationTrackingServiceInstrumentedTest {

    @get:Rule
    val locationPermissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        LocationTrackingService.stopTracking(context)
    }

    @Test
    fun foregroundServiceStartsWithoutSecurityExceptionWhenPermissionGranted() {
        try {
            LocationTrackingService.startTracking(context, "test_user_instrumented")
            // Give service a moment to execute onStartCommand
            Thread.sleep(1000)
            // Stop service cleanly
            LocationTrackingService.stopTracking(context)
        } catch (e: SecurityException) {
            fail("Service failed with SecurityException despite permissions granted: ${e.message}")
        }
    }

    @Test
    fun foregroundServiceStopsCleanlyWhenStopped() {
        LocationTrackingService.startTracking(context, "test_user_instrumented")
        Thread.sleep(500)
        LocationTrackingService.stopTracking(context)
        Thread.sleep(500)
    }
}
