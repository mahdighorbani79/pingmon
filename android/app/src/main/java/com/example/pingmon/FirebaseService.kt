package com.example.pingmon

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class FirebaseService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Log.i("FCM", "Push received — waking PingService")
        // Wake PingService to ping immediately
        PingService.start(this)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i("FCM", "New FCM token: $token")
        // Store token for sending to server
        getSharedPreferences(PingService.PREFS, MODE_PRIVATE)
            .edit().putString("fcm_token", token).apply()
    }
}
