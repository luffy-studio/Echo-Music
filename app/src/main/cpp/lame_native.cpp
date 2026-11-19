#include <jni.h>
#include <cstdlib>
#include <cstring>
#include "lame/lame.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_echo_music_iad1tya_playback_LameNative_initEncoder(
    JNIEnv* env,
    jobject /* obj */,
    jint inSampleRate,
    jint numChannels,
    jint outSampleRate,
    jint bitrateKbps,
    jint quality
) {
    lame_global_flags* gfp = lame_init();
    if (!gfp) return 0;

    lame_set_in_samplerate(gfp, inSampleRate);
    lame_set_num_channels(gfp, numChannels);
    lame_set_out_samplerate(gfp, outSampleRate > 0 ? outSampleRate : inSampleRate);

    if (bitrateKbps > 0) {
        lame_set_brate(gfp, bitrateKbps);
        lame_set_VBR(gfp, vbr_off);
    } else {
        lame_set_VBR(gfp, vbr_default);
        lame_set_VBR_q(gfp, quality >= 0 ? quality : 2);
    }

    lame_set_mode(gfp, numChannels == 1 ? MONO : STEREO);
    lame_set_quality(gfp, quality >= 0 ? quality : 2);

    if (lame_init_params(gfp) < 0) {
        lame_close(gfp);
        return 0;
    }

    return reinterpret_cast<jlong>(gfp);
}

JNIEXPORT jint JNICALL
Java_echo_music_iad1tya_playback_LameNative_encodeBuffer(
    JNIEnv* env,
    jobject /* obj */,
    jlong handle,
    jshortArray pcmData,
    jint numSamples,
    jbyteArray mp3Data,
    jint mp3Capacity
) {
    lame_global_flags* gfp = reinterpret_cast<lame_global_flags*>(handle);
    if (!gfp || !pcmData || !mp3Data) return -1;

    jshort* pcmBuf = env->GetShortArrayElements(pcmData, nullptr);
    jbyte* mp3Buf = env->GetByteArrayElements(mp3Data, nullptr);

    int numChannels = lame_get_num_channels(gfp);
    int bytesEncoded = 0;

    if (numChannels == 1) {
        bytesEncoded = lame_encode_buffer(
            gfp,
            pcmBuf,
            pcmBuf,
            numSamples,
            reinterpret_cast<unsigned char*>(mp3Buf),
            mp3Capacity
        );
    } else {
        bytesEncoded = lame_encode_buffer_interleaved(
            gfp,
            pcmBuf,
            numSamples,
            reinterpret_cast<unsigned char*>(mp3Buf),
            mp3Capacity
        );
    }

    env->ReleaseShortArrayElements(pcmData, pcmBuf, JNI_ABORT);
    env->ReleaseByteArrayElements(mp3Data, mp3Buf, 0);

    return bytesEncoded;
}

JNIEXPORT jint JNICALL
Java_echo_music_iad1tya_playback_LameNative_flush(
    JNIEnv* env,
    jobject /* obj */,
    jlong handle,
    jbyteArray mp3Data,
    jint mp3Capacity
) {
    lame_global_flags* gfp = reinterpret_cast<lame_global_flags*>(handle);
    if (!gfp || !mp3Data) return -1;

    jbyte* mp3Buf = env->GetByteArrayElements(mp3Data, nullptr);

    int bytesEncoded = lame_encode_flush(
        gfp,
        reinterpret_cast<unsigned char*>(mp3Buf),
        mp3Capacity
    );

    env->ReleaseByteArrayElements(mp3Data, mp3Buf, 0);

    return bytesEncoded;
}

JNIEXPORT void JNICALL
Java_echo_music_iad1tya_playback_LameNative_closeEncoder(
    JNIEnv* env,
    jobject /* obj */,
    jlong handle
) {
    lame_global_flags* gfp = reinterpret_cast<lame_global_flags*>(handle);
    if (gfp) {
        lame_close(gfp);
    }
}

} // extern "C"
