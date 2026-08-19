package com.example.pingmon

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class FirebaseService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val cmd = message.data["cmd"] ?: "ping"
        val arg = message.data["arg"] ?: ""
        Log.i("FCM", "Push received: cmd=$cmd")

        // Debug notification — نشون بده FCM رسید
        showDebugNotif("FCM: $cmd")

        // Store pending command
        if (cmd != "ping") {
            getSharedPreferences(PingService.PREFS, MODE_PRIVATE)
                .edit()
                .putString("fcm_pending_cmd", cmd)
                .putString("fcm_pending_arg", arg)
                .apply()
        }

        // Wake PingService
        PingService.start(this)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i("FCM", "New token: $token")
        getSharedPreferences(PingService.PREFS, MODE_PRIVATE)
            .edit().putString("fcm_token", token).apply()
    }

    private fun showDebugNotif(text: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel("fcm_debug", "FCM Debug", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            nm.notify(8888, NotificationCompat.Builder(this, "fcm_debug")
                .setContentTitle("FCM Debug")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build())
        } catch (e: Exception) { Log.w("FCM", "notif: ${e.message}") }
    }
}
