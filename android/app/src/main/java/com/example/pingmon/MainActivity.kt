package com.example.pingmon

import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {

    private var web: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Force mode: no WebView until every requirement is satisfied.
        if (!SetupActivity.isComplete(this)) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            loadUrl("https://www.google.com")
        }
        setContentView(web)

        PingService.start(this)
    }

    override fun onResume() {
        super.onResume()
        // The user may have revoked a permission from settings while away.
        if (web != null && !SetupActivity.isComplete(this)) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
        }
    }

    override fun onBackPressed() {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else moveTaskToBack(true)
    }
}
