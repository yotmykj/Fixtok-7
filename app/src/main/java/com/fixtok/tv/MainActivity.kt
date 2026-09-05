package com.fixtok.tv

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
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
        private const val TIKTOK_URL =
            "https://www.tiktok.com/"

        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Safari/537.36"

        private const val MOUSE_SPEED = 16f

        // WebView initial scale
        private const val DESKTOP_SCALE = 90

        // Real page scale.
        // 0.90 = 90%
        private const val TV_SCALE = 0.90f

        // Audio
        private const val BASS_STRENGTH = 1000
        private const val AUDIO_GAIN_MB = 0
    }

    private lateinit var binding: ActivityMainBinding

    private var splashHidden = false

    private var mouseX = 0f
    private var mouseY = 0f

    private var tvAudio: TvAudioController? = null

    private var lastAppliedPageZoom = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding =
            ActivityMainBinding.inflate(layoutInflater)

        setContentView(binding.root)

        enterFullscreen()

        // Android TV audio effects
        tvAudio = TvAudioController()

        tvAudio?.enable()

        tvAudio?.setBass(
            BASS_STRENGTH
        )

        tvAudio?.setGain(
            AUDIO_GAIN_MB
        )

        setupWebView()
        setupMouse()
        animateSplashIn()

        if (savedInstanceState == null) {

            binding.webView.loadUrl(
                TIKTOK_URL
            )

        } else {

            binding.webView.restoreState(
                savedInstanceState
            )
        }

        binding.root.post {
            centerMouse()
        }
    }

    private fun enterFullscreen() {

        window.statusBarColor =
            Color.BLACK

        window.navigationBarColor =
            Color.BLACK

        if (
            android.os.Build.VERSION.SDK_INT >=
            android.os.Build.VERSION_CODES.R
        ) {

            window.setDecorFitsSystemWindows(
                false
            )

            window.insetsController?.let { controller ->

                controller.hide(
                    WindowInsets.Type.systemBars()
                )

                controller.systemBarsBehavior =
                    WindowInsetsController
                        .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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

        val webView =
            binding.webView

        webView.setLayerType(
            View.LAYER_TYPE_HARDWARE,
            null
        )

        webView.isFocusable = true

        webView.isFocusableInTouchMode = true

        webView.settings.apply {

            javaScriptEnabled = true

            domStorageEnabled = true

            databaseEnabled = true

            javaScriptCanOpenWindowsAutomatically =
                true

            setSupportMultipleWindows(
                false
            )

            mediaPlaybackRequiresUserGesture =
                false

            allowFileAccess = false

            allowContentAccess = false

            cacheMode =
                WebSettings.LOAD_DEFAULT

            useWideViewPort = true

            // Native WebView page zoom. Do NOT use CSS zoom or View.scaleX/scaleY:
            // TikTok relies heavily on fixed/absolute layout and those can break it.
            loadWithOverviewMode = false

            setSupportZoom(true)

            builtInZoomControls = true

            displayZoomControls = false

            textZoom = 100

            defaultFontSize = 16

            defaultFixedFontSize = 16

            userAgentString =
                DESKTOP_USER_AGENT
        }

        // Initial WebView scale.
        webView.setInitialScale(
            DESKTOP_SCALE
        )

        CookieManager.getInstance().apply {

            setAcceptCookie(true)

            setAcceptThirdPartyCookies(
                webView,
                true
            )
        }

        webView.webViewClient =
            object : WebViewClient() {

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    return false
                }

                override fun onPageStarted(
                    view: WebView,
                    url: String,
                    favicon: android.graphics.Bitmap?
                ) {
                    super.onPageStarted(view, url, favicon)
                    lastAppliedPageZoom = 1f
                }

                override fun onScaleChanged(
                    view: WebView,
                    oldScale: Float,
                    newScale: Float
                ) {
                    super.onScaleChanged(view, oldScale, newScale)
                    lastAppliedPageZoom = newScale
                }

                override fun onPageFinished(
                    view: WebView,
                    url: String
                ) {
                    super.onPageFinished(
                        view,
                        url
                    )

                    // Safe in-page bass: only attaches to media elements when
                    // Web Audio is allowed for that media (same-origin/CORS).
                    // It never touches a cross-origin element that could become muted.
                    injectSafeWebAudioBass(view)

                    // setInitialScale is only a starting hint. The actual
                    // page zoom is applied once, using the current scale,
                    // so 0.90 is absolute instead of multiplying 0.90 again
                    // and again on repeated page callbacks.
                    view.postDelayed({
                        applyTvZoom(view)
                    }, 350)

                    if (
                        !splashHidden &&
                        url.contains(
                            "tiktok.com"
                        )
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

                    if (
                        request.isForMainFrame
                    ) {

                        view.postDelayed({

                            if (!splashHidden) {

                                view.loadUrl(
                                    TIKTOK_URL
                                )
                            }

                        }, 5000)
                    }
                }
            }

        webView.webChromeClient =
            object : WebChromeClient() {

                private var customView:
                    View? = null

                private var customViewCallback:
                    CustomViewCallback? = null

                override fun onShowCustomView(
                    view: View,
                    callback: CustomViewCallback
                ) {

                    customView = view

                    customViewCallback =
                        callback

                    binding.fullscreenContainer
                        .addView(
                            view,
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT
                            )
                        )

                    binding.fullscreenContainer
                        .visibility =
                        View.VISIBLE
                }

                override fun onHideCustomView() {

                    customView?.let {

                        binding.fullscreenContainer
                            .removeView(it)
                    }

                    customView = null

                    customViewCallback
                        ?.onCustomViewHidden()

                    customViewCallback = null

                    binding.fullscreenContainer
                        .visibility =
                        View.GONE
                }
            }

        webView.requestFocus()
    }

    private fun applyTvZoom(
        webView: WebView
    ) {
        if (!webView.settings.supportZoom) {
            webView.settings.setSupportZoom(true)
        }

        // WebView.zoomBy() is relative. Convert our desired absolute TV
        // zoom (90%) into a delta from the CURRENT WebView scale.
        // This prevents 0.90 x 0.90 x 0.90 ... accumulation.
        val current = webView.scale
            .takeIf { it > 0.01f }
            ?: lastAppliedPageZoom.takeIf { it > 0.01f }
            ?: 1f

        val factor = TV_SCALE / current

        if (kotlin.math.abs(factor - 1f) < 0.01f) {
            lastAppliedPageZoom = current
            return
        }

        try {
            webView.zoomBy(factor)
            lastAppliedPageZoom = TV_SCALE
            Log.d("FixTokZoom", "Applied absolute zoom=$TV_SCALE current=$current factor=$factor")
        } catch (t: Throwable) {
            Log.e("FixTokZoom", "Native WebView zoom failed", t)
        }
    }

    private fun injectSafeWebAudioBass(webView: WebView) {
        val js = """
            (() => {
              if (window.__fixtokBassInstalled) return;
              window.__fixtokBassInstalled = true;

              const BASS_DB = 8;
              const contexts = new Map();

              function sameOrigin(src) {
                try { return new URL(src, location.href).origin === location.origin; }
                catch (_) { return false; }
              }

              function attach(el) {
                if (!el || contexts.has(el) || !el.currentSrc) return;

                // Do NOT connect media that has no CORS permission.
                // MediaElementSource would otherwise silence cross-origin audio.
                const corsOK = el.crossOrigin === 'anonymous' ||
                               el.crossOrigin === 'use-credentials' ||
                               sameOrigin(el.currentSrc);
                if (!corsOK) return;

                try {
                  const Ctx = window.AudioContext || window.webkitAudioContext;
                  if (!Ctx) return;
                  const ctx = new Ctx();
                  const source = ctx.createMediaElementSource(el);
                  const bass = ctx.createBiquadFilter();
                  bass.type = 'lowshelf';
                  bass.frequency.value = 140;
                  bass.gain.value = BASS_DB;
                  source.connect(bass);
                  bass.connect(ctx.destination);
                  contexts.set(el, {ctx, bass});
                  const resume = () => { try { ctx.resume(); } catch (_) {} };
                  el.addEventListener('play', resume, {passive:true});
                  resume();
                } catch (_) {
                  // Never interfere with normal playback if Web Audio is rejected.
                }
              }

              function scan() {
                document.querySelectorAll('video,audio').forEach(attach);
              }

              scan();
              new MutationObserver(scan).observe(document.documentElement, {childList:true, subtree:true});
              setInterval(scan, 1500);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun setupMouse() {

        binding.mouseCursor.apply {

            isClickable = false

            isFocusable = false

            isFocusableInTouchMode = false

            setOnTouchListener { _, _ ->
                false
            }
        }
    }

    private fun centerMouse() {

        val width =
            binding.root.width.toFloat()

        val height =
            binding.root.height.toFloat()

        if (
            width <= 0f ||
            height <= 0f
        ) {
            return
        }

        mouseX =
            width / 2f

        mouseY =
            height / 2f

        updateMousePosition()
    }

    private fun moveMouse(
        dx: Float,
        dy: Float
    ) {

        val width =
            binding.root.width.toFloat()

        val height =
            binding.root.height.toFloat()

        if (
            width <= 0f ||
            height <= 0f
        ) {
            return
        }

        mouseX = min(
            width -
                binding.mouseCursor.width,
            max(
                0f,
                mouseX + dx
            )
        )

        mouseY = min(
            height -
                binding.mouseCursor.height,
            max(
                0f,
                mouseY + dy
            )
        )

        updateMousePosition()
    }

    private fun updateMousePosition() {

        binding.mouseCursor.translationX =
            mouseX

        binding.mouseCursor.translationY =
            mouseY
    }

    private fun clickMouse() {

        val webView =
            binding.webView

        val cursorCenterX =
            mouseX +
                binding.mouseCursor.width / 2f

        val cursorCenterY =
            mouseY +
                binding.mouseCursor.height / 2f

        val x =
            cursorCenterX.coerceIn(
                0f,
                webView.width.toFloat()
            )

        val y =
            cursorCenterY.coerceIn(
                0f,
                webView.height.toFloat()
            )

        val downTime =
            SystemClock.uptimeMillis()

        val downEvent =
            MotionEvent.obtain(
                downTime,
                downTime,
                MotionEvent.ACTION_DOWN,
                x,
                y,
                0
            )

        webView.dispatchTouchEvent(
            downEvent
        )

        downEvent.recycle()

        val upTime =
            SystemClock.uptimeMillis()

        val upEvent =
            MotionEvent.obtain(
                downTime,
                upTime,
                MotionEvent.ACTION_UP,
                x,
                y,
                0
            )

        webView.dispatchTouchEvent(
            upEvent
        )

        upEvent.recycle()
    }

    override fun dispatchKeyEvent(
        event: KeyEvent
    ): Boolean {

        if (
            event.action ==
            KeyEvent.ACTION_DOWN
        ) {

            when (event.keyCode) {

                KeyEvent.KEYCODE_DPAD_UP -> {

                    moveMouse(
                        0f,
                        -MOUSE_SPEED
                    )

                    return true
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> {

                    moveMouse(
                        0f,
                        MOUSE_SPEED
                    )

                    return true
                }

                KeyEvent.KEYCODE_DPAD_LEFT -> {

                    moveMouse(
                        -MOUSE_SPEED,
                        0f
                    )

                    return true
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {

                    moveMouse(
                        MOUSE_SPEED,
                        0f
                    )

                    return true
                }

                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {

                    if (
                        event.repeatCount == 0
                    ) {
                        clickMouse()
                    }

                    return true
                }

                KeyEvent.KEYCODE_BACK -> {

                    if (
                        binding.webView.canGoBack()
                    ) {

                        binding.webView.goBack()

                    } else {

                        finish()
                    }

                    return true
                }
            }
        }

        return super.dispatchKeyEvent(
            event
        )
    }

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

        if (splashHidden) {
            return
        }

        splashHidden = true

        binding.splashContainer
            .animate()
            .alpha(0f)
            .setDuration(500L)
            .withEndAction {

                binding.splashContainer
                    .visibility =
                    View.GONE
            }
            .start()
    }

    override fun onSaveInstanceState(
        outState: Bundle
    ) {

        binding.webView.saveState(
            outState
        )

        super.onSaveInstanceState(
            outState
        )
    }

    override fun onResume() {
        super.onResume()

        binding.webView.onResume()

        binding.webView.resumeTimers()

        tvAudio?.enable()

        tvAudio?.setBass(
            BASS_STRENGTH
        )

        tvAudio?.setGain(
            AUDIO_GAIN_MB
        )
    }

    override fun onPause() {

        binding.webView.onPause()

        binding.webView.pauseTimers()

        tvAudio?.disable()

        super.onPause()
    }

    override fun onDestroy() {

        tvAudio?.release()

        tvAudio = null

        binding.webView.stopLoading()

        binding.webView.removeAllViews()

        binding.webView.destroy()

        super.onDestroy()
    }
}
