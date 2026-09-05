package com.fixtok.tv

import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.util.Log

/**
 * Best-effort system output bass for WebView/Chromium audio. In-page Web Audio is handled separately by MainActivity.
 *
 * Important: WebView does not expose its internal media audio-session ID via
 * the public SDK. Session 0 therefore targets the device/output mix on
 * implementations that support it. Some TV firmwares simply ignore effects
 * on session 0; the diagnostics below make that visible in logcat.
 */
class TvAudioController {

    companion object {
        private const val TAG = "FixTokAudio"
        private const val AUDIO_SESSION_GLOBAL = 0
    }

    private var bassBoost: BassBoost? = null
    private var equalizer: Equalizer? = null
    private var enabled = false

    fun enable() {
        if (enabled) return
        enabled = true

        logAvailableEffects()
        createBassBoost()

        // If BassBoost is unsupported, try a real low-frequency EQ instead.
        if (bassBoost == null) {
            createLowEq()
        }
    }

    private fun logAvailableEffects() {
        try {
            val effects = AudioEffect.queryEffects()
            Log.d(TAG, "Available audio effects: ${effects?.size ?: 0}")
            effects?.forEach { d ->
                Log.d(TAG, "effect type=${d.type} name=${d.name} implementor=${d.implementor}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot query audio effects", t)
        }
    }

    private fun createBassBoost() {
        try {
            bassBoost?.release()
            bassBoost = BassBoost(0, AUDIO_SESSION_GLOBAL).apply {
                Log.d(TAG, "BassBoost created; strengthSupported=$strengthSupported")
                if (!strengthSupported) {
                    release()
                    return
                }
                setStrength(1000.toShort())
                enabled = true
                Log.d(TAG, "BassBoost enabled at 1000/1000 on session 0")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "BassBoost unavailable/failed", t)
            bassBoost = null
        }
    }

    private fun createLowEq() {
        try {
            equalizer?.release()
            equalizer = Equalizer(0, AUDIO_SESSION_GLOBAL).apply {
                val bands = numberOfBands.toInt()
                Log.d(TAG, "Equalizer fallback created; bands=$bands range=${bandLevelRange.contentToString()}")

                // Boost the lowest available bands, without changing the rest.
                for (i in 0 until bands) {
                    val band = i.toShort()
                    val hz = getCenterFreq(band) / 1000
                    if (hz <= 250) {
                        val max = bandLevelRange[1].toInt()
                        val boost = minOf(120, max)
                        setBandLevel(band, boost.toShort())
                        Log.d(TAG, "EQ band=$i freq=${hz}Hz level=${boost}mB")
                    }
                }
                enabled = true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Equalizer fallback unavailable/failed", t)
            equalizer = null
        }
    }

    fun disable() {
        enabled = false

        try { bassBoost?.enabled = false } catch (_: Throwable) {}
        try { equalizer?.enabled = false } catch (_: Throwable) {}
    }

    fun setBass(strength: Int) {
        val value = strength.coerceIn(0, 1000).toShort()
        try {
            bassBoost?.let {
                if (it.strengthSupported) {
                    it.setStrength(value)
                    if (!it.enabled) it.enabled = true
                    Log.d(TAG, "BassBoost strength=$value")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "setBass failed", t)
        }
    }

    /** Kept for API compatibility; bass mode does not add loudness/gain. */
    fun setGain(gainMb: Int) {
        Log.d(TAG, "Output gain request ignored: $gainMb mB (avoids clipping)")
    }

    fun release() {
        try { bassBoost?.release() } catch (_: Throwable) {}
        try { equalizer?.release() } catch (_: Throwable) {}

        bassBoost = null
        equalizer = null
        enabled = false
    }
}
