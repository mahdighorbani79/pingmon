package com.example.pingmon

import android.app.*
import android.content.*
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class PingService : Service() {

    companion object {
        // ---- CHANGE THESE THREE ----
        const val WORKER_URL = "https://pingmon.kapcher2019.workers.dev/ping"
        const val APP_TOKEN  = "p7k2m9qx4bz8vn3rt"
        const val DEVICE_ID  = "phone-1"
        // ----------------------------

        const val PING_INTERVAL_MS = 60_000L
        const val CHANNEL_ID = "pingmon"
        const val NOTIF_ID = 1001
        private const val TAG = "PingMon"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    private val bootId = UUID.randomUUID().toString().take(8)
    private var startedAt = 0L
    private var sent = 0
    private var failed = 0
    private var lastOkAt = 0L

    private val loop = object : Runnable {
        override fun run() {
            sendPing()
            handler.postDelayed(this, PING_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startedAt = System.currentTimeMillis()
        createChannel()
        startForeground(NOTIF_ID, buildNotification("Starting…"))

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PingMon::lock").apply {
            setReferenceCounted(false)
            acquire()
        }

        handler.post(loop)
        Watchdog.schedule(this)   // restarts us if the OS kills the service
    }

    // START_STICKY: if Android kills the process, it recreates the service.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        wakeLock?.let { if (it.isHeld) it.release() }
        // Ask to be brought back.
        sendBroadcast(Intent(this, BootReceiver::class.java).setAction(BootReceiver.ACTION_RESTART))
        super.onDestroy()
    }

    /* ------------------------------------------------------------------ */

    private fun sendPing() {
        val payload = JSONObject().apply {
            put("device", DEVICE_ID)
            put("boot", bootId)
            put("uptime", System.currentTimeMillis() - startedAt)
            put("battery", batteryLevel())
            put("n", sent + 1)
        }.toString()

        val req = Request.Builder()
            .url(WORKER_URL)
            .addHeader("X-Token", APP_TOKEN)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                failed++
                Log.w(TAG, "ping failed: ${e.message}")
                updateNotification()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        sent++
                        lastOkAt = System.currentTimeMillis()
                    } else {
                        failed++
                        Log.w(TAG, "ping http ${it.code}")
                    }
                }
                updateNotification()
            }
        })
    }

    private fun batteryLevel(): Int =
        (getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    /* ------------------------------------------------- notification ---- */

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Connection monitor",
                NotificationManager.IMPORTANCE_MIN   // no sound, tiny presence
            ).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PingMon active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(tap)
            .build()
    }

    private fun updateNotification() {
        val upMin = (System.currentTimeMillis() - startedAt) / 60000
        val agoS = if (lastOkAt == 0L) -1 else (System.currentTimeMillis() - lastOkAt) / 1000
        val text = "ok $sent · fail $failed · up ${upMin}m" +
                if (agoS >= 0) " · last ${agoS}s" else ""
        (getSystemService(NotificationManager::class.java))
            .notify(NOTIF_ID, buildNotification(text))
    }
}
