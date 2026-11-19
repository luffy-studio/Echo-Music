#ifndef LAME_H
#define LAME_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum MPEG_mode_e {
  STEREO = 0,
  JOINT_STEREO,
  DUAL_CHANNEL,
  MONO,
  NOT_SET
} MPEG_mode;

typedef enum vbr_mode_e {
  vbr_off = 0,
  vbr_mt,
  vbr_rh,
  vbr_abr,
  vbr_ltr,
  vbr_default
} vbr_mode;

typedef struct lame_global_struct lame_global_flags;

lame_global_flags* lame_init(void);
int lame_set_in_samplerate(lame_global_flags* gfp, int rate);
int lame_set_num_channels(lame_global_flags* gfp, int channels);
int lame_set_out_samplerate(lame_global_flags* gfp, int rate);
int lame_set_quality(lame_global_flags* gfp, int quality);
int lame_set_mode(lame_global_flags* gfp, MPEG_mode mode);
int lame_set_brate(lame_global_flags* gfp, int brate);
int lame_set_VBR(lame_global_flags* gfp, vbr_mode mode);
int lame_set_VBR_q(lame_global_flags* gfp, int quality);
int lame_init_params(lame_global_flags* gfp);
int lame_get_num_channels(const lame_global_flags* gfp);

int lame_encode_buffer(
    lame_global_flags* gfp,
    const short int buffer_l[],
    const short int buffer_r[],
    const int nsamples,
    unsigned char* mp3buf,
    const int mp3buf_size);

int lame_encode_buffer_interleaved(
    lame_global_flags* gfp,
    short int pcm[],
    int nsamples,
    unsigned char* mp3buf,
    int mp3buf_size);

int lame_encode_flush(
    lame_global_flags* gfp,
    unsigned char* mp3buf,
    int mp3buf_size);

int lame_close(lame_global_flags* gfp);

#ifdef __cplusplus
}
#endif

#endif // LAME_H
