package com.titgroups.advance

import android.Manifest
import android.app.Dialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
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
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TARGET_URL = "https://titadvance.dukans.in/"
        private const val TARGET_HOST = "titadvance.dukans.in"
        private const val PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=com.titgroups.advance"
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
    private var pendingDownloadUrl: String? = null
    private var pendingUserAgent: String? = null
    private var pendingContentDisposition: String? = null
    private var pendingMimeType: String? = null

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

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val writeGranted = permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] ?: true
        if (writeGranted) {
            pendingDownloadUrl?.let { url ->
                startDownload(url, pendingUserAgent, pendingContentDisposition, pendingMimeType)
            }
        } else {
            Toast.makeText(this, "Storage permission required to download files", Toast.LENGTH_SHORT).show()
        }
        pendingDownloadUrl = null
        pendingUserAgent = null
        pendingContentDisposition = null
        pendingMimeType = null
    }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (downloadId != -1L) {
                    queryAndOpenDownloadedFile(downloadId)
                }
            }
        }
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun shareText(text: String?) {
            runOnUiThread {
                val validText = if (text.isNullOrBlank()) PLAY_STORE_URL else text
                this@MainActivity.shareText(validText)
            }
        }

        @JavascriptInterface
        fun shareApp() {
            runOnUiThread {
                this@MainActivity.shareApp()
            }
        }

        @JavascriptInterface
        fun share(title: String?, text: String?, url: String?) {
            runOnUiThread {
                val sb = StringBuilder()
                if (!title.isNullOrBlank()) sb.append(title).append("\n")
                if (!text.isNullOrBlank()) sb.append(text).append("\n")
                if (!url.isNullOrBlank()) sb.append(url)
                val fullText = sb.toString().trim()
                val finalText = if (fullText.isEmpty()) PLAY_STORE_URL else fullText
                this@MainActivity.shareText(finalText)
            }
        }

        @JavascriptInterface
        fun processBase64Data(base64Data: String, fileName: String, mimeType: String) {
            runOnUiThread {
                this@MainActivity.saveAndOpenBase64File(base64Data, fileName, mimeType)
            }
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
        registerDownloadReceiver()
        requestNotificationPermissionIfNeeded()

        if (savedInstanceState == null) {
            loadPage(TARGET_URL)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    private fun registerDownloadReceiver() {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        ContextCompat.registerReceiver(
            this,
            downloadReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
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

        val webAppInterface = WebAppInterface()
        webView.addJavascriptInterface(webAppInterface, "AndroidApp")
        webView.addJavascriptInterface(webAppInterface, "Android")
        webView.addJavascriptInterface(webAppInterface, "AndroidShare")

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            checkAndDownloadFile(url, userAgent, contentDisposition, mimetype)
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                injectWebSharePolyfill()
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
                injectWebSharePolyfill()
                if (url != null && !url.startsWith("data:")) {
                    hasError = false
                }
                hideErrorView()
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectWebSharePolyfill()
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

    private fun injectWebSharePolyfill() {
        val js = """
            (function() {
                function doShare(data) {
                    return new Promise(function(resolve, reject) {
                        try {
                            var title = (data && data.title) ? data.title : "";
                            var text = (data && data.text) ? data.text : "";
                            var url = (data && data.url) ? data.url : "";
                            
                            var shareString = "";
                            if (title) shareString += title;
                            if (text) {
                                if (shareString) shareString += "\n";
                                shareString += text;
                            }
                            if (url) {
                                if (shareString) shareString += "\n";
                                shareString += url;
                            }
                            if (!shareString) {
                                shareString = "$PLAY_STORE_URL";
                            }
                            
                            if (window.AndroidApp && window.AndroidApp.shareText) {
                                window.AndroidApp.shareText(shareString);
                                resolve();
                            } else if (window.Android && window.Android.shareText) {
                                window.Android.shareText(shareString);
                                resolve();
                            } else if (window.AndroidShare && window.AndroidShare.shareText) {
                                window.AndroidShare.shareText(shareString);
                                resolve();
                            } else {
                                resolve();
                            }
                        } catch (e) {
                            resolve();
                        }
                    });
                }

                navigator.share = doShare;
                navigator.canShare = function(data) { return true; };
                if (window.navigator) {
                    window.navigator.share = doShare;
                    window.navigator.canShare = function(data) { return true; };
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun processUrlLoading(url: String): Boolean {
        val lowerUrl = url.lowercase()
        if (lowerUrl.contains("share-app") || lowerUrl.contains("action=share") || lowerUrl.contains("type=share")) {
            shareApp()
            return true
        }
        if (isDownloadableUrl(url)) {
            checkAndDownloadFile(url, webView.settings.userAgentString, null, getMimeType(url))
            return true
        }
        if (isInternalUrl(url)) {
            return false
        }
        return handleExternalUrl(url)
    }

    private fun isDownloadableUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.endsWith(".pdf") || lower.contains(".pdf?") ||
                lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".docx") || lower.endsWith(".xlsx") || lower.endsWith(".zip")
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
                if (url.contains("whatsapp.com") || url.contains("wa.me")) {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, uri)
                        startActivity(intent)
                        return true
                    } catch (e: Exception) {
                        // Fallback below
                    }
                }
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

    private fun checkAndDownloadFile(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                pendingDownloadUrl = url
                pendingUserAgent = userAgent
                pendingContentDisposition = contentDisposition
                pendingMimeType = mimeType
                permissionLauncher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
                return
            }
        }
        startDownload(url, userAgent, contentDisposition, mimeType)
    }

    private fun startDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        val effectiveMimeType = if (mimeType.isNullOrEmpty() || mimeType == "application/octet-stream") {
            getMimeType(url)
        } else {
            mimeType
        }
        val guessedFileName = URLUtil.guessFileName(url, contentDisposition, effectiveMimeType)

        if (url.startsWith("data:")) {
            saveAndOpenBase64File(url, guessedFileName, effectiveMimeType)
            return
        }

        if (url.startsWith("blob:")) {
            downloadBlobUrl(url, guessedFileName, effectiveMimeType)
            return
        }

        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(effectiveMimeType)
                val cookie = CookieManager.getInstance().getCookie(url)
                if (!cookie.isNullOrEmpty()) {
                    addRequestHeader("Cookie", cookie)
                }
                if (!userAgent.isNullOrEmpty()) {
                    addRequestHeader("User-Agent", userAgent)
                }
                setDescription("Downloading file...")
                setTitle(guessedFileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, guessedFileName)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }

            val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)
            Toast.makeText(this, "Downloading $guessedFileName...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun downloadBlobUrl(blobUrl: String, fileName: String, mimeType: String) {
        val js = """
            (function() {
                var xhr = new XMLHttpRequest();
                xhr.open('GET', '$blobUrl', true);
                xhr.responseType = 'blob';
                xhr.onload = function() {
                    var reader = new FileReader();
                    reader.readAsDataURL(xhr.response);
                    reader.onloadend = function() {
                        AndroidApp.processBase64Data(reader.result, '$fileName', '$mimeType');
                    };
                };
                xhr.onerror = function() {
                    console.error('Blob download failed');
                };
                xhr.send();
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun saveAndOpenBase64File(base64Data: String, fileName: String, mimeType: String) {
        try {
            val cleanBase64 = if (base64Data.contains(",")) {
                base64Data.substring(base64Data.indexOf(",") + 1)
            } else {
                base64Data
            }
            val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)

            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) downloadsDir.mkdirs()

            val targetFile = File(downloadsDir, fileName)
            FileOutputStream(targetFile).use { it.write(bytes) }

            Toast.makeText(this, "File saved: $fileName", Toast.LENGTH_SHORT).show()

            val contentUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", targetFile)
            val finalMimeType = mimeType.ifEmpty { getMimeType(fileName) }
            openDownloadedFile(contentUri, finalMimeType)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to save file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun queryAndOpenDownloadedFile(downloadId: Long) {
        try {
            val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterById(downloadId)
            val cursor = downloadManager.query(query)
            if (cursor != null && cursor.moveToFirst()) {
                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                if (statusIndex != -1 && cursor.getInt(statusIndex) == DownloadManager.STATUS_SUCCESSFUL) {
                    val uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                    val mimeTypeIndex = cursor.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE)
                    val titleIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)

                    val uriString = if (uriIndex != -1) cursor.getString(uriIndex) else null
                    val mimeType = if (mimeTypeIndex != -1) cursor.getString(mimeTypeIndex) else null
                    val fileName = if (titleIndex != -1) cursor.getString(titleIndex) else "downloaded_file"

                    Toast.makeText(this, "Download complete: $fileName", Toast.LENGTH_LONG).show()

                    if (!uriString.isNullOrEmpty()) {
                        val fileUri = Uri.parse(uriString)
                        val contentUri = if (fileUri.scheme == "file") {
                            val file = File(fileUri.path ?: "")
                            if (file.exists()) {
                                FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                            } else fileUri
                        } else fileUri

                        val finalMimeType = mimeType ?: getMimeType(fileName)
                        openDownloadedFile(contentUri, finalMimeType)
                    }
                }
                cursor.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun openDownloadedFile(uri: Uri, mimeType: String) {
        try {
            val openIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(Intent.createChooser(openIntent, "Open file with"))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app found to open this file", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to open file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareText(text: String, title: String = "Share via") {
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(shareIntent, title))
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to share content", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareApp() {
        val shareMessage = "Check out TIT Advance app: $PLAY_STORE_URL"
        shareText(shareMessage, "Share TIT Advance")
    }

    private fun getMimeType(url: String): String {
        val extension = MimeTypeMap.getFileExtensionFromUrl(url)
        if (!extension.isNullOrEmpty()) {
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
            if (!mime.isNullOrEmpty()) return mime
        }
        val lowerUrl = url.lowercase()
        return when {
            lowerUrl.contains(".pdf") -> "application/pdf"
            lowerUrl.contains(".png") -> "image/png"
            lowerUrl.contains(".jpg") || lowerUrl.contains(".jpeg") -> "image/jpeg"
            lowerUrl.contains(".zip") -> "application/zip"
            lowerUrl.contains(".docx") -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            lowerUrl.contains(".xlsx") -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            else -> "*/*"
        }
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
                    showExitConfirmationDialog()
                }
            }
        })
    }

    private fun showExitConfirmationDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_exit_confirmation)

        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setDimAmount(0.36f)
            val displayMetrics = resources.displayMetrics
            val width = (displayMetrics.widthPixels * 0.86).toInt()
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        dialog.findViewById<View>(R.id.btnStay)?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.findViewById<View>(R.id.btnExit)?.setOnClickListener {
            dialog.dismiss()
            finish()
        }

        dialog.show()
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
        try {
            unregisterReceiver(downloadReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
        webView.destroy()
        super.onDestroy()
    }
}