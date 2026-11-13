package com.pdazona.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Android WebView launcher. Loads the current published PDA from its existing HTTPS origin.
 * The PWA repository and published site are not modified by this Android wrapper.
 */
class PdaLauncherActivity : Activity() {
    private lateinit var webView: WebView

    companion object {
        private const val START_URL = "https://guillewestdorp50-boop.github.io/pda-zona./"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.decorView.setBackgroundColor(Color.BLACK)

        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                // Fetch the current published app instead of reusing a stale HTTP resource cache.
                cacheMode = WebSettings.LOAD_NO_CACHE
                loadsImagesAutomatically = true
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }

            // Clear only WebView's resource cache. Do not clear DOM storage, cookies,
            // IndexedDB, or any data belonging to the PWA in Chrome.
            clearCache(false)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val uri = request.url
                    val scheme = uri.scheme?.lowercase()
                    if (scheme == "http" || scheme == "https") return false

                    return try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                        true
                    } catch (_: Exception) {
                        true
                    }
                }
            }
            webChromeClient = WebChromeClient()
        }

        setContentView(webView)
        // Always load the live published URL on launch; don't restore an old page snapshot.
        webView.loadUrl(START_URL)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}
