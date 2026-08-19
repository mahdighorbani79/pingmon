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

        // Wake PingService — it will handle the command
        PingService.start(this)

        // For non-ping commands, also store in prefs so PingService picks it up
        if (cmd != "ping") {
            getSharedPreferences(PingService.PREFS, MODE_PRIVATE)
                .edit()
                .putString("fcm_pending_cmd", cmd)
                .putString("fcm_pending_arg", arg)
                .apply()
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i("FCM", "New token: $token")
        getSharedPreferences(PingService.PREFS, MODE_PRIVATE)
            .edit().putString("fcm_token", token).apply()
    }
}
