package com.titgroups.advance

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TARGET_URL = "https://titadvance.dukans.in/"
        private const val TARGET_HOST = "titadvance.dukans.in"
    }

    private lateinit var rootContainer: View
    private lateinit var webView: WebView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var errorContainer: LinearLayout
    private lateinit var btnRetry: MaterialButton

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var hasError = false
    private var lastFailedUrl: String? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback
        if (callback != null) {
            val results = if (result.resultCode == RESULT_OK && result.data != null) {
                val dataString = result.data?.dataString
                val clipData = result.data?.clipData
                if (clipData != null) {
                    val uris = mutableListOf<Uri>()
                    for (i in 0 until clipData.itemCount) {
                        uris.add(clipData.getItemAt(i).uri)
                    }
                    uris.toTypedArray()
                } else if (dataString != null) {
                    arrayOf(Uri.parse(dataString))
                } else null
            } else null
            callback.onReceiveValue(results)
            filePathCallback = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        rootContainer = findViewById(R.id.rootContainer)
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        errorContainer = findViewById(R.id.errorContainer)
        btnRetry = findViewById(R.id.btnRetry)

        setupWindowInsets()
        setupWebView()
        setupSwipeRefresh()
        setupRetryButton()
        setupBackNavigation()

        if (savedInstanceState == null) {
            loadPage(TARGET_URL)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    private fun setupWindowInsets() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.isAppearanceLightStatusBars = true
        windowInsetsController.isAppearanceLightNavigationBars = true

        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val displayCutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())

            val topInset = maxOf(systemBars.top, displayCutout.top)
            val bottomInset = maxOf(systemBars.bottom, displayCutout.bottom)
            val leftInset = maxOf(systemBars.left, displayCutout.left)
            val rightInset = maxOf(systemBars.right, displayCutout.right)

            view.setPadding(leftInset, topInset, rightInset, bottomInset)
            insets
        }
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            supportZoom()
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                    swipeRefreshLayout.isRefreshing = false
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback = null
                    false
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return processUrlLoading(url)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == null) return false
                return processUrlLoading(url)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (url != null && !url.startsWith("data:")) {
                    hasError = false
                }
                hideErrorView()
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                swipeRefreshLayout.isRefreshing = false
                if (hasError) {
                    showErrorView()
                } else {
                    hideErrorView()
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    hasError = true
                    lastFailedUrl = request.url?.toString()
                    showErrorView()
                }
            }
        }
    }

    private fun processUrlLoading(url: String): Boolean {
        if (isInternalUrl(url)) {
            return false
        }
        return handleExternalUrl(url)
    }

    private fun isInternalUrl(url: String): Boolean {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        return host == TARGET_HOST || host.endsWith(".$TARGET_HOST") || host.endsWith(".dukans.in")
    }

    private fun handleExternalUrl(url: String): Boolean {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase() ?: return false

        when (scheme) {
            "intent" -> {
                try {
                    val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    if (intent != null) {
                        val packageManager = packageManager
                        val info = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                        if (info != null) {
                            startActivity(intent)
                            return true
                        }
                        val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                        if (!fallbackUrl.isNullOrEmpty()) {
                            if (isInternalUrl(fallbackUrl)) {
                                webView.loadUrl(fallbackUrl)
                            } else {
                                handleExternalUrl(fallbackUrl)
                            }
                            return true
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                return true
            }
            "tel", "mailto", "sms", "whatsapp", "market" -> {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, uri)
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "No app available to handle this request", Toast.LENGTH_SHORT).show()
                }
                return true
            }
            "http", "https" -> {
                if (!isInternalUrl(url)) {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, uri)
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(this, "Unable to open link", Toast.LENGTH_SHORT).show()
                    }
                    return true
                }
            }
            else -> {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, uri)
                    startActivity(intent)
                    return true
                } catch (e: Exception) {
                    return false
                }
            }
        }
        return false
    }

    private fun setupSwipeRefresh() {
        swipeRefreshLayout.setColorSchemeResources(R.color.primary_button_color)
        swipeRefreshLayout.setOnRefreshListener {
            val currentUrl = webView.url ?: lastFailedUrl ?: TARGET_URL
            loadPage(currentUrl)
        }
        swipeRefreshLayout.setOnChildScrollUpCallback { _, _ ->
            webView.scrollY > 0
        }
    }

    private fun setupRetryButton() {
        btnRetry.setOnClickListener {
            val retryUrl = lastFailedUrl ?: webView.url ?: TARGET_URL
            loadPage(retryUrl)
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (errorContainer.visibility == View.VISIBLE && webView.canGoBack()) {
                    hideErrorView()
                    webView.goBack()
                } else if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun loadPage(url: String) {
        lastFailedUrl = url
        if (!isNetworkAvailable()) {
            hasError = true
            showErrorView()
            swipeRefreshLayout.isRefreshing = false
            return
        }
        hasError = false
        hideErrorView()
        webView.loadUrl(url)
    }

    private fun showErrorView() {
        errorContainer.visibility = View.VISIBLE
        swipeRefreshLayout.visibility = View.GONE
        progressBar.visibility = View.GONE
    }

    private fun hideErrorView() {
        errorContainer.visibility = View.GONE
        swipeRefreshLayout.visibility = View.VISIBLE
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}