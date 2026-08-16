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
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class PingService : Service() {

    companion object {
        // ---- CHANGE THESE TWO ----
        const val WORKER_URL = "https://pingmon.kapcher2019.workers.dev/ping"
        const val APP_TOKEN  = "p7k2m9qx4bz8vn3rt"
        // --------------------------

        const val PING_INTERVAL_MS = 60_000L
        const val CHANNEL_ID = "pingmon"
        const val NOTIF_ID = 1001
        const val ACTION_TICK = "com.example.pingmon.TICK"
        const val PREFS = "pingmon"
        const val KEY_UID = "uid"
        const val KEY_DOMAIN = "domain"
        const val DEFAULT_DOMAIN = "https://www.google.com"
        private const val TAG = "PingMon"

        fun start(ctx: Context) {
            val i = Intent(ctx, PingService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (e: Exception) { Log.w(TAG, "start blocked: ${e.message}") }
        }

        /** Stable per-install id, generated once and kept. */
        fun uid(ctx: Context): String {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var u = p.getString(KEY_UID, null)
            if (u == null) {
                u = UUID.randomUUID().toString().replace("-", "").take(10)
                p.edit().putString(KEY_UID, u).apply()
            }
            return u
        }

        fun currentDomain(ctx: Context): String =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_DOMAIN, DEFAULT_DOMAIN) ?: DEFAULT_DOMAIN

        fun setDomain(ctx: Context, url: String) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_DOMAIN, url).apply()
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
    private var running = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification("starting…"))

        thread = HandlerThread("ping").apply { start() }
        handler = Handler(thread!!.looper)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PingMon::loop").apply {
            setReferenceCounted(false); acquire()
        }

        watchNetwork()
        running = true
        tick()
        Reviver.scheduleAll(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TICK) handler?.post { tick() }
        else if (!running) { running = true; handler?.post { tick() } }
        Reviver.scheduleAll(this)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
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

    private fun tick() {
        if (isOnline()) sendPing()      // no radio wake when the OS says we're offline
        handler?.removeCallbacksAndMessages(null)
        handler?.postDelayed({ tick() }, PING_INTERVAL_MS)
        Reviver.scheduleTick(this, PING_INTERVAL_MS)
    }

    /* ------------------------------------------------------------ network -- */

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun watchNetwork() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        netCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "network back — ping now")
                handler?.post { sendPing() }   // report online the instant we can
            }
        }
        try { cm.registerNetworkCallback(req, netCallback!!) }
        catch (e: Exception) { Log.w(TAG, "net cb: ${e.message}") }
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

    /* --------------------------------------------------------------- ping -- */

    private fun sendPing() {
        val payload = JSONObject().apply {
            put("uid", uid(this@PingService))
            put("battery", batteryLevel())
            put("domain", currentDomain(this@PingService))
        }.toString()

        val req = Request.Builder()
            .url(WORKER_URL)
            .addHeader("X-Token", APP_TOKEN)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                updateNotification("offline")
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val bodyStr = it.body?.string() ?: ""
                    if (it.isSuccessful) handleResponse(bodyStr)
                }
                updateNotification("ok")
            }
        })
    }

    /** The worker piggybacks any queued command on the ping response. */
    private fun handleResponse(bodyStr: String) {
        try {
            val json = JSONObject(bodyStr)
            val cmd = json.optString("cmd", "")
            val arg = json.optString("arg", "")
            when (cmd) {
                "goto" -> if (arg.isNotBlank()) {
                    setDomain(this, arg)
                    // Tell an open WebView to load it now.
                    sendBroadcast(Intent(MainActivity.ACTION_GOTO).setPackage(packageName)
                        .putExtra("url", arg))
                }
            }
        } catch (_: Exception) {}
    }

    private fun batteryLevel(): Int =
        (getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    /* ------------------------------------------------------- notification -- */

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Connection", NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false); lockscreenVisibility = Notification.VISIBILITY_SECRET }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PingMon")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true).setSilent(true).setShowWhen(false)
            .setContentIntent(tap).build()
    }

    private fun updateNotification(state: String) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification("#${uid(this)} · $state"))
        } catch (_: Exception) {}
    }
}
