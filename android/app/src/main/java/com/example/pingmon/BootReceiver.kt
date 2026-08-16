package com.example.pingmon

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Two jobs:
 *  1. Start the service after the phone reboots.
 *  2. Act as the target of a repeating AlarmManager "watchdog" that restarts
 *     the service if the OS killed it.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_RESTART = "com.example.pingmon.RESTART"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val svc = Intent(context, PingService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(svc)
            else
                context.startService(svc)
        } catch (_: Exception) { /* app in background restrictions */ }

        Watchdog.schedule(context)
    }
}

object Watchdog {
    private const val INTERVAL_MS = 15 * 60 * 1000L   // 15 min

    fun schedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, 99,
            Intent(context, BootReceiver::class.java).setAction(BootReceiver.ACTION_RESTART),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Inexact + allow-while-idle: works in Doze, needs no special permission.
        am.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + INTERVAL_MS,
            pi
        )
    }
}
