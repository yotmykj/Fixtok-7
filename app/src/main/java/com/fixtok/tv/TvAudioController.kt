package com.fixtok.tv

import android.media.audiofx.BassBoost
import android.media.audiofx.LoudnessEnhancer

class TvAudioController {

    private var bassBoost: BassBoost? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    private var enabled = false

    fun enable() {
        if (enabled) return

        enabled = true

        createBassBoost()
        createLoudnessEnhancer()
    }

    private fun createBassBoost() {
        try {
            bassBoost?.release()

            bassBoost = BassBoost(
                0,
                0
            ).apply {

                if (strengthSupported) {
                    setStrength(1000.toShort())
                }

                enabled = true
            }

        } catch (_: Throwable) {
            bassBoost = null
        }
    }

    private fun createLoudnessEnhancer() {
        try {
            loudnessEnhancer?.release()

            loudnessEnhancer = LoudnessEnhancer(0).apply {

                // +12 dB
                setTargetGain(1200)

                enabled = true
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

        val value = strength
            .coerceIn(0, 1000)
            .toShort()

        try {
            bassBoost?.let {
                if (it.strengthSupported) {
                    it.setStrength(value)
                }
            }
        } catch (_: Throwable) {
        }
    }

    fun setGain(gainMb: Int) {

        val value = gainMb.coerceIn(
            -10000,
            2000
        )

        try {
            loudnessEnhancer?.setTargetGain(value)
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
