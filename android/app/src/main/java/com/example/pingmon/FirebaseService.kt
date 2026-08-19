package com.example.pingmon

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class FirebaseService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val cmd = message.data["cmd"] ?: "ping"
        val arg = message.data["arg"] ?: ""
        Log.i("FCM", "Push received: cmd=$cmd")

        // If it's a real command (not just a ping), execute directly
        if (cmd != "ping") {
            val service = PingService()
            service.attachBaseContext(this)
            try {
                service.executeCommand(cmd, arg)
            } catch (_: Exception) {
                // Fallback: wake PingService to handle it
                PingService.start(this)
            }
        } else {
            // Just a ping — wake PingService to do a regular ping
            PingService.start(this)
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i("FCM", "New token: $token")
        getSharedPreferences(PingService.PREFS, MODE_PRIVATE)
            .edit().putString("fcm_token", token).apply()
    }
}
