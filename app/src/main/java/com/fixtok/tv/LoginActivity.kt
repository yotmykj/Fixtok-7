package com.fixtok.tv

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.fixtok.tv.databinding.ActivityLoginBinding
import kotlin.math.max
import kotlin.math.min

/**
 * Отдельный экран входа: свой WebView, свой lifecycle, свой allowlist доменов.
 *
 * В отличие от основного экрана, здесь разрешена виртуальная мышь — страницы
 * входа TikTok/Google/Facebook/Apple рассчитаны на указатель, и без курсора
 * по ним невозможно нормально пройти. После закрытия этого экрана мышь
 * полностью исчезает: в основном интерфейсе Fixtok её нет.
 *
 * Домены проверяются строго по URI.host (точное совпадение или поддомен).
 * Проверки вида url.contains("tiktok.com") запрещены — они пропускают
 * evil-tiktok.com и tiktok.com.evil.com.
 */
class LoginActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "FixTokLogin"

        private const val LOGIN_URL = "https://www.tiktok.com/login"

        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Safari/537.36"

        private const val MOUSE_SPEED = 16f

        // Разрешённые домены для навигации внутри Login WebView.
        private val ALLOWED_HOSTS = listOf(
            "tiktok.com",
            "accounts.google.com",
            "facebook.com",
            "appleid.apple.com",
            "idmsa.apple.com"
        )

        fun isAllowedHost(host: String?): Boolean {
            val h = host?.lowercase() ?: return false
            return ALLOWED_HOSTS.any { h == it || h.endsWith(".$it") }
        }

        /** Есть ли действующая сессия TikTok в CookieManager. */
        fun hasSessionCookie(cookieManager: CookieManager): Boolean {
            val cookies = cookieManager.getCookie("https://www.tiktok.com")
                ?: return false
            return cookies.split(";").any { part ->
                val trimmed = part.trim()
                trimmed.startsWith("sessionid=") &&
                    trimmed.substringAfter("=", "").isNotBlank()
            }
        }
    }

    private lateinit var binding: ActivityLoginBinding

    private var mouseX = 0f
    private var mouseY = 0f

    private var popupWebView: WebView? = null

    /** Login completed: finish() called to avoid double finish. */
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enterFullscreen()
        setupWebView()
        setupMouse()

        if (savedInstanceState == null) {
            binding.loginWebView.loadUrl(LOGIN_URL)
        } else {
            binding.loginWebView.restoreState(savedInstanceState)
        }

        binding.root.post { centerMouse() }
    }

    private fun enterFullscreen() {
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.loginWebView

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            textZoom = 100
            userAgentString = DESKTOP_USER_AGENT
        }

        // Тот же CookieManager, что у основного WebView: сессия общая.
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                return !allowNavigation(request.url)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(
                view: WebView,
                url: String?
            ): Boolean {
                val uri = url?.let {
                    runCatching { android.net.Uri.parse(it) }.getOrNull()
                }
                return if (uri == null) true else !allowNavigation(uri)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                checkLoginComplete()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean {
                // Google/Facebook/Apple OAuth открываются через window.open().
                closePopup()

                val popup = WebView(this@LoginActivity).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = DESKTOP_USER_AGENT
                    settings.setSupportMultipleWindows(false)

                    webViewClient = object : WebViewClient() {

                        override fun shouldOverrideUrlLoading(
                            v: WebView,
                            req: WebResourceRequest
                        ): Boolean {
                            return !allowNavigation(req.url)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun shouldOverrideUrlLoading(
                            v: WebView,
                            url: String?
                        ): Boolean {
                            val uri = url?.let {
                                runCatching { android.net.Uri.parse(it) }.getOrNull()
                            }
                            return if (uri == null) true else !allowNavigation(uri)
                        }

                        override fun onPageFinished(v: WebView, url: String) {
                            super.onPageFinished(v, url)
                            // Фlow вернулся на TikTok — вход завершён.
                            val host = runCatching {
                                android.net.Uri.parse(url).host?.lowercase()
                            }.getOrNull()
                            if (host == "tiktok.com" ||
                                host?.endsWith(".tiktok.com") == true
                            ) {
                                closePopup()
                                checkLoginComplete()
                            }
                        }
                    }
                }

                popupWebView = popup

                binding.loginPopupContainer.removeAllViews()
                binding.loginPopupContainer.addView(
                    popup,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                binding.loginPopupContainer.visibility = View.VISIBLE
                popup.requestFocus()

                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = popup
                resultMsg.sendToTarget()

                return true
            }

            override fun onCloseWindow(window: WebView) {
                closePopup()
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                // Камера/микрофон приложению не нужны — запросы отклоняются.
                runOnUiThread {
                    runCatching { request.deny() }
                }
            }
        }

        webView.requestFocus()
    }

    /**
     * Разрешена ли навигация по URI. Строгая проверка host из allowlist
     * плюс схема https. Всё остальное блокируется.
     */
    private fun allowNavigation(uri: android.net.Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()

        if (scheme != "https") {
            Log.w(TAG, "Blocked non-https navigation: $uri")
            return false
        }
        if (!isAllowedHost(host)) {
            Log.w(TAG, "Blocked navigation outside allowlist: $uri")
            return false
        }
        return true
    }

    /** Сессия появилась — вход выполнен, возвращаемся в основной экран. */
    private fun checkLoginComplete() {
        if (finished) return
        if (hasSessionCookie(CookieManager.getInstance())) {
            finished = true
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun closePopup() {
        popupWebView?.let { popup ->
            binding.loginPopupContainer.removeView(popup)
            popup.stopLoading()
            popup.destroy()
        }
        popupWebView = null
        binding.loginPopupContainer.visibility = View.GONE
    }

    // ── Виртуальная мышь (только внутри Login WebView) ──────────────

    private fun setupMouse() {
        binding.loginMouseCursor.apply {
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
            setOnTouchListener { _, _ -> false }
        }
    }

    private fun centerMouse() {
        val width = binding.root.width.toFloat()
        val height = binding.root.height.toFloat()
        if (width <= 0f || height <= 0f) return

        mouseX = width / 2f
        mouseY = height / 2f
        updateMousePosition()
    }

    private fun moveMouse(dx: Float, dy: Float) {
        val width = binding.root.width.toFloat()
        val height = binding.root.height.toFloat()
        if (width <= 0f || height <= 0f) return

        mouseX = min(
            width - binding.loginMouseCursor.width,
            max(0f, mouseX + dx)
        )
        mouseY = min(
            height - binding.loginMouseCursor.height,
            max(0f, mouseY + dy)
        )
        updateMousePosition()
    }

    private fun updateMousePosition() {
        binding.loginMouseCursor.translationX = mouseX
        binding.loginMouseCursor.translationY = mouseY
    }

    private fun clickMouse() {
        val target = popupWebView ?: binding.loginWebView

        val x = (mouseX + binding.loginMouseCursor.width / 2f)
            .coerceIn(0f, target.width.toFloat())
        val y = (mouseY + binding.loginMouseCursor.height / 2f)
            .coerceIn(0f, target.height.toFloat())

        val downTime = SystemClock.uptimeMillis()

        val downEvent = MotionEvent.obtain(
            downTime, downTime,
            MotionEvent.ACTION_DOWN, x, y, 0
        )
        target.dispatchTouchEvent(downEvent)
        downEvent.recycle()

        val upEvent = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP, x, y, 0
        )
        target.dispatchTouchEvent(upEvent)
        upEvent.recycle()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    moveMouse(0f, -MOUSE_SPEED)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    moveMouse(0f, MOUSE_SPEED)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    moveMouse(-MOUSE_SPEED, 0f)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    moveMouse(MOUSE_SPEED, 0f)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    if (event.repeatCount == 0) {
                        clickMouse()
                    }
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    // Back внутри popup закрывает popup, иначе — закрывает
                    // Login WebView и возвращает фокус основному экрану.
                    if (popupWebView != null) {
                        closePopup()
                    } else {
                        setResult(RESULT_CANCELED)
                        finished = true
                        finish()
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        binding.loginWebView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        binding.loginWebView.onResume()
        binding.loginWebView.resumeTimers()
    }

    override fun onPause() {
        binding.loginWebView.onPause()
        binding.loginWebView.pauseTimers()
        super.onPause()
    }

    override fun onDestroy() {
        closePopup()
        binding.loginWebView.stopLoading()
        binding.loginWebView.removeAllViews()
        binding.loginWebView.destroy()
        super.onDestroy()
    }
}
