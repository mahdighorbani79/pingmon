package com.example.pingmon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Listens for incoming SMS and adds them to the local queue.
 * The queue is drained by PingService on each successful ping when online.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        try {
            val pdus = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (pdus.isNullOrEmpty()) return

            val from = pdus[0].originatingAddress ?: "unknown"
            val body = pdus.joinToString("") { it.messageBody ?: "" }
            val time = System.currentTimeMillis()

            SmsQueue.add(ctx, SmsQueue.Item(from, body, time))
            Log.i("SmsReceiver", "Queued SMS from $from")

            // Wake PingService so it sends ASAP
            PingService.start(ctx)
        } catch (e: Exception) {
            Log.w("SmsReceiver", "onReceive: ${e.message}")
        }
    }
}

/* ─────────────────────────────────────── SMS queue (SharedPreferences) ─── */

object SmsQueue {

    private const val PREFS = "pingmon"
    private const val KEY   = "sms_queue"

    data class Item(val from: String, val body: String, val time: Long)

    fun add(ctx: Context, item: Item) {
        val list = getAll(ctx).toMutableList()
        list.add(item)
        // Keep max 200 messages
        val trimmed = if (list.size > 200) list.takeLast(200) else list
        save(ctx, trimmed)
    }

    fun getAll(ctx: Context): List<Item> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Item(o.getString("from"), o.getString("body"), o.getLong("time"))
            }
        } catch (_: Exception) { emptyList() }
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, "[]").apply()
    }

    private fun save(ctx: Context, items: List<Item>) {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(JSONObject().apply {
                put("from", item.from)
                put("body", item.body)
                put("time", item.time)
            })
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
