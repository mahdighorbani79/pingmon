package com.example.pingmon

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class FirebaseService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val cmd = message.data["cmd"] ?: "ping"
        Log.i("FCM", "Push received: $cmd — waking PingService")

        // Show notification so we can see if FCM arrives
        showDebugNotif("FCM received: $cmd")

        // Ping immediately in background thread — don't wait for PingService handler
        Thread {
            try {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val prefs   = getSharedPreferences(PingService.PREFS, android.content.Context.MODE_PRIVATE)
                val uid     = PingService.uid(this)
                val battery = (getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager)
                    .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val payload = org.json.JSONObject().apply {
                    put("uid", uid)
                    put("battery", battery)
                    put("model", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim())
                    put("android", android.os.Build.VERSION.RELEASE)
                    val srv = prefs.getString("pending_report", null)
                    if (!srv.isNullOrBlank()) put("report", srv)
                }.toString()
                val req = okhttp3.Request.Builder()
                    .url(PingService.WORKER_URL)
                    .addHeader("X-Token", PingService.APP_TOKEN)
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = org.json.JSONObject(resp.body?.string() ?: "{}")
                        val cmd  = body.optString("cmd", "")
                        if (cmd.isNotBlank()) {
                            Log.i("FCM", "Got command from FCM ping: $cmd")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("FCM", "instant ping failed: ${e.message}")
            }
            // Also wake PingService for full processing
            PingService.start(this@FirebaseService)
        }.start()
    }

    private fun showDebugNotif(text: String) {
        try {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val ch = android.app.NotificationChannel("fcm_debug", "FCM Debug", android.app.NotificationManager.IMPORTANCE_HIGH)
                nm.createNotificationChannel(ch)
            }
            val n = androidx.core.app.NotificationCompat.Builder(this, "fcm_debug")
                .setContentTitle("FCM Debug")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            nm.notify(9999, n)
        } catch (e: Exception) { Log.w("FCM", "notif: ${e.message}") }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i("FCM", "New FCM token: $token")
        // Store token for sending to server
        getSharedPreferences(PingService.PREFS, MODE_PRIVATE)
            .edit().putString("fcm_token", token).apply()
    }
}
