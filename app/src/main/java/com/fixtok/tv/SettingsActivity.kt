package com.fixtok.tv

import android.os.Bundle
import android.webkit.CookieManager
import androidx.appcompat.app.AppCompatActivity
import com.fixtok.tv.databinding.ActivitySettingsBinding

/**
 * TV-friendly настройки. Всё управляется D-pad: элементы focusable,
 * видимое focused-состояние через @drawable/focusable_bg.
 *
 * RESULT_OK возвращается в MainActivity, если сессия изменилась
 * (вход выполнен или logout) — тогда лента перезагружается.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "fixtok_settings"
        const val KEY_BASS = "bass_enabled"
    }

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK

        // ── Bass boost ────────────────────────────────────────────────

        binding.settingsBass.isChecked =
            prefs.getBoolean(KEY_BASS, true)

        binding.settingsBass.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_BASS, isChecked).apply()
        }

        // ── Аккаунт ───────────────────────────────────────────────────

        updateAccountStatus()

        binding.settingsLogin.setOnClickListener {
            setResult(RESULT_OK) // сессия может измениться
            startActivity(android.content.Intent(this, LoginActivity::class.java))
        }

        binding.settingsLogout.setOnClickListener {
            logout()
        }

        binding.settingsDone.setOnClickListener {
            finish()
        }
    }

    private fun updateAccountStatus() {
        val loggedIn =
            LoginActivity.hasSessionCookie(CookieManager.getInstance())
        binding.settingsAccountStatus.text = getString(
            if (loggedIn) R.string.account_status_logged_in
            else R.string.account_status_logged_out
        )
    }

    /**
     * Logout: удаляются только cookie TikTok (сторонние данные приложение
     * вообще не хранит), UI обновляется, результат возвращается в
     * MainActivity для перезагрузки ленты в неавторизованном состоянии.
     */
    private fun logout() {
        val manager = CookieManager.getInstance()
        val cookies = manager.getCookie("https://www.tiktok.com") ?: ""

        val remaining = cookies.split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { part ->
                val name = part.substringBefore("=")
                name == "sessionid" || name == "sessionid_ss" ||
                    name == "sid_tt" || name == "sid_guard"
            }
            .joinToString("; ")

        // Удаляем session-cookie TikTok, остальное сохраняем.
        manager.setCookie("https://www.tiktok.com", remaining)

        setResult(RESULT_OK)
        updateAccountStatus()
    }

    override fun onResume() {
        super.onResume()
        // Вернулся из LoginActivity — обновить статус.
        updateAccountStatus()
    }
}
