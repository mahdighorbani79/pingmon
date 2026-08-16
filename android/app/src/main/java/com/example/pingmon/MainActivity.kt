package com.example.pingmon

import android.content.*
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_GOTO = "com.example.pingmon.GOTO"
    }

    private var web: WebView? = null

    /** Lets the service push a new domain into a live WebView. */
    private val gotoReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val url = i?.getStringExtra("url") ?: return
            runOnUiThread { web?.loadUrl(url) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!SetupActivity.isComplete(this)) {
            startActivity(Intent(this, SetupActivity::class.java)); finish(); return
        }

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            loadUrl(PingService.currentDomain(this@MainActivity))
        }
        setContentView(web)
        PingService.start(this)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(ACTION_GOTO)
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(gotoReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else
            @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(gotoReceiver, filter)

        if (web != null && !SetupActivity.isComplete(this)) {
            startActivity(Intent(this, SetupActivity::class.java)); finish()
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(gotoReceiver) } catch (_: Exception) {}
    }

    override fun onBackPressed() {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else moveTaskToBack(true)
    }
}
