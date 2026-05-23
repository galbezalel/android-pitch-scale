package com.example.pitchscale.dsp

import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.abs

class PitchShifter(
    val sampleRate: Float = 44100.0f
) {
    private val bufferSize = 16384
    private val delayBuffer = FloatArray(bufferSize)
    private var writeIdx = 0

    private val delayRange = 1024.0f
    private val minDelay = 64.0f
    private var phasor = 0.0f

    @Volatile
    var pitchShiftRatio: Float = 1.0f

    /**
     * Process input audio samples using a classic phasor-based two-tap delay line pitch shifter.
     * Returns the number of processed samples (equal to length).
     */
    fun process(input: ShortArray, length: Int, output: ShortArray): Int {
        val ratio = pitchShiftRatio
        val df = (1.0f - ratio) / delayRange

        for (i in 0 until length) {
            // Write input sample to circular buffer (normalized float)
            val inVal = input[i].toFloat() / 32768.0f
            delayBuffer[writeIdx] = inVal

            // Tap A phase, delay, read index, window
            val phaseA = phasor
            val delayA = minDelay + phaseA * delayRange
            var rawPosA = writeIdx - delayA
            while (rawPosA < 0.0f) {
                rawPosA += bufferSize
            }
            val posAIndex = rawPosA % bufferSize
            val valA = interpolate(posAIndex)
            val winA = 0.5f * (1.0f - cos(2.0f * PI.toFloat() * phaseA))

            // Tap B phase (180 deg offset), delay, read index, window
            val phaseB = (phasor + 0.5f) % 1.0f
            val delayB = minDelay + phaseB * delayRange
            var rawPosB = writeIdx - delayB
            while (rawPosB < 0.0f) {
                rawPosB += bufferSize
            }
            val posBIndex = rawPosB % bufferSize
            val valB = interpolate(posBIndex)
            val winB = 0.5f * (1.0f - cos(2.0f * PI.toFloat() * phaseB))

            // Mix/Cross-fade the taps and apply a soft clipper to prevent digital peaking
            val mixedVal = (valA * winA + valB * winB) * 1.5f
            val outVal = mixedVal / (1.0f + abs(mixedVal))

            // Convert back to 16-bit short PCM
            output[i] = (outVal * 32768.0f).coerceIn(-32768.0f, 32767.0f).toInt().toShort()

            // Advance write pointer
            writeIdx = (writeIdx + 1) % bufferSize

            // Advance phasor with wrapping
            phasor = (phasor + df) % 1.0f
            if (phasor < 0.0f) {
                phasor += 1.0f
            }
        }

        return length
    }

    fun flush(output: ShortArray): Int {
        // Phasor-based delay line shifter has constant delay and doesn't require flushing
        return 0
    }

    fun dispose() {
        // Nothing to dispose in pure Kotlin
    }

    private fun interpolate(pos: Float): Float {
        val idx = pos.toInt()
        val nextIdx = (idx + 1) % bufferSize
        val frac = pos - idx
        return delayBuffer[idx] * (1.0f - frac) + delayBuffer[nextIdx] * frac
    }
}
