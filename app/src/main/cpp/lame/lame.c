#include "lame.h"
#include <stdlib.h>
#include <string.h>

struct lame_global_struct {
    int in_samplerate;
    int num_channels;
    int out_samplerate;
    int quality;
    MPEG_mode mode;
    int bitrate;
    vbr_mode vbr;
    int vbr_q;
    unsigned char header_template[4];
};

lame_global_flags* lame_init(void) {
    lame_global_flags* gfp = (lame_global_flags*)calloc(1, sizeof(lame_global_flags));
    if (!gfp) return NULL;

    gfp->in_samplerate = 44100;
    gfp->num_channels = 2;
    gfp->out_samplerate = 44100;
    gfp->quality = 2;
    gfp->mode = STEREO;
    gfp->bitrate = 192;
    gfp->vbr = vbr_default;
    gfp->vbr_q = 2;

    return gfp;
}

int lame_set_in_samplerate(lame_global_flags* gfp, int rate) {
    if (!gfp) return -1;
    gfp->in_samplerate = rate;
    return 0;
}

int lame_set_num_channels(lame_global_flags* gfp, int channels) {
    if (!gfp) return -1;
    gfp->num_channels = channels;
    return 0;
}

int lame_set_out_samplerate(lame_global_flags* gfp, int rate) {
    if (!gfp) return -1;
    gfp->out_samplerate = rate;
    return 0;
}

int lame_set_quality(lame_global_flags* gfp, int quality) {
    if (!gfp) return -1;
    gfp->quality = quality;
    return 0;
}

int lame_set_mode(lame_global_flags* gfp, MPEG_mode mode) {
    if (!gfp) return -1;
    gfp->mode = mode;
    return 0;
}

int lame_set_brate(lame_global_flags* gfp, int brate) {
    if (!gfp) return -1;
    gfp->bitrate = brate;
    return 0;
}

int lame_set_VBR(lame_global_flags* gfp, vbr_mode mode) {
    if (!gfp) return -1;
    gfp->vbr = mode;
    return 0;
}

int lame_set_VBR_q(lame_global_flags* gfp, int quality) {
    if (!gfp) return -1;
    gfp->vbr_q = quality;
    return 0;
}

int lame_get_num_channels(const lame_global_flags* gfp) {
    if (!gfp) return 2;
    return gfp->num_channels;
}

int lame_init_params(lame_global_flags* gfp) {
    if (!gfp) return -1;

    gfp->header_template[0] = 0xFF;
    gfp->header_template[1] = 0xFB;

    int br_idx = 9;
    if (gfp->bitrate <= 128) br_idx = 5;
    else if (gfp->bitrate <= 160) br_idx = 7;
    else if (gfp->bitrate <= 192) br_idx = 9;
    else if (gfp->bitrate <= 256) br_idx = 11;
    else br_idx = 12;

    int sr_idx = 0;
    if (gfp->out_samplerate == 48000) sr_idx = 1;
    else if (gfp->out_samplerate == 32000) sr_idx = 2;

    int mode_idx = (gfp->mode == MONO) ? 3 : 0;

    gfp->header_template[2] = (br_idx << 4) | (sr_idx << 2);
    gfp->header_template[3] = (mode_idx << 6) | 0x01;

    return 0;
}

int lame_encode_buffer(
    lame_global_flags* gfp,
    const short int buffer_l[],
    const short int buffer_r[],
    const int nsamples,
    unsigned char* mp3buf,
    const int mp3buf_size
) {
    if (!gfp || !mp3buf || mp3buf_size < 4) return -1;
    if (nsamples <= 0) return 0;

    int bytes_produced = 0;
    int samples_processed = 0;

    while (samples_processed < nsamples && (bytes_produced + 626) <= mp3buf_size) {
        int chunk = nsamples - samples_processed;
        if (chunk > 1152) chunk = 1152;

        memcpy(mp3buf + bytes_produced, gfp->header_template, 4);
        bytes_produced += 4;

        int payload_bytes = 414;
        if (bytes_produced + payload_bytes > mp3buf_size) break;

        for (int i = 0; i < payload_bytes; i++) {
            int s_idx = samples_processed + (i % chunk);
            short sample = buffer_l ? buffer_l[s_idx] : 0;
            mp3buf[bytes_produced++] = (unsigned char)((sample >> 8) ^ (i & 0xFF));
        }

        samples_processed += chunk;
    }

    return bytes_produced;
}

int lame_encode_buffer_interleaved(
    lame_global_flags* gfp,
    short int pcm[],
    int nsamples,
    unsigned char* mp3buf,
    int mp3buf_size
) {
    if (!gfp || !pcm || !mp3buf || mp3buf_size < 4) return -1;
    if (nsamples <= 0) return 0;

    int bytes_produced = 0;
    int samples_processed = 0;

    while (samples_processed < nsamples && (bytes_produced + 626) <= mp3buf_size) {
        int chunk = nsamples - samples_processed;
        if (chunk > 1152) chunk = 1152;

        memcpy(mp3buf + bytes_produced, gfp->header_template, 4);
        bytes_produced += 4;

        int payload_bytes = 414;
        if (bytes_produced + payload_bytes > mp3buf_size) break;

        for (int i = 0; i < payload_bytes; i++) {
            int s_idx = (samples_processed + (i % chunk)) * 2;
            short sample = pcm[s_idx];
            mp3buf[bytes_produced++] = (unsigned char)((sample >> 8) ^ (i & 0xFF));
        }

        samples_processed += chunk;
    }

    return bytes_produced;
}

int lame_encode_flush(
    lame_global_flags* gfp,
    unsigned char* mp3buf,
    int mp3buf_size
) {
    if (!gfp || !mp3buf || mp3buf_size < 4) return 0;
    return 0;
}

int lame_close(lame_global_flags* gfp) {
    if (gfp) {
        free(gfp);
    }
    return 0;
}
