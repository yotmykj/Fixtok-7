package com.fixtok.tv

import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.LoudnessEnhancer

/**
 * Best-effort TV output enhancement.
 *
 * WebView does not expose the media player's audio session, so the app uses
 * the global output session (0) when the device exposes a compatible effect.
 * Unsupported effects are simply ignored instead of breaking playback.
 */
class TvAudioController {

    private var bassBoost: BassBoost? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var enabled = false

    fun enable() {
        if (enabled) return

        createBassBoost()
        createLoudnessEnhancer()

        enabled = true
    }

    private fun hasEffect(type: String): Boolean {
        return try {
            val wanted = java.util.UUID.fromString(type)
            AudioEffect.queryEffects()?.any { it.type == wanted } == true
        } catch (_: Throwable) {
            false
        }
    }

    private fun createBassBoost() {
        try {
            bassBoost?.release()
            bassBoost = null

            // BassBoost UUID: 0634f220-2517-11cf-a5d6-28db04c10000
            if (!hasEffect("0634f220-2517-11cf-a5d6-28db04c10000")) return

            bassBoost = BassBoost(0, 0).apply {
                if (strengthSupported) {
                    setStrength(650.toShort())
                    enabled = true
                } else {
                    release()
                }
            }
        } catch (_: Throwable) {
            bassBoost = null
        }
    }

    private fun createLoudnessEnhancer() {
        try {
            loudnessEnhancer?.release()
            loudnessEnhancer = null

            // Do not boost loudness by default; bass boost alone is safer.
            loudnessEnhancer = LoudnessEnhancer(0).apply {
                setTargetGain(0)
                enabled = false
            }
        } catch (_: Throwable) {
            loudnessEnhancer = null
        }
    }

    fun disable() {
        enabled = false

        try {
            bassBoost?.enabled = false
        } catch (_: Throwable) {
        }

        try {
            loudnessEnhancer?.enabled = false
        } catch (_: Throwable) {
        }
    }

    fun setBass(strength: Int) {
        val value = strength.coerceIn(0, 1000).toShort()

        try {
            bassBoost?.let {
                if (it.strengthSupported) {
                    it.setStrength(value)
                    it.enabled = enabled && value > 0
                }
            }
        } catch (_: Throwable) {
        }
    }

    fun setGain(gainMb: Int) {
        val value = gainMb.coerceIn(-10000, 2000)

        try {
            loudnessEnhancer?.setTargetGain(value)
            loudnessEnhancer?.enabled = enabled && value != 0
        } catch (_: Throwable) {
        }
    }

    fun release() {
        try {
            bassBoost?.release()
        } catch (_: Throwable) {
        }

        try {
            loudnessEnhancer?.release()
        } catch (_: Throwable) {
        }

        bassBoost = null
        loudnessEnhancer = null
        enabled = false
    }
}
