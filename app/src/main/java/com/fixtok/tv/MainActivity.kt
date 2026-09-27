package com.fixtok.tv

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.SafeBrowsingResponse
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.fixtok.tv.databinding.ActivityMainBinding
import kotlin.math.abs
import kotlin.math.pow

/**
 * Основной экран Fixtok — чистое TV-приложение.
 *
 * Управление: только D-pad + пульт (OK — клик/scroll-фокус внутри WebView,
 * Back — назад/выход, Menu — настройки). Виртуальной мыши в этом экране
 * нет. Вход выполняется в отдельном [LoginActivity], где мышь разрешена.
 *
 * Миграция сессии: старая и новая система используют один и тот же
 * CookieManager (applicationId не менялся), поэтому рабочая старая сессия
 * автоматически становится новой — ничего не удаляется. Если TikTok
 * редиректит на страницу входа (сессия недействительна), открывается
 * Login WebView. Старые данные не уничтожаются ни на одном этапе.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "FixTok"

        private const val TIKTOK_URL = "https://www.tiktok.com/"

        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Safari/537.36"

        // WebView initial scale hint (percent).
        private const val DESKTOP_SCALE = 90

        // Absolute target page zoom. 0.90 = 90%
        private const val TV_SCALE = 0.90f

        // Audio
        private const val BASS_STRENGTH = 1000

        // Connection retry
        private const val MAX_RETRIES = 5
        private const val BASE_RETRY_DELAY_MS = 2000L

        // Double-back-to-exit window
        private const val EXIT_CONFIRM_WINDOW_MS = 2000L
    }

    private lateinit var binding: ActivityMainBinding

    private var splashHidden = false

    private var tvAudio: TvAudioController? = null
    private var bassEnabled = true

    private val mainHandler = Handler(Looper.getMainLooper())

    private var retryCount = 0
    private var pendingRetry = false

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private var lastBackPressTime = 0L

    /** Login screen launcher. RESULT_OK — сессия изменилась, нужен reload. */
    private val loginLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                // Сессия появилась/изменилась — перезагружаем ленту.
                retryCount = 0
                pendingRetry = false
                hideErrorOverlay()
                binding.webView.loadUrl(TIKTOK_URL)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        loadPreferences()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enterFullscreen()

        // Android TV audio effects (best effort — see TvAudioController).
        if (bassEnabled) {
            tvAudio = TvAudioController()
            tvAudio?.enable()
            tvAudio?.setBass(BASS_STRENGTH)
        }

        setupWebView()
        animateSplashIn()
        setupTvChrome()

        if (savedInstanceState == null) {
            binding.webView.loadUrl(TIKTOK_URL)
        } else {
            binding.webView.restoreState(savedInstanceState)
        }
    }

    /** Wires the TV chrome: settings button, retry button, account status, auto-hiding bottom bar. */
    private fun setupTvChrome() {
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.errorRetryButton.setOnClickListener { retryNow() }
        updateAccountStatus()
        scheduleBottomBarAutoHide()
    }

    private fun updateAccountStatus() {
        val loggedIn = LoginActivity.hasSessionCookie(CookieManager.getInstance())
        binding.topBarAccountStatus.setText(
            if (loggedIn) R.string.account_status_logged_in
            else R.string.account_status_logged_out
        )
    }

    private val hideBottomBarRunnable = Runnable {
        binding.bottomBar.animate().alpha(0f).setDuration(300L).start()
    }

    private fun scheduleBottomBarAutoHide() {
        mainHandler.removeCallbacks(hideBottomBarRunnable)
        binding.bottomBar.animate().alpha(1f).setDuration(150L).start()
        mainHandler.postDelayed(hideBottomBarRunnable, 5000L)
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
        bassEnabled = prefs.getBoolean(SettingsActivity.KEY_BASS, true)
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
            // Вход через Google/Facebook/Apple обрабатывается отдельным
            // LoginActivity; в основном WebView popup-окна не нужны.
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)

            mediaPlaybackRequiresUserGesture = false

            allowFileAccess = false
            allowContentAccess = false

            cacheMode = WebSettings.LOAD_DEFAULT

            useWideViewPort = true
            // Native WebView page zoom only. Do NOT use CSS zoom or
            // View.scaleX/scaleY: TikTok relies heavily on fixed/absolute
            // layout and those can break it.
            loadWithOverviewMode = false

            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false

            textZoom = 100
            defaultFontSize = 16
            defaultFixedFontSize = 16

            userAgentString = DESKTOP_USER_AGENT

            // Safe Browsing никогда не отключаем ради совместимости.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
        }

        webView.setInitialScale(DESKTOP_SCALE)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                return handleNavigation(request.url)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(
                view: WebView,
                url: String?
            ): Boolean {
                val uri = url?.let {
                    runCatching { android.net.Uri.parse(it) }.getOrNull()
                } ?: return true
                return handleNavigation(uri)
            }

            /**
             * Навигация в основном WebView. Разрешены только tiktok.com и
             * его поддомены; редирект на /login означает недействительную
             * сессию — открываем LoginActivity (сценарий C ТЗ). Внешние
             * ссылки открываем системным браузером, если он есть.
             */
            private fun handleNavigation(uri: android.net.Uri): Boolean {
                val scheme = uri.scheme?.lowercase()
                val host = uri.host?.lowercase()

                if (scheme != "https" && scheme != "http") return true

                val isTikTok =
                    host == "tiktok.com" || host?.endsWith(".tiktok.com") == true
                if (!isTikTok) {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }
                    return true
                }

                if (uri.path?.startsWith("/login") == true &&
                    uri.query.isNullOrEmpty()
                ) {
                    // Сессия недействительна — открываем экран входа.
                    loginLauncher.launch(
                        Intent(this@MainActivity, LoginActivity::class.java)
                    )
                    return true
                }

                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)

                retryCount = 0
                pendingRetry = false
                hideErrorOverlay()

                injectSafeWebAudioBass(view)
                injectChromeHide(view)

                // setInitialScale — только стартовая подсказка. Реальный
                // зум применяем один раз относительно текущего масштаба,
                // чтобы 0.90 был абсолютным, а не мультипликативным.
                view.postDelayed({ applyTvZoom(view) }, 350)

                if (!splashHidden) {
                    hideSplash()
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    scheduleRetry(view)
                }
            }

            // Тот же обработчик для API 21–22.
            @Deprecated("Deprecated in Java")
            override fun onReceivedError(
                view: WebView,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                scheduleRetry(view)
            }

            override fun onSafeBrowsingHit(
                view: WebView,
                request: WebResourceRequest,
                threatType: Int,
                callback: SafeBrowsingResponse
            ) {
                Log.w(TAG, "Safe Browsing threat: $threatType ${request.url}")
                super.onSafeBrowsingHit(view, request, threatType, callback)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            override fun onShowCustomView(
                view: View,
                callback: CustomViewCallback
            ) {
                fullscreenView = view
                fullscreenCallback = callback

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
                exitFullscreenVideo()
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                // Камера/микрофон приложению не нужны — запросы отклоняются,
                // никакие ресурсы не грантятся автоматически.
                runOnUiThread {
                    runCatching { request.deny() }
                }
            }
        }

        webView.requestFocus()
    }

    /** Exit HTML5 fullscreen video, if it is currently shown. */
    private fun exitFullscreenVideo() {
        fullscreenView?.let {
            binding.fullscreenContainer.removeView(it)
        }
        fullscreenView = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        binding.fullscreenContainer.visibility = View.GONE
    }

    private fun scheduleRetry(view: WebView) {
        if (pendingRetry) return

        if (retryCount >= MAX_RETRIES) {
            showErrorOverlay(getString(R.string.error_no_connection_final))
            return
        }

        showErrorOverlay(getString(R.string.error_no_connection))

        pendingRetry = true

        val delay = BASE_RETRY_DELAY_MS * 2.0.pow(retryCount).toLong()
        retryCount++

        mainHandler.postDelayed({
            pendingRetry = false
            view.loadUrl(TIKTOK_URL)
        }, delay)
    }

    private fun showErrorOverlay(message: String) {
        binding.errorText.text = message
        binding.errorContainer.visibility = View.VISIBLE
    }

    private fun hideErrorOverlay() {
        binding.errorContainer.visibility = View.GONE
    }

    /** Ручной повтор, когда показан финальный оверлей «Press OK to retry». */
    private fun retryNow() {
        retryCount = 0
        pendingRetry = false
        hideErrorOverlay()
        binding.webView.loadUrl(TIKTOK_URL)
    }

    private fun applyTvZoom(webView: WebView) {
        val target = TV_SCALE

        try {
            webView.settings.setSupportZoom(true)
            webView.settings.builtInZoomControls = true
            webView.settings.displayZoomControls = false
            webView.settings.loadWithOverviewMode = false
            webView.settings.useWideViewPort = true

            webView.setInitialScale(DESKTOP_SCALE)

            webView.postDelayed({
                val afterInitial = webView.scale
                if (afterInitial > 0.01f && abs(afterInitial - target) > 0.02f) {
                    val factor = target / afterInitial
                    if (factor in 0.5f..2.0f && abs(factor - 1f) > 0.01f) {
                        webView.zoomBy(factor)
                        Log.d(TAG, "Fallback page zoom applied, factor=$factor")
                    }
                }
            }, 250)
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to apply WebView page zoom", t)
        }
    }

    /**
     * Bass boost via Web Audio.
     *
     * Perf note: TikTok's feed preloads several adjacent <video> elements
     * at once. Earlier this attached a Web Audio graph (one real-time
     * AudioContext each) to *every* video with a src as soon as it was
     * found, which meant multiple concurrent AudioContexts running for
     * off-screen/paused videos — a plausible source of the reported
     * stutter/frame drops on weaker TV boxes. Now a video is only wired
     * up once it actually starts playing, and its context is suspended
     * (not just left running) the moment it pauses, so at most one
     * AudioContext is normally alive at a time.
     */
    private fun injectSafeWebAudioBass(webView: WebView) {
        val js = """
            (() => {
              if (window.__fixtokBassInstalled) return;
              window.__fixtokBassInstalled = true;

              const BASS_DB = 8;
              const contexts = new Map();
              const wired = new WeakSet();
              let scanScheduled = false;

              function sameOrigin(src) {
                try { return new URL(src, location.href).origin === location.origin; }
                catch (_) { return false; }
              }

              function connect(el) {
                if (contexts.has(el)) return;

                // Do NOT connect media that has no CORS permission.
                // MediaElementSource would otherwise silence cross-origin audio.
                const corsOK = el.crossOrigin === 'anonymous' ||
                               el.crossOrigin === 'use-credentials' ||
                               sameOrigin(el.currentSrc || '');
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
                  contexts.set(el, ctx);
                } catch (_) {
                  // Never interfere with normal playback if Web Audio is rejected.
                }
              }

              function detach(el) {
                const ctx = contexts.get(el);
                if (!ctx) return;
                try { ctx.close(); } catch (_) {}
                contexts.delete(el);
              }

              function suspend(el) {
                const ctx = contexts.get(el);
                if (ctx) { try { ctx.suspend(); } catch (_) {} }
              }

              function resume(el) {
                connect(el); // no-op if already connected
                const ctx = contexts.get(el);
                if (ctx) { try { ctx.resume(); } catch (_) {} }
              }

              function wire(el) {
                if (!el || wired.has(el)) return;
                wired.add(el);
                // Only build the audio graph once playback actually starts,
                // and only for the element that is actually playing —
                // never for preloaded/off-screen siblings.
                el.addEventListener('playing', () => resume(el), {passive:true});
                el.addEventListener('pause', () => suspend(el), {passive:true});
                el.addEventListener('emptied', () => detach(el), {passive:true});
                if (!el.paused && el.currentSrc) resume(el);
              }

              function scan() {
                document.querySelectorAll('video,audio').forEach(wire);
                contexts.forEach((_, el) => { if (!el.isConnected) detach(el); });
              }

              function scheduleScan() {
                if (scanScheduled) return;
                scanScheduled = true;
                setTimeout(() => { scanScheduled = false; scan(); }, 1000);
              }

              scan();
              new MutationObserver(scheduleScan)
                  .observe(document.documentElement, {childList:true, subtree:true});
              setInterval(scheduleScan, 15000);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /**
     * Best-effort visual cleanup of TikTok's own chrome (site header,
     * sidebar navigation, footer, cookie/app-install banners) so the
     * WebView reads as "the video, inside FixTok" rather than "the
     * tiktok.com website". This only hides elements with CSS — it does
     * not touch TikTok's markup, scripts, or any private/internal API,
     * and it never hides the player itself.
     *
     * TikTok's own class names are build-generated and can change at any
     * time, so this targets semantic/ARIA landmarks (<header>, <footer>,
     * role="navigation", role="banner") plus a couple of well-known,
     * publicly-documented cookie-banner ids. Coverage is therefore
     * best-effort, not guaranteed — see the final report.
     */
    private fun injectChromeHide(webView: WebView) {
        // Single-quoted attribute selectors only: this string gets embedded
        // inside a double-quoted JS string literal below, so it must not
        // contain any unescaped double quotes.
        val css = """
            header, footer,
            [role='navigation'], [role='banner'],
            #tiktok-cookie-banner, #header-more-menu,
            [data-e2e='nav-more-container'], [data-e2e='recommend-account'],
            [data-e2e='video-side-action-bar-download'] {
              display: none !important;
            }
        """.trimIndent().replace("\n", " ")

        val js = """
            (() => {
              if (window.__fixtokChromeHideInstalled) return;
              window.__fixtokChromeHideInstalled = true;

              function ensureStyle() {
                if (document.getElementById('__fixtok_hide_chrome')) return;
                const style = document.createElement('style');
                style.id = '__fixtok_hide_chrome';
                style.textContent = "$css";
                document.head.appendChild(style);
              }

              ensureStyle();
              // TikTok is a single-page app; re-check occasionally in case
              // the <head> is ever rebuilt. Cheap: only touches the DOM
              // when the style tag is actually missing.
              new MutationObserver(ensureStyle)
                  .observe(document.documentElement, {childList: true, subtree: false});
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    // Briefly reveal the control-hint bar, then let it
                    // auto-hide again; does not consume the event.
                    scheduleBottomBarAutoHide()
                }

                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    // OK/Enter должен доходить до WebView — он кликает
                    // сфокусированный элемент страницы. Перехватываем
                    // только когда показан финальный оверлей ошибки.
                    if (retryCount >= MAX_RETRIES &&
                        binding.errorContainer.visibility == View.VISIBLE
                    ) {
                        if (event.repeatCount == 0) {
                            retryNow()
                        }
                        return true
                    }
                    return super.dispatchKeyEvent(event)
                }

                KeyEvent.KEYCODE_BACK -> {
                    if (fullscreenView != null) {
                        // HTML5 fullscreen: Back сначала выходит из видео.
                        exitFullscreenVideo()
                    } else if (binding.webView.canGoBack()) {
                        binding.webView.goBack()
                    } else {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastBackPressTime <= EXIT_CONFIRM_WINDOW_MS) {
                            finish()
                        } else {
                            lastBackPressTime = now
                            Toast.makeText(
                                this,
                                R.string.press_back_again_to_exit,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    return true
                }

                KeyEvent.KEYCODE_MENU -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    return true
                }
            }
        }

        return super.dispatchKeyEvent(event)
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

        loadPreferences()
        updateAccountStatus()

        binding.webView.onResume()
        binding.webView.resumeTimers()

        if (bassEnabled && tvAudio == null) {
            tvAudio = TvAudioController()
        }
        if (bassEnabled) {
            tvAudio?.enable()
            tvAudio?.setBass(BASS_STRENGTH)
        }
    }

    override fun onPause() {
        binding.webView.onPause()
        binding.webView.pauseTimers()
        tvAudio?.disable()
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        exitFullscreenVideo()

        tvAudio?.release()
        tvAudio = null

        binding.webView.stopLoading()
        binding.webView.removeAllViews()
        binding.webView.destroy()

        super.onDestroy()
    }
}
