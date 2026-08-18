package com.example.pingmon

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class FirebaseService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val cmd = message.data["cmd"] ?: "ping"
        Log.i("FCM", "Push received: $cmd — waking PingService")

        // Show notification so we can see if FCM arrives
        showDebugNotif("FCM received: $cmd")

        // Wake PingService to ping immediately
        PingService.start(this)
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
