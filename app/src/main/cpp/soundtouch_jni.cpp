#include <jni.h>
#include "SoundTouch.h"

using namespace soundtouch;

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_pitchscale_dsp_SoundTouchNative_createInstance(JNIEnv *env, jobject thiz) {
    return reinterpret_cast<jlong>(new SoundTouch());
}

JNIEXPORT void JNICALL
Java_com_example_pitchscale_dsp_SoundTouchNative_destroyInstance(JNIEnv *env, jobject thiz, jlong handle) {
    auto *st = reinterpret_cast<SoundTouch *>(handle);
    delete st;
}

JNIEXPORT void JNICALL
Java_com_example_pitchscale_dsp_SoundTouchNative_setSampleRate(JNIEnv *env, jobject thiz, jlong handle, jint sampleRate) {
    auto *st = reinterpret_cast<SoundTouch *>(handle);
    st->setSampleRate(sampleRate);
}

JNIEXPORT void JNICALL
Java_com_example_pitchscale_dsp_SoundTouchNative_setChannels(JNIEnv *env, jobject thiz, jlong handle, jint numChannels) {
    auto *st = reinterpret_cast<SoundTouch *>(handle);
    st->setChannels(numChannels);
}

JNIEXPORT void JNICALL
Java_com_example_pitchscale_dsp_SoundTouchNative_setPitchSemiTones(JNIEnv *env, jobject thiz, jlong handle, jfloat pitch) {
    auto *st = reinterpret_cast<SoundTouch *>(handle);
    st->setPitchSemiTones(pitch);
}

JNIEXPORT void JNICALL
Java_com_example_pitchscale_dsp_SoundTouchNative_putSamples(JNIEnv *env, jobject thiz, jlong handle, jshortArray samples, jint numSamples) {
    auto *st = reinterpret_cast<SoundTouch *>(handle);
    jshort *c_samples = env->GetShortArrayElements(samples, nullptr);
    st->putSamples(c_samples, numSamples);
    env->ReleaseShortArrayElements(samples, c_samples, JNI_ABORT);
}

JNIEXPORT jint JNICALL
Java_com_example_pitchscale_dsp_SoundTouchNative_receiveSamples(JNIEnv *env, jobject thiz, jlong handle, jshortArray outBuffer, jint maxSamples) {
    auto *st = reinterpret_cast<SoundTouch *>(handle);
    jshort *c_outBuffer = env->GetShortArrayElements(outBuffer, nullptr);
    int numReceived = st->receiveSamples(c_outBuffer, maxSamples);
    env->ReleaseShortArrayElements(outBuffer, c_outBuffer, 0);
    return numReceived;
}

JNIEXPORT void JNICALL
Java_com_example_pitchscale_dsp_SoundTouchNative_flush(JNIEnv *env, jobject thiz, jlong handle) {
    auto *st = reinterpret_cast<SoundTouch *>(handle);
    st->flush();
}

}
