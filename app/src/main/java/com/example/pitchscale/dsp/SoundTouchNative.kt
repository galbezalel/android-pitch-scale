package com.example.pitchscale.dsp

class SoundTouchNative {
    init {
        System.loadLibrary("soundtouchjni")
    }

    private var handle: Long = createInstance()

    private external fun createInstance(): Long
    private external fun destroyInstance(handle: Long)
    private external fun setSampleRate(handle: Long, sampleRate: Int)
    private external fun setChannels(handle: Long, numChannels: Int)
    private external fun setPitchSemiTones(handle: Long, pitch: Float)
    private external fun putSamples(handle: Long, samples: ShortArray, numSamples: Int)
    private external fun receiveSamples(handle: Long, outBuffer: ShortArray, maxSamples: Int): Int
    private external fun flush(handle: Long)

    fun setSampleRate(sampleRate: Int) = setSampleRate(handle, sampleRate)
    fun setChannels(numChannels: Int) = setChannels(handle, numChannels)
    fun setPitchSemiTones(pitch: Float) = setPitchSemiTones(handle, pitch)
    
    fun process(input: ShortArray, length: Int, output: ShortArray): Int {
        putSamples(handle, input, length)
        return receiveSamples(handle, output, output.size)
    }

    fun flush(output: ShortArray): Int {
        flush(handle)
        return receiveSamples(handle, output, output.size)
    }

    fun dispose() {
        if (handle != 0L) {
            destroyInstance(handle)
            handle = 0L
        }
    }
}
