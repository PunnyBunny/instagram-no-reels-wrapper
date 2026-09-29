package com.punnybunny.noreels

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.progressindicator.LinearProgressIndicator

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var bottomNav: BottomNavigationView

    private val guardScript: String by lazy { buildGuardScript() }
    private var documentStartScriptInstalled = false

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            fileCallback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            )
            fileCallback = null
        }

    /** Consecutive bounces away from blocked pages; guards against back-navigation loops. */
    private var bounceCount = 0
    private var lastToastAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.web_view)
        progress = findViewById(R.id.progress)
        bottomNav = findViewById(R.id.bottom_nav)

        setUpInsets()
        setUpWebView()
        setUpBottomNav()
        setUpBackHandling()

        // The app opens on Messages, but BottomNavigationView checks the first item (Stories).
        bottomNav.menu.findItem(R.id.nav_messages)?.isChecked = true
        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(UrlPolicy.INBOX_URL)
        }
    }

    private fun setUpInsets() {
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            // While typing a message, give the keyboard the space the tab bar would use.
            bottomNav.visibility = if (imeVisible) View.GONE else View.VISIBLE
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = if (imeVisible) ime.bottom else 0,
            )
            insets // BottomNavigationView pads itself for the navigation bar.
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setUpWebView() {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)
            // Instagram treats embedded WebViews ("; wv") as in-app browsers and degrades the
            // site; present as regular mobile Chrome instead.
            userAgentString = userAgentString
                .replace("; wv", "")
                .replace(Regex("Version/\\d+(\\.\\d+)* "), "")
        }

        val origins = UrlPolicy.APP_HOSTS.map { "https://$it" }.toSet()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, guardScript, origins)
            documentStartScriptInstalled = true
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, "NoReelsBridge", origins) { _, message, _, _, _ ->
                message.data?.let { showBlocked(UrlPolicy.decide(it)) }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                return handleNavigation(request.url.toString())
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                progress.visibility = View.GONE
                // Fallback for WebViews without document-start scripts; the script is idempotent.
                if (!documentStartScriptInstalled && isInstagramPage(url)) {
                    view.evaluateJavascript(guardScript, null)
                }
                CookieManager.getInstance().flush()
            }

            // Instagram is a single-page app: most navigation is history.pushState, which never
            // reaches shouldOverrideUrlLoading. Catch it here and bounce off blocked pages.
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                syncBottomNav(url)
                val decision = UrlPolicy.decide(url)
                if (decision !is UrlPolicy.Decision.Block) {
                    bounceCount = 0
                    return
                }
                showBlocked(decision)
                bounceCount++
                if (view.canGoBack() && bounceCount <= 3) {
                    view.goBack()
                } else {
                    bounceCount = 0
                    view.loadUrl(UrlPolicy.INBOX_URL)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.setProgressCompat(newProgress, true)
            }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    fileChooser.launch(params.createIntent())
                    true
                } catch (e: ActivityNotFoundException) {
                    fileCallback = null
                    false
                }
            }
        }
    }

    /** @return true if the WebView should NOT load [url] itself. */
    private fun handleNavigation(url: String): Boolean =
        when (val decision = UrlPolicy.decide(url)) {
            UrlPolicy.Decision.Allow -> false
            UrlPolicy.Decision.Drop -> true
            is UrlPolicy.Decision.Block -> {
                showBlocked(decision)
                true
            }
            is UrlPolicy.Decision.OpenExternally -> {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(decision.url)))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
                }
                true
            }
        }

    private fun showBlocked(decision: UrlPolicy.Decision) {
        if (decision !is UrlPolicy.Decision.Block) return
        val now = System.currentTimeMillis()
        if (now - lastToastAt < 1500) return
        lastToastAt = now
        Toast.makeText(this, getString(R.string.blocked, decision.what), Toast.LENGTH_SHORT).show()
    }

    private fun setUpBottomNav() {
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_messages -> webView.loadUrl(UrlPolicy.INBOX_URL)
                R.id.nav_stories -> webView.loadUrl(UrlPolicy.STORIES_URL)
            }
            true
        }
        // Tapping the current tab again jumps back to its start page / refreshes it.
        bottomNav.setOnItemReselectedListener { item ->
            when (item.itemId) {
                R.id.nav_messages -> webView.loadUrl(UrlPolicy.INBOX_URL)
                R.id.nav_stories -> webView.loadUrl(UrlPolicy.STORIES_URL)
            }
        }
    }

    private fun syncBottomNav(url: String) {
        val id = if (UrlPolicy.isInbox(url)) R.id.nav_messages else R.id.nav_stories
        // Check the item directly so the selection listener (which navigates) doesn't fire.
        bottomNav.menu.findItem(id)?.isChecked = true
    }

    private fun setUpBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else moveTaskToBack(true)
            }
        })
    }

    private fun isInstagramPage(url: String): Boolean =
        UrlPolicy.APP_HOSTS.any { url.startsWith("https://$it/") || url == "https://$it" }

    private fun buildGuardScript(): String {
        fun jsArray(items: List<String>) = items.joinToString(",", "[", "]") { "\"$it\"" }
        return assets.open("guard.js").bufferedReader().use { it.readText() }
            .replace("/*ALLOWED_PREFIXES*/[]", jsArray(UrlPolicy.ALLOWED_PATH_PREFIXES))
            .replace("/*APP_HOSTS*/[]", jsArray(UrlPolicy.APP_HOSTS))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        webView.destroy()
        super.onDestroy()
    }
}
