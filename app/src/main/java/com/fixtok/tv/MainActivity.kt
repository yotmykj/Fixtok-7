package com.fixtok.tv

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.fixtok.tv.databinding.ActivityMainBinding
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TIKTOK_URL = "https://www.tiktok.com/"

        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Safari/537.36"
    }

    private lateinit var binding: ActivityMainBinding

    private var splashHidden = false

    // Положение виртуальной мыши
    private var mouseX = 0f
    private var mouseY = 0f

    // Скорость курсора
    private var mouseSpeed = 18f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enterFullscreen()
        animateSplashIn()
        setupWebView()

        if (savedInstanceState == null) {
            binding.webView.loadUrl(TIKTOK_URL)
        } else {
            binding.webView.restoreState(savedInstanceState)
        }

        binding.mouseCursor.post {
            centerMouse()
        }
    }

    private fun enterFullscreen() {
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)

            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {

        val webView = binding.webView

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        webView.isFocusable = true
        webView.isFocusableInTouchMode = true

        webView.settings.apply {

            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true

            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)

            mediaPlaybackRequiresUserGesture = false

            allowFileAccess = false
            allowContentAccess = false

            cacheMode = WebSettings.LOAD_DEFAULT

            /*
             * Desktop layout.
             *
             * Не используем loadWithOverviewMode:
             * он часто делает слишком маленький масштаб
             * на TV.
             */
            useWideViewPort = true
            loadWithOverviewMode = false

            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false

            /*
             * Desktop Chrome.
             */
            userAgentString = DESKTOP_USER_AGENT

            /*
             * Оставляем стандартный размер шрифта.
             */
            defaultFontSize = 16
            defaultFixedFontSize = 16
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                return false
            }

            override fun onPageFinished(
                view: WebView,
                url: String
            ) {
                super.onPageFinished(view, url)

                if (!splashHidden && url.contains("tiktok.com")) {
                    hideSplash()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            private var customView: View? = null
            private var customViewCallback: CustomViewCallback? = null

            override fun onShowCustomView(
                view: View,
                callback: CustomViewCallback
            ) {

                customView = view
                customViewCallback = callback

                binding.fullscreenContainer.addView(
                    view,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )

                binding.fullscreenContainer.visibility = View.VISIBLE
            }

            override fun onHideCustomView() {

                customView?.let {
                    binding.fullscreenContainer.removeView(it)
                }

                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null

                binding.fullscreenContainer.visibility = View.GONE
            }
        }

        webView.requestFocus()
    }

    /*
     * -------------------------
     * ВИРТУАЛЬНАЯ МЫШЬ
     * -------------------------
     */

    private fun centerMouse() {

        val rootWidth = binding.root.width.toFloat()
        val rootHeight = binding.root.height.toFloat()

        if (rootWidth <= 0 || rootHeight <= 0) return

        mouseX = rootWidth / 2f
        mouseY = rootHeight / 2f

        updateMousePosition()
    }

    private fun moveMouse(dx: Float, dy: Float) {

        val width = binding.root.width.toFloat()
        val height = binding.root.height.toFloat()

        if (width <= 0 || height <= 0) return

        mouseX = min(
            width,
            max(0f, mouseX + dx)
        )

        mouseY = min(
            height,
            max(0f, mouseY + dy)
        )

        updateMousePosition()
    }

    private fun updateMousePosition() {

        binding.mouseCursor.translationX = mouseX
        binding.mouseCursor.translationY = mouseY
    }

    /*
     * Клик в координате курсора.
     *
     * Используем JS MouseEvent, чтобы PC-сайт
     * воспринимал OK как обычный клик мыши.
     */
    private fun clickMouse() {

        val x = mouseX
        val y = mouseY

        val js = """
            (function() {
                var x = ${x.toInt()};
                var y = ${y.toInt()};

                var el = document.elementFromPoint(x, y);

                if (el) {
                    el.dispatchEvent(
                        new MouseEvent('mousedown', {
                            bubbles: true,
                            cancelable: true,
                            clientX: x,
                            clientY: y,
                            button: 0
                        })
                    );

                    el.dispatchEvent(
                        new MouseEvent('mouseup', {
                            bubbles: true,
                            cancelable: true,
                            clientX: x,
                            clientY: y,
                            button: 0
                        })
                    );

                    el.click();
                }
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(js, null)
    }

    /*
     * -------------------------
     * ПУЛЬТ
     * -------------------------
     */

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {

        if (event.action == KeyEvent.ACTION_DOWN) {

            when (event.keyCode) {

                KeyEvent.KEYCODE_DPAD_UP -> {
                    moveMouse(0f, -mouseSpeed)
                    return true
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    moveMouse(0f, mouseSpeed)
                    return true
                }

                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    moveMouse(-mouseSpeed, 0f)
                    return true
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    moveMouse(mouseSpeed, 0f)
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

                    if (binding.webView.canGoBack()) {
                        binding.webView.goBack()
                    } else {
                        finish()
                    }

                    return true
                }
            }
        }

        return super.dispatchKeyEvent(event)
    }

    /*
     * -------------------------
     * SPLASH
     * -------------------------
     */

    private fun animateSplashIn() {

        binding.splashLogo.apply {
            alpha = 0f
            scaleX = 0.8f
            scaleY = 0.8f

            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(700L)
                .start()
        }

        binding.splashTitle.apply {
            alpha = 0f

            animate()
                .alpha(1f)
                .setStartDelay(300L)
                .setDuration(500L)
                .start()
        }

        binding.splashByManas.apply {
            alpha = 0f

            animate()
                .alpha(1f)
                .setStartDelay(500L)
                .setDuration(500L)
                .start()
        }
    }

    private fun hideSplash() {

        if (splashHidden) return

        splashHidden = true

        binding.splashContainer
            .animate()
            .alpha(0f)
            .setDuration(500L)
            .withEndAction {
                binding.splashContainer.visibility = View.GONE
            }
            .start()
    }

    override fun onSaveInstanceState(outState: Bundle) {

        binding.webView.saveState(outState)

        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()

        binding.webView.onResume()
        binding.webView.resumeTimers()
    }

    override fun onPause() {

        binding.webView.onPause()
        binding.webView.pauseTimers()

        super.onPause()
    }

    override fun onDestroy() {

        binding.webView.stopLoading()
        binding.webView.loadUrl("about:blank")
        binding.webView.removeAllViews()
        binding.webView.destroy()

        super.onDestroy()
    }
}
