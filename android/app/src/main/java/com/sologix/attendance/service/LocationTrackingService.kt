// DESIGN DECISION: Option A (foreground-service-only) is the active background-location strategy for this application.
// Under Option A, ACCESS_BACKGROUND_LOCATION is NOT requested. Continuous tracking while on-shift is powered
// by this Foreground Service declaring foregroundServiceType="location" with a persistent, non-dismissible notification.
package com.sologix.attendance.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.sologix.attendance.MainActivity
import com.sologix.attendance.R
import com.sologix.attendance.data.local.AppDatabase
import com.sologix.attendance.data.local.entity.GpsPointEntity
import com.sologix.attendance.data.local.entity.OperationType
import com.sologix.attendance.data.local.entity.SyncQueueEntity
import com.sologix.attendance.data.local.entity.SyncState
import com.sologix.attendance.location.MockLocationDetector
import com.sologix.attendance.sync.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID

class LocationTrackingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val mockDetector = MockLocationDetector()

    private var activeUserId: String? = null

    companion object {
        const val CHANNEL_ID = "sologix_shift_tracking_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "ACTION_START_TRACKING"
        const val ACTION_STOP = "ACTION_STOP_TRACKING"
        const val EXTRA_USER_ID = "EXTRA_USER_ID"

        fun startTracking(context: Context, userId: String) {
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_USER_ID, userId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopTracking(context: Context) {
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val userId = activeUserId ?: return
                for (location in result.locations) {
                    val isMock = mockDetector.isMock(location)
                    recordTrackingPoint(userId, location.latitude, location.longitude, isMock)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                activeUserId = intent.getStringExtra(EXTRA_USER_ID)
                startForegroundWithNotification()
                startLocationUpdates()
            }
            ACTION_STOP -> {
                stopLocationUpdates()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        val notification = buildPersistentNotification()
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundType)
    }

    private fun buildPersistentNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sologix Duty Route Tracking Active")
            .setContentText("Your shift location is being reliably recorded.")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 30_000L)
            .setMinUpdateIntervalMillis(15_000L)
            .setMinUpdateDistanceMeters(10f)
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            // Missing fine/coarse permission
        }
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private fun recordTrackingPoint(userId: String, lat: Double, lng: Double, isMock: Boolean) {
        serviceScope.launch {
            val db = AppDatabase.getInstance(applicationContext)
            val pointId = UUID.randomUUID().toString()
            val operationId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()

            val gpsPoint = GpsPointEntity(
                id = pointId,
                userId = userId,
                lat = lat,
                lng = lng,
                isMocked = isMock,
                recordedAt = now,
                operationId = operationId,
                syncState = SyncState.PENDING
            )

            val payloadJson = """
                {
                    "id": "$pointId",
                    "userId": "$userId",
                    "lat": $lat,
                    "lng": $lng,
                    "isMocked": $isMock,
                    "recordedAt": $now,
                    "operationId": "$operationId"
                }
            """.trimIndent()

            val queueItem = SyncQueueEntity(
                operationId = operationId,
                entityType = "GPS_POINT",
                entityId = pointId,
                operationType = OperationType.GPS_POINT,
                payloadJson = payloadJson
            )

            // Local-first write: save both point and sync queue in local SQLite
            db.gpsPointDao().insert(gpsPoint)
            db.syncQueueDao().insert(queueItem)

            // Trigger sync
            SyncManager.triggerSync(applicationContext)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Active Shift Route Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifies worker that location tracking is running during active work hours"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
