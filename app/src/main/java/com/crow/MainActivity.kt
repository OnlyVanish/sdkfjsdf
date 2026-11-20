package com.crow

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.provider.Settings
import android.graphics.Color
import android.util.Base64
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.Gravity
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.ConsoleMessage
import android.webkit.WebResourceError
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ProgressBar
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import androidx.webkit.WebViewAssetLoader
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.BufferedInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * A small native Android host for the original, self-contained Crow HTML game.
 * The game is bundled locally and served through WebViewAssetLoader
 * (https://appassets.androidplatform.net/assets/www/index.html). Internet is used only for updates.
 */
class MainActivity : Activity() {

    private var gameView: WebView? = null
    private lateinit var root: FrameLayout
    private var pendingExport: String? = null
    private val contentDirectory by lazy { File(filesDir, "crow-content") }
    private var startupOverlay: View? = null
    private var startupMessage: TextView? = null
    private var startupProgress: ProgressBar? = null
    private var pendingInstallFile: File? = null
    private var pendingForceUpdate: Boolean = false
    private var permissionSettingsOpened: Boolean = false
    private var installerLaunched: Boolean = false
    private var pendingInstallVersionCode: Int = 0
    private var startupFinished = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var gameLoadTimeout: Runnable? = null
    private var webErrorDialog: AlertDialog? = null

    private val assetLoader by lazy {
        if (!contentDirectory.exists()) contentDirectory.mkdirs()
        WebViewAssetLoader.Builder()
            .setDomain(ASSET_HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/content/", WebViewAssetLoader.InternalStoragePathHandler(this, contentDirectory))
            .build()
    }

    // ---------------------------------------------------------------- audio behaviour
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var focusRequest: AudioFocusRequest? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> runJs("window.androidPause && window.androidPause();")
            AudioManager.AUDIOFOCUS_GAIN -> runJs("window.androidResume && window.androidResume();")
            else -> Unit
        }
    }

    /** Headphones unplugged / Bluetooth disconnected: pause instead of blasting the speaker. */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            runJs("window.androidPause && window.androidPause();")
        }
    }
    private var noisyRegistered = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Let the game draw into the camera cutout area in landscape.
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        applyImmersiveMode()

        showStartupScreen("در حال بررسی نسخه‌ی بازی…")

        // Predictive-back era (API 33+): the legacy onBackPressed() is not called when the
        // app opts in, so register a callback instead. Older versions use onBackPressed().
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { handleBack() }
        }

        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        startStartupFlow()
    }

    private fun startStartupFlow() {
        Thread {
            try {
                val appRelease = requestJson("/api/v1/app", 1024 * 1024)
                val remoteCode = appRelease.optInt("version_code", 0)
                if (remoteCode > BuildConfig.VERSION_CODE) {
                    val expectedHash = appRelease.optString("sha256", "").lowercase(Locale.ROOT)
                    val downloadPath = appRelease.optString("download_url", "")
                    if (!expectedHash.matches(Regex("[0-9a-f]{64}")) || downloadPath != "/api/v1/app/download") {
                        throw IOException("اطلاعات نسخه‌ی جدید معتبر نیست")
                    }
                    val expectedSize = appRelease.optLong("size_bytes", 0L)
                    val updateFile = File(cacheDir, "updates/crow-update.apk")
                    updateFile.parentFile?.mkdirs()
                    updateStartup("نسخه‌ی ${appRelease.optString("version_name", remoteCode.toString())} پیدا شد؛ در حال دریافت…", 0)
                    downloadToFile(downloadPath, updateFile, MAX_APK_BYTES, expectedSize) { done, total ->
                        val percent = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1
                        updateStartup("در حال دریافت به‌روزرسانی بازی… ${if (percent >= 0) "$percent٪" else ""}", percent)
                    }
                    val actualHash = sha256(updateFile)
                    if (!MessageDigest.isEqual(actualHash.toByteArray(), expectedHash.toByteArray())) {
                        updateFile.delete()
                        throw IOException("صحت فایل APK تأیید نشد؛ فایل حذف شد")
                    }
                    if (!verifyDownloadedApk(updateFile, remoteCode)) {
                        updateFile.delete()
                        throw IOException("این APK برای همین نسخه‌ی Crow یا همین کلید امضا نیست")
                    }
                    val force = appRelease.optBoolean("force_update", false)
                    runOnUiThread { offerInstall(updateFile, force, appRelease.optString("release_notes", ""), remoteCode) }
                    return@Thread
                }
            } catch (e: Exception) {
                android.util.Log.w("CrowUpdater", "App update check skipped: ${e.message}")
            }

            try {
                syncRemoteSeasons()
            } catch (e: Exception) {
                android.util.Log.w("CrowUpdater", "Season sync skipped: ${e.message}")
            }
            runOnUiThread { continueToGame() }
        }.start()
    }

    private fun showStartupScreen(message: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(26), dp(32), dp(26))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(15, 17, 25))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.rgb(85, 68, 48))
            }
        }
        val title = TextView(this).apply {
            text = "C R O W"
            textSize = 27f
            setTextColor(Color.rgb(225, 187, 119))
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = message
            textSize = 14f
            setTextColor(Color.rgb(232, 232, 238))
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(18))
        }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        card.addView(title, LinearLayout.LayoutParams(-1, -2))
        card.addView(subtitle, LinearLayout.LayoutParams(-1, -2))
        card.addView(progress, LinearLayout.LayoutParams(-1, dp(7)))
        val wrap = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(4, 5, 9))
            addView(card, FrameLayout.LayoutParams(dp(310), -2, Gravity.CENTER))
        }
        startupOverlay = wrap
        startupMessage = subtitle
        startupProgress = progress
        root.addView(wrap, FrameLayout.LayoutParams(-1, -1))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private fun updateStartup(message: String, percent: Int? = null) {
        runOnUiThread {
            startupMessage?.text = message
            startupProgress?.let { bar ->
                if (percent == null || percent < 0) {
                    bar.isIndeterminate = true
                } else {
                    bar.isIndeterminate = false
                    bar.progress = percent.coerceIn(0, 100)
                }
            }
        }
    }

    private fun continueToGame() {
        if (startupFinished || isFinishing) return
        startupFinished = true
        startupOverlay?.let { root.removeView(it) }
        startupOverlay = null
        startupMessage = null
        startupProgress = null
        val view = createGameView()
        gameView = view
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        loadGamePage(view)
    }

    /** Loads the local game and provides a visible failure path instead of leaving a black WebView. */
    private fun loadGamePage(view: WebView) {
        gameLoadTimeout?.let(mainHandler::removeCallbacks)
        val timeout = Runnable {
            if (gameView === view && !isFinishing && !isDestroyed) {
                showWebViewLoadError("صفحه‌ی بازی در ۱۵ ثانیه بارگذاری نشد. آدرس: $GAME_URL")
            }
        }
        gameLoadTimeout = timeout
        mainHandler.postDelayed(timeout, 15_000L)
        android.util.Log.i("CrowWebView", "Loading local game: $GAME_URL")
        view.loadUrl(GAME_URL)
    }

    private fun showWebViewLoadError(message: String) {
        android.util.Log.e("CrowWebView", message)
        if (isFinishing || isDestroyed || (webErrorDialog?.isShowing == true)) return
        runOnUiThread {
            if (isFinishing || isDestroyed || (webErrorDialog?.isShowing == true)) return@runOnUiThread
            webErrorDialog = AlertDialog.Builder(this)
                .setTitle("بارگذاری بازی ناموفق بود")
                .setMessage("صفحه‌ی بازی باز نشد. جزئیات خطا:\n\n$message\n\nبرای بررسی بیشتر، خروجی Logcat با برچسب CrowWebView و chromium را ارسال کن.")
                .setPositiveButton("تلاش دوباره") { _, _ ->
                    webErrorDialog = null
                    gameView?.let(::loadGamePage)
                }
                .setNegativeButton("خروج") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        }
    }

    private fun offerInstall(file: File, force: Boolean, notes: String, versionCode: Int) {
        pendingInstallFile = file
        pendingForceUpdate = force
        pendingInstallVersionCode = versionCode
        installerLaunched = false
        val message = buildString {
            append("نسخه‌ی جدید بازی آماده‌ی نصب است.\n")
            if (notes.isNotBlank()) append("\n").append(notes.take(1200))
            append("\n\nدانلود و بررسی فایل کامل شد. برای نصب، تأیید نهایی اندروید لازم است.")
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("به‌روزرسانی Crow")
            .setMessage(message)
            .setPositiveButton("نصب نسخه‌ی جدید") { _, _ -> requestInstallPermissionAndLaunch() }
        if (force) {
            dialog.setNegativeButton("خروج از بازی") { _, _ -> finish() }
            dialog.setCancelable(false)
        } else {
            dialog.setNegativeButton("بعداً") { _, _ -> pendingInstallFile = null; continueToGame() }
            dialog.setCancelable(true)
            dialog.setOnCancelListener { pendingInstallFile = null; continueToGame() }
        }
        dialog.show()
    }

    private fun requestInstallPermissionAndLaunch() {
        val file = pendingInstallFile ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle("اجازه‌ی نصب به‌روزرسانی")
                .setMessage("برای نصب APK دانلودشده، باید اجازه‌ی نصب از این برنامه را در تنظیمات اندروید فعال کنی. این اجازه فقط برای باز کردن نصب‌کننده‌ی رسمی سیستم است.")
                .setPositiveButton("رفتن به تنظیمات") { _, _ ->
                    try {
                        permissionSettingsOpened = true
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    } catch (_: Exception) {
                        permissionSettingsOpened = false
                        toast("تنظیمات نصب برنامه در دسترس نیست")
                    }
                }
                .setNegativeButton("لغو") { _, _ ->
                    pendingInstallFile = null
                    if (pendingForceUpdate) finish() else continueToGame()
                }
                .show()
            return
        }
        launchSystemInstaller(file)
    }

    private fun launchSystemInstaller(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            intent.putExtra(Intent.EXTRA_RETURN_RESULT, true)
            installerLaunched = true
            startActivityForResult(intent, REQ_INSTALL)
        } catch (e: Exception) {
            installerLaunched = false
            toast("باز کردن نصب‌کننده‌ی اندروید ناموفق بود")
            android.util.Log.e("CrowUpdater", "Could not launch installer", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyDownloadedApk(file: File, expectedVersionCode: Int): Boolean {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: return false
        if (archive.packageName != packageName) return false
        val archiveVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.longVersionCode else archive.versionCode.toLong()
        if (archiveVersion != expectedVersionCode.toLong()) return false
        val installed = packageManager.getPackageInfo(packageName, flags)
        fun signers(info: PackageInfo): List<String> {
            val sigs: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners
            } else info.signatures
            return sigs?.map { Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP) }?.sorted() ?: emptyList()
        }
        val currentSigners = signers(installed)
        return currentSigners.isNotEmpty() && currentSigners == signers(archive)
    }

    private fun requestJson(path: String, maxBytes: Int): JSONObject {
        val bytes = requestBytes(path, maxBytes) { _, _ -> }
        return JSONObject(String(bytes, Charsets.UTF_8))
    }

    private fun requestBytes(path: String, maxBytes: Int, progress: (Long, Long) -> Unit): ByteArray {
        val connection = openSecureConnection(path)
        try {
            connection.connectTimeout = 4000
            connection.readTimeout = 6000
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw IOException("سرور به‌روزرسانی پاسخ نامعتبر داد: $status")
            val declared = connection.contentLength.toLong()
            if (declared > maxBytes) throw IOException("پاسخ سرور بیش از حد بزرگ است")
            val output = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) throw IOException("پاسخ سرور بیش از حد بزرگ است")
                    output.write(buffer, 0, count)
                    progress(total, if (declared > 0) declared else -1)
                }
            }
            return output.toByteArray()
        } finally { connection.disconnect() }
    }

    private fun downloadToFile(path: String, destination: File, maxBytes: Long, expectedSize: Long, progress: (Long, Long) -> Unit) {
        val connection = openSecureConnection(path)
        try {
            connection.connectTimeout = 15000
            connection.readTimeout = 45000
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw IOException("دریافت فایل از سرور ناموفق بود: $status")
            val declared = connection.contentLength.toLong()
            if (declared > maxBytes || (expectedSize > 0 && declared > 0 && declared != expectedSize)) throw IOException("حجم APK با اطلاعات انتشار یکسان نیست")
            var total = 0L
            FileOutputStream(destination).use { output ->
                BufferedInputStream(connection.inputStream).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > maxBytes) throw IOException("فایل دریافتی بیش از حد بزرگ است")
                        output.write(buffer, 0, count)
                        progress(total, if (declared > 0) declared else expectedSize)
                    }
                    output.fd.sync()
                }
            }
            if (total <= 0 || (expectedSize > 0 && total != expectedSize)) throw IOException("دانلود ناقص است")
        } catch (e: Exception) {
            destination.delete()
            throw e
        } finally { connection.disconnect() }
    }

    private fun openSecureConnection(path: String): HttpURLConnection {
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("..")) throw IOException("مسیر دانلود نامعتبر است")
        val url = URL(UPDATE_BASE_URL.trimEnd('/') + path)
        if (url.protocol != "https" || url.host != UPDATE_HOST) throw IOException("سرور به‌روزرسانی ناامن است")
        return (url.openConnection() as? HttpURLConnection) ?: throw IOException("اتصال سرور برقرار نشد")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun syncRemoteSeasons() {
        updateStartup("در حال بررسی فصل تازه…", null)
        val manifest = requestJson("/api/v1/seasons", 2 * 1024 * 1024)
        val remote = manifest.optJSONArray("seasons") ?: throw IOException("فهرست فصل‌های سرور معتبر نیست")
        if (remote.length() == 0) {
            clearInstalledContentPacks()
            return
        }
        if (remote.length() != 1) throw IOException("سرور باید دقیقاً یک فصل فعال داشته باشد")
        val item = remote.optJSONObject(0) ?: throw IOException("اطلاعات فصل سرور خراب است")
        val id = item.optString("id", "")
        if (!PACK_ID_REGEX.matches(id)) throw IOException("شناسه‌ی فصل سرور نامعتبر است")
        val path = item.optString("download_url", "")
        val hash = item.optString("sha256", "").lowercase(Locale.ROOT)
        if (!hash.matches(Regex("[0-9a-f]{64}")) || path != "/api/v1/seasons/$id/download") throw IOException("نشانی یا هش فصل سرور معتبر نیست")
        val installed = readInstalledContentPacks()
        if (installed.size == 1 && installed[0].id == id && installed[0].sha256 == hash) return
        val title = item.optString("title", id)
        val file = File(cacheDir, "season-downloads/$id.zip")
        file.parentFile?.mkdirs()
        updateStartup("در حال دریافت فصل: $title", 0)
        try {
            downloadToFile(path, file, MAX_SEASON_BYTES, item.optLong("size_bytes", 0L)) { done, total ->
                val percent = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1
                updateStartup("دریافت فصل $title… ${if (percent >= 0) "$percent٪" else ""}", percent)
            }
            if (sha256(file) != hash) throw IOException("هش بسته‌ی فصل صحیح نیست")
            // Validates first, then replaces the previous season (old levels are removed).
            importContentPack(Uri.fromFile(file), hash)
        } catch (e: Exception) {
            android.util.Log.w("CrowUpdater", "Season $id could not be installed: ${e.message}")
        } finally {
            file.delete()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createGameView(): WebView = WebView(this).apply {
        setBackgroundColor(Color.BLACK)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isFocusable = true
        isFocusableInTouchMode = true

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true // The game stores progress in localStorage.
            allowFileAccess = false // Assets are served by WebViewAssetLoader, not file://
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        addJavascriptInterface(NativeBridge(), "CrowNative")
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                val line = "${message.message()} (${message.sourceId()}:${message.lineNumber()})"
                when (message.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR -> android.util.Log.e("CrowWebView", line)
                    ConsoleMessage.MessageLevel.WARNING -> android.util.Log.w("CrowWebView", line)
                    else -> android.util.Log.d("CrowWebView", line)
                }
                return true
            }
        }
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                android.util.Log.i("CrowWebView", "onPageStarted: $url")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                gameLoadTimeout?.let(mainHandler::removeCallbacks)
                gameLoadTimeout = null
                android.util.Log.i("CrowWebView", "onPageFinished: $url")
                view?.evaluateJavascript(
                    "(function(){var c=document.getElementById('c');return JSON.stringify({ready:document.readyState,canvas:!!c,width:c?c.width:0,height:c?c.height:0,native:!!window.CrowNative});})()"
                ) { state -> android.util.Log.i("CrowWebView", "Page state: $state") }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    showWebViewLoadError("${error?.description ?: "خطای ناشناخته در بارگذاری"} (کد ${error?.errorCode ?: -1})")
                } else {
                    android.util.Log.w("CrowWebView", "Subresource load failed: ${request?.url} — ${error?.description}")
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                response: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, response)
                android.util.Log.e("CrowWebView", "HTTP ${response?.statusCode} for ${request?.url}")
                if (request?.isForMainFrame == true) {
                    showWebViewLoadError("پاسخ HTTP ${response?.statusCode} هنگام باز کردن صفحه‌ی بازی")
                }
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val uri = request?.url ?: return null
                return assetLoader.shouldInterceptRequest(uri)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme?.lowercase()
                if ((scheme == "https" || scheme == "http") && uri.host == ASSET_HOST) {
                    return false // the game itself
                }
                if (scheme == "http" || scheme == "https") {
                    // External links leave the game and open in the user's browser.
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (_: Exception) {
                        // No browser available: keep the app stable.
                    }
                }
                return true
            }

            /** The WebView renderer was killed (low memory / crash): rebuild instead of dying. */
            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                android.util.Log.e("CrowWebView", "Renderer gone. didCrash=${detail?.didCrash()}")
                if (view == null || view !== gameView) return true
                gameLoadTimeout?.let(mainHandler::removeCallbacks)
                gameLoadTimeout = null
                root.removeView(view)
                view.destroy()
                val fresh = createGameView()
                gameView = fresh
                root.addView(
                    fresh,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                loadGamePage(fresh)
                return true
            }
        }
    }

    // ---------------------------------------------------------------- JS bridge
    /** Methods callable from the game as window.CrowNative.*  (only our own bundled page is loaded). */
    private inner class NativeBridge {
        @Suppress("DEPRECATION")
        @JavascriptInterface
        fun vibrate(ms: Int) {
            runOnUiThread {
                try {
                    val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                            ?.defaultVibrator
                    } else {
                        getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    }
                    if (vibrator == null || !vibrator.hasVibrator()) return@runOnUiThread
                    val duration = ms.coerceIn(1, 300).toLong()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(
                            VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)
                        )
                    } else {
                        vibrator.vibrate(duration)
                    }
                } catch (_: Exception) {
                    // Haptics are optional.
                }
            }
        }

        /** Opens the system "save as" dialog and writes the save JSON there. */
        @JavascriptInterface
        fun exportSave(json: String) {
            runOnUiThread {
                pendingExport = json
                try {
                    startActivityForResult(
                        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "application/json"
                            putExtra(Intent.EXTRA_TITLE, "crow-save.json")
                        },
                        REQ_EXPORT
                    )
                } catch (_: Exception) {
                    toast("امکان ذخیره‌ی فایل وجود ندارد")
                }
            }
        }

        /** Opens the system file picker and loads a backup into the game. */
        @JavascriptInterface
        fun importSave() {
            runOnUiThread {
                try {
                    startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        },
                        REQ_IMPORT
                    )
                } catch (_: Exception) {
                    toast("امکان انتخاب فایل وجود ندارد")
                }
            }
        }

        /** Returns only metadata for packs installed in app-private storage. */
        @JavascriptInterface
        fun getInstalledContentPacks(): String = installedContentPacksJson()
    }

    // ========== onActivityResult ادغام‌شده (نسخه‌ی نصب APK + خروجی/ورودی سیو) ==========
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // --- نتیجه‌ی نصب APK ---
        if (requestCode == REQ_INSTALL) {
            installerLaunched = false
            val installedVersion = try {
                val info = packageManager.getPackageInfo(packageName, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt() else info.versionCode
            } catch (_: Exception) { 0 }
            if (resultCode == RESULT_OK || (pendingInstallVersionCode > 0 && installedVersion >= pendingInstallVersionCode)) {
                pendingInstallFile = null
                pendingForceUpdate = false
                pendingInstallVersionCode = 0
                val launch = packageManager.getLaunchIntentForPackage(packageName)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    startActivity(launch)
                }
                finish()
                return
            }
            if (pendingForceUpdate) {
                AlertDialog.Builder(this)
                    .setTitle("به‌روزرسانی نصب نشد")
                    .setMessage("برای ادامه باید نسخه‌ی جدید Crow نصب شود.")
                    .setPositiveButton("تلاش دوباره") { _, _ ->
                        pendingInstallFile?.let { launchSystemInstaller(it) }
                    }
                    .setNegativeButton("خروج از بازی") { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
            } else {
                pendingInstallFile = null
                pendingInstallVersionCode = 0
                continueToGame()
            }
            return
        }

        // --- نتیجه‌ی خروجی/ورودی سیو (export/import) ---
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            pendingExport = null
            return
        }
        when (requestCode) {
            REQ_EXPORT -> {
                val json = pendingExport
                pendingExport = null
                if (json == null) return
                try {
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) }
                        ?: throw IllegalStateException("no stream")
                    toast("پشتیبان ذخیره شد")
                } catch (_: Exception) {
                    toast("ذخیره‌ی پشتیبان ناموفق بود")
                }
            }
            REQ_IMPORT -> {
                val text = try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readNBytesCompat(MAX_SAVE_BYTES + 1)
                        if (bytes.size > MAX_SAVE_BYTES) null else String(bytes)
                    }
                } catch (_: Exception) {
                    null
                }
                if (text == null) {
                    toast("فایل پشتیبان معتبر نیست")
                    return
                }
                gameView?.evaluateJavascript(
                    "(function(){ return !!(window.__crowImportSave && window.__crowImportSave(${JSONObject.quote(text)})); })();"
                ) { result ->
                    toast(if (result == "true") "پیشرفت بازیابی شد" else "فایل پشتیبان معتبر نیست")
                }
            }
        }
    }

    private data class ContentPackInfo(
        val id: String,
        val title: String,
        val version: Int,
        val entry: String,
        val order: Int,
        val levelCount: Int,
        val sha256: String
    )

    private fun clearInstalledContentPacks() {
        contentDirectory.listFiles()?.forEach(::deleteRecursively)
    }

    private fun importContentPack(uri: Uri, packHash: String) {
        val stagingRoot = File(cacheDir, "crow-pack-staging")
        if (!stagingRoot.exists() && !stagingRoot.mkdirs()) throw IOException("فضای موقت قابل‌ساخت نیست")
        val staging = File(stagingRoot, "pack-${System.nanoTime()}")
        if (!staging.mkdirs()) throw IOException("فضای موقت قابل‌ساخت نیست")

        try {
            var totalBytes = 0L
            var entryCount = 0
            contentResolver.openInputStream(uri)?.use { source ->
                val zip = ZipInputStream(source)
                val buffer = ByteArray(8192)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    if (entryCount > MAX_PACK_ENTRIES) throw IOException("تعداد فایل‌های بسته بیش از حد مجاز است")
                    val name = entry.name.replace('\\', '/')
                    if (name.startsWith("/") || name.contains(':') || name.split('/').any { it == ".." || it.isEmpty() && !entry.isDirectory }) {
                        throw IOException("مسیر نامعتبر در بسته پیدا شد")
                    }
                    if (name.isBlank()) throw IOException("نام فایل نامعتبر است")
                    val outFile = File(staging, name).canonicalFile
                    val rootPath = staging.canonicalPath + File.separator
                    if (!outFile.path.startsWith(rootPath)) throw IOException("مسیر ناامن در بسته پیدا شد")

                    if (entry.isDirectory) {
                        if (!outFile.exists() && !outFile.mkdirs()) throw IOException("ساخت پوشه ناموفق بود")
                    } else {
                        val parent = outFile.parentFile ?: throw IOException("ساختار ZIP نامعتبر است")
                        if (!parent.exists() && !parent.mkdirs()) throw IOException("ساخت پوشه ناموفق بود")
                        if (outFile.exists()) throw IOException("نام تکراری در بسته وجود دارد")
                        var fileBytes = 0L
                        outFile.outputStream().buffered().use { output ->
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                fileBytes += count
                                totalBytes += count
                                if (fileBytes > MAX_PACK_FILE_BYTES || totalBytes > MAX_PACK_UNCOMPRESSED_BYTES) {
                                    throw IOException("حجم بازشده‌ی بسته بیش از حد مجاز است")
                                }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            } ?: throw IOException("فایل انتخاب‌شده قابل‌خواندن نیست")

            if (entryCount == 0) throw IOException("فایل ZIP خالی است")
            val manifestFile = File(staging, "pack.json")
            if (!manifestFile.isFile || manifestFile.length() > MAX_PACK_MANIFEST_BYTES) {
                throw IOException("فایل pack.json در ریشه‌ی ZIP پیدا نشد")
            }
            val manifestText = manifestFile.readText(Charsets.UTF_8)
            val manifest = JSONObject(manifestText)
            if (manifest.optString("format") != PACK_FORMAT || manifest.optInt("schemaVersion", -1) != PACK_SCHEMA_VERSION) {
                throw IOException("فرمت بسته‌ی Crow پشتیبانی نمی‌شود")
            }
            val id = manifest.optString("id", "")
            if (!PACK_ID_REGEX.matches(id)) throw IOException("شناسه‌ی بسته نامعتبر است")
            val title = manifest.optString("title", "").trim()
            if (title.isEmpty() || title.length > 80) throw IOException("نام فصل نامعتبر است")
            val version = manifest.optInt("version", 1).coerceAtLeast(1)
            val levelCount = manifest.optInt("levelCount", 0)
            if (levelCount !in 0..5000) throw IOException("تعداد مراحل در pack.json نامعتبر است")
            val entry = manifest.optString("entry", "")
            if (!isSafePackRelativePath(entry) || !entry.endsWith(".js", ignoreCase = true)) {
                throw IOException("مسیر فایل اصلی فصل نامعتبر است")
            }
            if (!File(staging, entry).canonicalPath.startsWith(staging.canonicalPath + File.separator) || !File(staging, entry).isFile) {
                throw IOException("فایل اسکریپت فصل پیدا نشد")
            }
            val minimumGameVersion = manifest.optString("minGameVersion", "1.3")
            if (compareVersions(BuildConfig.VERSION_NAME, minimumGameVersion) < 0) {
                throw IOException("این بسته به نسخه‌ی جدیدتری از بازی نیاز دارد")
            }
            val order = manifest.optInt("order", 1).coerceAtLeast(1)
            if (!contentDirectory.exists() && !contentDirectory.mkdirs()) throw IOException("فضای نصب فصل‌ها ساخته نشد")
            manifestFile.writeText(
                JSONObject(manifest.toString())
                    .put("version", version).put("order", order).put("levelCount", levelCount)
                    .put("sha256", packHash).put("installedAt", System.currentTimeMillis())
                    .toString(2),
                Charsets.UTF_8
            )

            // Only one season lives on the device: the new one replaces whatever was installed.
            val destination = File(contentDirectory, id)
            val backup = File(contentDirectory, ".replaced-backup")
            deleteRecursively(backup)
            if (!backup.mkdirs()) throw IOException("جایگزینی فصل قبلی ممکن نشد")
            val previous = contentDirectory.listFiles()?.filter { it.name != backup.name } ?: emptyList()
            for (old in previous) {
                if (!old.renameTo(File(backup, old.name))) {
                    previous.forEach { moved -> File(backup, moved.name).takeIf { it.exists() }?.renameTo(moved) }
                    deleteRecursively(backup)
                    throw IOException("جایگزینی فصل قبلی ممکن نشد")
                }
            }
            if (!staging.renameTo(destination)) {
                previous.forEach { moved -> File(backup, moved.name).takeIf { it.exists() }?.renameTo(moved) }
                deleteRecursively(backup)
                throw IOException("نصب بسته ناموفق بود")
            }
            deleteRecursively(backup)
        } finally {
            deleteRecursively(staging)
        }
    }

    private fun isSafePackRelativePath(path: String): Boolean {
        if (path.isBlank() || path.startsWith("/") || path.contains('\\') || path.contains(':')) return false
        return path.split('/').all { it.isNotBlank() && it != "." && it != ".." }
    }

    private fun compareVersions(left: String, right: String): Int {
        val a = left.split('.').map { it.toIntOrNull() ?: 0 }
        val b = right.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private fun readInstalledContentPacks(): List<ContentPackInfo> {
        val dirs = contentDirectory.listFiles()?.filter { it.isDirectory && PACK_ID_REGEX.matches(it.name) } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            try {
                val file = File(dir, "pack.json")
                if (!file.isFile || file.length() > MAX_PACK_MANIFEST_BYTES) return@mapNotNull null
                val json = JSONObject(file.readText(Charsets.UTF_8))
                val entry = json.optString("entry", "")
                if (json.optString("format") != PACK_FORMAT || json.optString("id") != dir.name || !isSafePackRelativePath(entry)) return@mapNotNull null
                if (!File(dir, entry).isFile) return@mapNotNull null
                ContentPackInfo(
                    id = dir.name,
                    title = json.optString("title", dir.name),
                    version = json.optInt("version", 1),
                    entry = entry,
                    order = json.optInt("order", 0),
                    levelCount = json.optInt("levelCount", 0),
                    sha256 = json.optString("sha256", "")
                )
            } catch (_: Exception) { null }
        }.sortedWith(compareBy<ContentPackInfo> { it.order }.thenBy { pack ->
            try { JSONObject(File(contentDirectory, pack.id + "/pack.json").readText(Charsets.UTF_8)).optLong("installedAt", 0L) }
            catch (_: Exception) { 0L }
        }.thenBy { it.id })
    }

    private fun installedContentPacksJson(): String {
        val array = JSONArray()
        readInstalledContentPacks().forEach { pack ->
            array.put(JSONObject().apply {
                put("id", pack.id)
                put("title", pack.title)
                put("version", pack.version)
                put("entry", pack.entry)
                put("order", pack.order)
                put("levelCount", pack.levelCount)
                put("sha256", pack.sha256)
            })
        }
        return array.toString()
    }

    private fun deleteRecursively(file: File) {
        if (file.isDirectory) file.listFiles()?.forEach(::deleteRecursively)
        if (file.exists()) file.delete()
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (total < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - total))
            if (n < 0) break
            out.write(buffer, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun runJs(script: String) {
        runOnUiThread { gameView?.evaluateJavascript(script, null) }
    }

    // ---------------------------------------------------------------- window / lifecycle
    /** Sticky immersive full screen: system bars are hidden and reappear briefly on swipe. */
    private fun applyImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build()
                .also { focusRequest = it }
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
    }

    override fun onResume() {
        super.onResume()
        if (pendingInstallFile != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !installerLaunched) {
            if (permissionSettingsOpened && packageManager.canRequestPackageInstalls()) {
                permissionSettingsOpened = false
                pendingInstallFile?.let { launchSystemInstaller(it) }
            } else if (permissionSettingsOpened) {
                permissionSettingsOpened = false
                AlertDialog.Builder(this)
                    .setTitle("اجازه‌ی نصب فعال نشد")
                    .setMessage("برای نصب به‌روزرسانی باید اجازه‌ی نصب از Crow را در تنظیمات فعال کنی.")
                    .setPositiveButton("تلاش دوباره") { _, _ -> requestInstallPermissionAndLaunch() }
                    .setNegativeButton(if (pendingForceUpdate) "خروج از بازی" else "فعلاً نه") { _, _ ->
                        pendingInstallFile = null
                        if (pendingForceUpdate) finish() else continueToGame()
                    }
                    .setCancelable(false)
                    .show()
            }
        }
        gameView?.let {
            it.onResume()
            it.evaluateJavascript("window.androidResume && window.androidResume();", null)
        }
        requestAudioFocus()
        if (!noisyRegistered) {
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(noisyReceiver, filter)
            }
            noisyRegistered = true
        }
    }

    override fun onPause() {
        gameView?.let {
            it.evaluateJavascript("window.androidPause && window.androidPause();", null)
            it.onPause()
        }
        if (noisyRegistered) {
            try { unregisterReceiver(noisyReceiver) } catch (_: Exception) {}
            noisyRegistered = false
        }
        abandonAudioFocus()
        super.onPause()
    }

    /** Fallback for Android 12 and older (API < 33). */
    @Deprecated("Used only below API 33; newer versions use OnBackInvokedCallback.")
    override fun onBackPressed() {
        handleBack()
    }

    private fun handleBack() {
        val view = gameView
        if (view == null) {
            finish()
            return
        }
        // The game closes its active menu/panel first. It returns false at the title screen.
        view.evaluateJavascript(
            "(function(){ return !!(window.androidBack && window.androidBack()); })();"
        ) { result ->
            if (result != "true") finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        gameView?.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        gameLoadTimeout?.let(mainHandler::removeCallbacks)
        gameLoadTimeout = null
        webErrorDialog?.dismiss()
        webErrorDialog = null
        gameView?.let {
            (it.parent as? FrameLayout)?.removeView(it)
            it.stopLoading()
            it.removeAllViews()
            it.destroy()
        }
        gameView = null
        super.onDestroy()
    }

    private companion object {
        const val ASSET_HOST = "appassets.androidplatform.net"
        const val GAME_URL = "https://$ASSET_HOST/assets/www/index.html"
        const val REQ_EXPORT = 1001
        const val REQ_IMPORT = 1002
        const val REQ_INSTALL = 1004
        const val MAX_SAVE_BYTES = 1024 * 1024
        const val UPDATE_BASE_URL = "https://185.26.33.188"
        const val UPDATE_HOST = "185.26.33.188"
        const val MAX_APK_BYTES = 1024L * 1024 * 1024
        const val MAX_SEASON_BYTES = 160L * 1024 * 1024
        const val MAX_PACK_ENTRIES = 2000
        const val MAX_PACK_FILE_BYTES = 32L * 1024 * 1024
        const val MAX_PACK_UNCOMPRESSED_BYTES = 128L * 1024 * 1024
        const val MAX_PACK_MANIFEST_BYTES = 1024 * 1024
        const val PACK_FORMAT = "crow-season-pack"
        const val PACK_SCHEMA_VERSION = 1
        val PACK_ID_REGEX = Regex("[a-z0-9][a-z0-9_-]{1,47}")
    }
}
