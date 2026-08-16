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
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * Revival layers, weakest to strongest:
 *   1. START_STICKY          — memory kills                    (PingService)
 *   2. onTaskRemoved         — swipe from Recents              (PingService)
 *   3. BootReceiver          — reboot, update, unlock, charger (here)
 *   4. AlarmManager watchdog — periodic + per-tick chain       (here)
 *   5. WorkManager           — OS-guaranteed, survives death   (here)
 *   6. JobScheduler          — independent of WorkManager      (here)
 *
 * Nothing survives a manual Force Stop / "Stop" in Running Services: Android
 * locks the app in FORCE_STOPPED until the user taps the icon. That is by
 * design and no code can work around it.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_REVIVE = "com.example.pingmon.REVIVE"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        PingService.start(context)
        Reviver.scheduleAll(context)
    }
}

object Reviver {

    private const val WATCHDOG_MS = 15 * 60 * 1000L
    private const val WORK_NAME = "pingmon-keepalive"
    private const val JOB_ID = 7701
    private const val REQ_REVIVE = 99
    private const val REQ_TICK = 100

    fun scheduleAll(context: Context) {
        scheduleRestart(context, WATCHDOG_MS)
        scheduleWork(context)
        scheduleJob(context)
    }

    /* --- layer 4a: watchdog that restarts a dead service ------------------ */

    fun scheduleRestart(context: Context, delayMs: Long) {
        val pi = PendingIntent.getBroadcast(
            context, REQ_REVIVE,
            Intent(context, BootReceiver::class.java).setAction(BootReceiver.ACTION_REVIVE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        fire(context, delayMs, pi)
    }

    /* --- layer 4b: an alarm per ping, independent of the handler thread --- */

    fun scheduleTick(context: Context, delayMs: Long) {
        val pi = PendingIntent.getForegroundService(
            context, REQ_TICK,
            Intent(context, PingService::class.java).setAction(PingService.ACTION_TICK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        fire(context, delayMs, pi)
    }

    private fun fire(context: Context, delayMs: Long, pi: PendingIntent) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = System.currentTimeMillis() + delayMs
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am.canScheduleExactAlarms())
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: Exception) {
            try { am.set(AlarmManager.RTC_WAKEUP, at, pi) } catch (_: Exception) {}
        }
    }

    /* --- layer 5: WorkManager -------------------------------------------- */

    fun scheduleWork(context: Context) {
        val work = PeriodicWorkRequestBuilder<KeepAliveWorker>(15, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        try {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, work
            )
        } catch (_: Exception) {}
    }

    /* --- layer 6: JobScheduler, persisted across reboots ------------------ */

    fun scheduleJob(context: Context) {
        try {
            val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (js.allPendingJobs.any { it.id == JOB_ID }) return

            val job = JobInfo.Builder(JOB_ID, ComponentName(context, PingJobService::class.java))
                .setPersisted(true)                       // survives reboot
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L)
                .build()
            js.schedule(job)
        } catch (_: Exception) {}
    }
}

class KeepAliveWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        PingService.start(applicationContext)
        Reviver.scheduleRestart(applicationContext, 15 * 60 * 1000L)
        return Result.success()
    }
}

class PingJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        PingService.start(applicationContext)
        Reviver.scheduleJob(applicationContext)
        return false   // work is done, nothing running in the background
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}
