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
 * FixTok — Android TV клиент TikTok на базе одного полноэкранного WebView.
 *
 * Схема: FixTok → WebView → TikTok
 *
 * Splash screen с логотипом и подписью «by Manas» показывается до тех пор,
 * пока WebView успешно не загрузит TikTok, затем плавно исчезает.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TIKTOK_URL = "https://www.tiktok.com/"
        private const val SPLASH_FADE_DURATION_MS = 500L
    }

    private lateinit var binding: ActivityMainBinding
    private var splashHidden = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enterFullscreen()
        animateSplashIn()
        setupWebView(savedInstanceState)

        if (savedInstanceState == null) {
            binding.webView.loadUrl(TIKTOK_URL)
        } else {
            binding.webView.restoreState(savedInstanceState)
        }
    }

    /** Полноэкранный режим: скрываем статус-бар и навигацию. */
    private fun enterFullscreen() {
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(savedInstanceState: Bundle?) {
        val webView = binding.webView

        // WebView занимает 100% доступного экрана
        webView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true

            // Медиа и HTML5 video
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false

            // Кеширование
            cacheMode = WebSettings.LOAD_DEFAULT

            // Отображение под TV-экран
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false

            // Современный user agent — чтобы TikTok отдавал десктопную версию
            userAgentString = userAgentString.replace("; wv", "")
        }

        // Cookies: обычные, сторонние и постоянные
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        // Сохранение сессии WebView между поворотами/пересозданием
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        }

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                // Вся навигация остаётся внутри WebView — без собственного UI
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // Splash скрывается только после успешной загрузки TikTok,
                // а не по таймеру
                hideSplash()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                // Ошибка — splash остаётся, WebView покажет страницу ошибки.
                // Можно добавить повторную попытку:
                if (request.isForMainFrame) {
                    view.postDelayed({
                        if (!splashHidden) view.loadUrl(TIKTOK_URL)
                    }, 5000)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // Полноэкранное HTML5 video
            private var customView: View? = null
            private var customViewCallback: CustomViewCallback? = null

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
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
                customView?.let { binding.fullscreenContainer.removeView(it) }
                customView = null
                customViewCallback?.onCustomViewHidden()
                binding.fullscreenContainer.visibility = View.GONE
            }
        }

        webView.requestFocus()
    }

    /** Плавная анимация появления splash screen при запуске. */
    private fun animateSplashIn() {
        binding.splashLogo.apply {
            alpha = 0f
            scaleX = 0.8f
            scaleY = 0.8f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(700L).start()
        }
        binding.splashTitle.apply {
            alpha = 0f
            animate().alpha(1f).setStartDelay(300L).setDuration(500L).start()
        }
        binding.splashByManas.apply {
            alpha = 0f
            animate().alpha(1f).setStartDelay(500L).setDuration(500L).start()
        }
    }

    /** Плавное исчезновение splash screen после загрузки TikTok. */
    private fun hideSplash() {
        if (splashHidden) return
        splashHidden = true

        binding.splashContainer.animate()
            .alpha(0f)
            .setDuration(SPLASH_FADE_DURATION_MS)
            .withEndAction {
                binding.splashContainer.visibility = View.GONE
            }
            .start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    /**
     * Кнопка Back на пульте:
     * если WebView может вернуться назад — возвращаемся в истории,
     * иначе — закрываем Activity.
     */
    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    /** Обработка D-pad / OK / Back с пульта Android TV. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                onBackPressed()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                // Передаём OK/Select в WebView (фокус уже на нём)
                return super.onKeyDown(keyCode, event)
            }
        }
        return super.onKeyDown(keyCode, event)
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
        binding.webView.destroy()
        super.onDestroy()
    }
}
