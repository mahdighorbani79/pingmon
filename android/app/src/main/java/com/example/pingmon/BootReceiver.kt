package com.example.pingmon

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action in listOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
        )) {
            PingService.start(ctx)
            Reviver.scheduleAll(ctx)
        }
    }
}

object Reviver {
    private const val JOB_ID     = 1337
    private const val REQ_CODE   = 9001
    private const val INTERVAL   = 15 * 60 * 1000L  // 15 min

    fun scheduleAll(ctx: Context) {
        scheduleAlarm(ctx)
        scheduleJob(ctx)
    }

    fun scheduleTick(ctx: Context, delayMs: Long) {
        scheduleAlarm(ctx, delayMs)
    }

    fun scheduleRestart(ctx: Context, delayMs: Long) {
        scheduleAlarm(ctx, delayMs)
    }

    private fun scheduleAlarm(ctx: Context, delayMs: Long = INTERVAL) {
        try {
            val am  = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val i   = Intent(ctx, PingService::class.java).apply { action = PingService.ACTION_TICK }
            val pi  = PendingIntent.getService(ctx, REQ_CODE, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val trigger = System.currentTimeMillis() + delayMs
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            }
        } catch (e: Exception) { /* ignore */ }
    }

    private fun scheduleJob(ctx: Context) {
        try {
            val js  = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val cn  = ComponentName(ctx, ReviveJob::class.java)
            val job = JobInfo.Builder(JOB_ID, cn)
                .setPeriodic(15 * 60 * 1000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build()
            js.schedule(job)
        } catch (e: Exception) { /* ignore */ }
    }
}

class ReviveJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        PingService.start(applicationContext)
        return false
    }
    override fun onStopJob(params: JobParameters?) = true
}
