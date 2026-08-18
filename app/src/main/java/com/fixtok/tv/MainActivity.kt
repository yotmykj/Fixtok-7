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

/**
 * FixTok
 *
 * Android TV WebView-клиент.
 *
 * FixTok → WebView → TikTok
 *
 * WebView работает как Desktop Chrome,
 * чтобы TikTok отдавал desktop-версию сайта.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TIKTOK_URL = "https://www.tiktok.com/"
        private const val SPLASH_FADE_DURATION_MS = 500L

        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Safari/537.36"
    }

    private lateinit var binding: ActivityMainBinding

    private var splashHidden = false

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
    }

    /**
     * Полноэкранный режим.
     */
    private fun enterFullscreen() {
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {

            window.setDecorFitsSystemWindows(false)

            window.insetsController?.let { controller ->

                controller.hide(
                    WindowInsets.Type.systemBars()
                )

                controller.systemBarsBehavior =
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

    /**
     * Настройка WebView.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {

        val webView = binding.webView

        webView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        webView.setLayerType(
            View.LAYER_TYPE_HARDWARE,
            null
        )

        webView.isFocusable = true
        webView.isFocusableInTouchMode = true

        webView.settings.apply {

            // JavaScript
            javaScriptEnabled = true

            // Storage
            domStorageEnabled = true
            databaseEnabled = true

            // Windows
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)

            // Video
            mediaPlaybackRequiresUserGesture = false

            // Security
            allowFileAccess = false
            allowContentAccess = false

            // Cache
            cacheMode = WebSettings.LOAD_DEFAULT

            // Desktop layout
            useWideViewPort = true
            loadWithOverviewMode = true

            // Disable zoom
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false

            /*
             * IMPORTANT
             *
             * WebView представляется как Desktop Chrome,
             * а не Android WebView.
             */
            userAgentString = DESKTOP_USER_AGENT
        }

        /*
         * Cookies
         */
        CookieManager.getInstance().apply {

            setAcceptCookie(true)

            setAcceptThirdPartyCookies(
                webView,
                true
            )
        }

        /*
         * WebViewClient
         */
        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {

                // Все ссылки остаются внутри WebView.
                return false
            }

            override fun onPageFinished(
                view: WebView,
                url: String
            ) {

                super.onPageFinished(
                    view,
                    url
                )

                /*
                 * Splash скрывается только после загрузки
                 * TikTok, а не через таймер.
                 */
                if (
                    !splashHidden &&
                    url.contains("tiktok.com")
                ) {
                    hideSplash()
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {

                super.onReceivedError(
                    view,
                    request,
                    error
                )

                /*
                 * Повторная попытка только для
                 * основной страницы.
                 */
                if (request.isForMainFrame) {

                    view.postDelayed({

                        if (!splashHidden) {
                            view.loadUrl(TIKTOK_URL)
                        }

                    }, 5000)
                }
            }
        }

        /*
         * WebChromeClient
         *
         * Нужен для HTML5 video/fullscreen.
         */
        webView.webChromeClient =
            object : WebChromeClient() {

                private var customView: View? = null

                private var customViewCallback:
                    CustomViewCallback? = null

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

                    binding.fullscreenContainer.visibility =
                        View.VISIBLE
                }

                override fun onHideCustomView() {

                    customView?.let {
                        binding.fullscreenContainer.removeView(it)
                    }

                    customView = null

                    customViewCallback?.onCustomViewHidden()
                    customViewCallback = null

                    binding.fullscreenContainer.visibility =
                        View.GONE
                }
            }

        webView.requestFocus()
    }

    /**
     * Анимация появления Splash.
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

    /**
     * Скрытие Splash.
     */
    private fun hideSplash() {

        if (splashHidden) {
            return
        }

        splashHidden = true

        binding.splashContainer
            .animate()
            .alpha(0f)
            .setDuration(
                SPLASH_FADE_DURATION_MS
            )
            .withEndAction {

                binding.splashContainer.visibility =
                    View.GONE
            }
            .start()
    }

    /**
     * Сохраняем состояние WebView.
     */
    override fun onSaveInstanceState(
        outState: Bundle
    ) {

        binding.webView.saveState(outState)

        super.onSaveInstanceState(outState)
    }

    /**
     * Back на Android TV-пульте.
     */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {

        if (binding.webView.canGoBack()) {

            binding.webView.goBack()

        } else {

            super.onBackPressed()
        }
    }

    /**
     * D-pad / OK / Back.
     */
    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent?
    ): Boolean {

        when (keyCode) {

            KeyEvent.KEYCODE_BACK -> {

                @Suppress("DEPRECATION")
                onBackPressed()

                return true
            }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {

                return super.onKeyDown(
                    keyCode,
                    event
                )
            }
        }

        return super.onKeyDown(
            keyCode,
            event
        )
    }

    /**
     * Resume WebView.
     */
    override fun onResume() {

        super.onResume()

        binding.webView.onResume()
        binding.webView.resumeTimers()
    }

    /**
     * Pause WebView.
     */
    override fun onPause() {

        binding.webView.onPause()
        binding.webView.pauseTimers()

        super.onPause()
    }

    /**
     * Destroy WebView.
     */
    override fun onDestroy() {

        binding.webView.stopLoading()
        binding.webView.loadUrl("about:blank")
        binding.webView.clearHistory()
        binding.webView.removeAllViews()
        binding.webView.destroy()

        super.onDestroy()
    }
}
