package com.example.pingmon

import android.app.*
import android.content.*
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class PingService : Service() {

    companion object {
        // ---- CHANGE THESE ----
        const val WORKER_URL = "https://pingmon.kapcher2019.workers.dev/ping"
        const val APP_TOKEN  = "p7k2m9qx4bz8vn3rt"
        const val DEVICE_ID  = "phone-1"
        const val PING_INTERVAL_MS = 60_000L
        // ----------------------

        const val CHANNEL_ID = "pingmon"
        const val NOTIF_ID = 1001
        const val ACTION_TICK = "com.example.pingmon.TICK"
        private const val TAG = "PingMon"
        private const val PREFS = "pingmon_state"
        private const val KEY_QUEUE = "offline_queue"
        private const val MAX_QUEUE = 2000

        fun start(ctx: Context) {
            val i = Intent(ctx, PingService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ctx.startForegroundService(i)
                else
                    ctx.startService(i)
            } catch (e: Exception) {
                Log.w(TAG, "start blocked: ${e.message}")
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    private val bootId = UUID.randomUUID().toString().take(8)
    private var startedAt = 0L
    private var sent = 0
    private var failed = 0
    private var lastOkAt = 0L
    private var running = false

    /* ================================================================ life == */

    override fun onCreate() {
        super.onCreate()
        startedAt = System.currentTimeMillis()

        createChannel()
        startForeground(NOTIF_ID, buildNotification("starting…"))

        thread = HandlerThread("ping").apply { start() }
        handler = Handler(thread!!.looper)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PingMon::loop").apply {
            setReferenceCounted(false)
            acquire()
        }

        watchNetwork()
        running = true
        tick()
        Reviver.scheduleAll(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A chained alarm fired: do one ping and arm the next one.
        if (intent?.action == ACTION_TICK) {
            handler?.post { tick() }
        } else if (!running) {
            running = true
            handler?.post { tick() }
        }
        Reviver.scheduleAll(this)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TAG, "task removed — immediate restart scheduled")
        Reviver.scheduleRestart(this, 1_000)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        wakeLock?.let { if (it.isHeld) it.release() }
        unwatchNetwork()
        Reviver.scheduleRestart(this, 2_000)
        super.onDestroy()
    }

    /* ================================================================ loop == */

    /**
     * Layer 5: every tick arms the NEXT tick as a system alarm as well as a
     * handler post. If the thread dies, the alarm still fires; if the alarm is
     * throttled by Doze, the handler still runs. Two independent clocks.
     */
    private fun tick() {
        sendPing()
        handler?.removeCallbacksAndMessages(null)
        handler?.postDelayed({ tick() }, PING_INTERVAL_MS)
        Reviver.scheduleTick(this, PING_INTERVAL_MS)
    }

    /* ============================================================= network == */

    private fun watchNetwork() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        netCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "network back — flushing queue")
                handler?.post { sendPing() }
            }
        }
        try {
            cm.registerNetworkCallback(req, netCallback!!)
        } catch (e: Exception) {
            Log.w(TAG, "net callback failed: ${e.message}")
        }
    }

    private fun unwatchNetwork() {
        netCallback?.let {
            try {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        netCallback = null
    }

    /* ======================================================== offline queue == */

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readQueue(): JSONArray =
        try { JSONArray(prefs().getString(KEY_QUEUE, "[]")) } catch (_: Exception) { JSONArray() }

    private fun writeQueue(a: JSONArray) {
        prefs().edit().putString(KEY_QUEUE, a.toString()).apply()
    }

    /** A ping that could not be delivered is kept, not lost. */
    private fun enqueue(ts: Long) {
        val q = readQueue()
        if (q.length() >= MAX_QUEUE) return          // oldest data is least useful
        q.put(ts)
        writeQueue(q)
    }

    /* ================================================================ ping == */

    private fun sendPing() {
        val now = System.currentTimeMillis()
        val queue = readQueue()

        val payload = JSONObject().apply {
            put("device", DEVICE_ID)
            put("boot", bootId)
            put("uptime", now - startedAt)
            put("battery", batteryLevel())
            put("n", sent + 1)
            // Backfill: how many earlier pings failed to reach the server.
            put("backfill", queue.length())
            if (queue.length() > 0) put("backfill_from", queue.optLong(0))
        }.toString()

        val req = Request.Builder()
            .url(WORKER_URL)
            .addHeader("X-Token", APP_TOKEN)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                failed++
                enqueue(now)
                updateNotification()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        sent++
                        lastOkAt = System.currentTimeMillis()
                        // Server accepted the backfill — the queue is settled.
                        if (queue.length() > 0) writeQueue(JSONArray())
                    } else {
                        failed++
                        // 401 means a bad token: queueing would never drain.
                        if (it.code != 401) enqueue(now)
                    }
                }
                updateNotification()
            }
        })
    }

    private fun batteryLevel(): Int =
        (getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    /* ======================================================== notification == */

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Connection monitor", NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
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
        val queued = readQueue().length()
        val text = buildString {
            append("ok $sent · fail $failed · up ${upMin}m")
            if (agoS >= 0) append(" · last ${agoS}s")
            if (queued > 0) append(" · queued $queued")
        }
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {}
    }
}
