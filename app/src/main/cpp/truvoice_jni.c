/* JNI bridge for the TruVoice engine (OpenTV portable C, tables embedded).
 *
 * One native handle per voice: TruVoiceEngine keeps a tvtts_synth per
 * voice index, created at 16 kHz (TVTTS_SR_16K — the engine's own third
 * rate, genuinely more bandwidth than the 11025 Hz desktop default).
 * A tvtts_synth is not thread safe; the Kotlin side serializes all calls
 * for a voice, and each voice has its own synth, so no two threads ever
 * share one.
 *
 * tvtts_speak_utf8 blocks until the utterance finishes, delivering 16-bit
 * mono samples and index marks through the callback in stream order. The
 * callback accumulates both; the finished utterance returns as a
 * SynthResult(ShortArray samples, IntArray markIds, IntArray markPos).
 *
 * Cancellation: nativeRequestAbort sets a flag the callback checks on
 * every event (returning non-zero aborts synthesis at once). The flag is
 * cleared on entry to every synthesize call.
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "tvtts.h"

/* Per-voice native state. Freed by nativeDestroy. */
typedef struct {
    tvtts_synth *s;
    volatile int abort;
} Handle;

typedef struct {
    int16_t *pcm;
    size_t len, cap;
    uint32_t *ids;
    uint32_t *pos;
    size_t mlen, mcap;
    volatile int *abort;
    int failed;
} Acc;

static int on_event(const tvtts_event *ev, void *user) {
    Acc *a = (Acc *)user;
    if (*a->abort) return 1;
    if (ev->type == TVTTS_AUDIO) {
        if (ev->count > 0 && ev->samples) {
            size_t need = a->len + ev->count;
            if (need > a->cap) {
                size_t cap = a->cap ? a->cap * 2 : 8192;
                while (cap < need) cap *= 2;
                int16_t *p = (int16_t *)realloc(a->pcm, cap * sizeof(int16_t));
                if (!p) { a->failed = 1; return 1; }
                a->pcm = p;
                a->cap = cap;
            }
            memcpy(a->pcm + a->len, ev->samples, ev->count * sizeof(int16_t));
            a->len += ev->count;
        }
    } else if (ev->type == TVTTS_MARK) {
        if (a->mlen == a->mcap) {
            size_t cap = a->mcap ? a->mcap * 2 : 64;
            uint32_t *ids = (uint32_t *)realloc(a->ids, cap * sizeof(uint32_t));
            uint32_t *pos = (uint32_t *)realloc(a->pos, cap * sizeof(uint32_t));
            if (!ids || !pos) {
                free(ids);
                free(pos);
                a->failed = 1;
                return 1;
            }
            a->ids = ids;
            a->pos = pos;
            a->mcap = cap;
        }
        a->ids[a->mlen] = ev->mark;
        a->pos[a->mlen] = ev->sample_pos;
        a->mlen++;
    }
    return 0;
}

/* Converts a jstring to UTF-8 bytes (proper handling of surrogate pairs;
 * GetStringUTFChars would give CESU-8). Rejects embedded NULs, which would
 * truncate silently at the C boundary. Returns malloc'd buffer, or NULL. */
static char *jstring_to_utf8(JNIEnv *env, jstring str, size_t *out_len) {
    if (!str) return NULL;
    jsize n = (*env)->GetStringLength(env, str);
    const jchar *ch = (*env)->GetStringChars(env, str, NULL);
    if (!ch) return NULL;
    /* Worst case: 4 bytes per char + terminator. */
    char *buf = (char *)malloc((size_t)n * 4 + 1);
    if (!buf) {
        (*env)->ReleaseStringChars(env, str, ch);
        return NULL;
    }
    size_t o = 0;
    for (jsize i = 0; i < n; i++) {
        uint32_t cp = ch[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < n &&
            ch[i + 1] >= 0xDC00 && ch[i + 1] <= 0xDFFF) {
            cp = 0x10000 + ((cp - 0xD800) << 10) + (ch[i + 1] - 0xDC00);
            i++;
        }
        if (cp == 0) { free(buf); (*env)->ReleaseStringChars(env, str, ch); return NULL; }
        if (cp < 0x80) {
            buf[o++] = (char)cp;
        } else if (cp < 0x800) {
            buf[o++] = (char)(0xC0 | (cp >> 6));
            buf[o++] = (char)(0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            buf[o++] = (char)(0xE0 | (cp >> 12));
            buf[o++] = (char)(0x80 | ((cp >> 6) & 0x3F));
            buf[o++] = (char)(0x80 | (cp & 0x3F));
        } else {
            buf[o++] = (char)(0xF0 | (cp >> 18));
            buf[o++] = (char)(0x80 | ((cp >> 12) & 0x3F));
            buf[o++] = (char)(0x80 | ((cp >> 6) & 0x3F));
            buf[o++] = (char)(0x80 | (cp & 0x3F));
        }
    }
    buf[o] = '\0';
    (*env)->ReleaseStringChars(env, str, ch);
    if (out_len) *out_len = o;
    return buf;
}

static char *jstring_to_cstr(JNIEnv *env, jstring str) {
    if (!str) return NULL;
    size_t len = 0;
    char *buf = jstring_to_utf8(env, str, &len);
    (void)len;
    return buf;
}

JNIEXPORT jlong JNICALL
Java_org_truevoicedroid_tts_TruVoiceNative_nativeCreateLang(
        JNIEnv *env, jclass cls, jstring lang, jint sampleRateHz) {
    (void)cls;
    char *cLang = jstring_to_cstr(env, lang);
    if (!cLang) return 0;
    tvtts_synth *s = tvtts_create_lang((uint32_t)sampleRateHz, cLang);
    free(cLang);
    if (!s) return 0;
    Handle *h = (Handle *)calloc(1, sizeof(Handle));
    if (!h) {
        tvtts_destroy(s);
        return 0;
    }
    h->s = s;
    return (jlong)(intptr_t)h;
}

JNIEXPORT void JNICALL
Java_org_truevoicedroid_tts_TruVoiceNative_nativeDestroy(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)env;
    (void)cls;
    Handle *h = (Handle *)(intptr_t)handle;
    if (!h) return;
    if (h->s) tvtts_destroy(h->s);
    free(h);
}

JNIEXPORT void JNICALL
Java_org_truevoicedroid_tts_TruVoiceNative_nativeSetVoice(
        JNIEnv *env, jclass cls, jlong handle, jint voice) {
    (void)env;
    (void)cls;
    Handle *h = (Handle *)(intptr_t)handle;
    if (h && h->s) tvtts_set_voice(h->s, voice);
}

JNIEXPORT void JNICALL
Java_org_truevoicedroid_tts_TruVoiceNative_nativeSetRate(
        JNIEnv *env, jclass cls, jlong handle, jint wpm) {
    (void)env;
    (void)cls;
    Handle *h = (Handle *)(intptr_t)handle;
    if (h && h->s) tvtts_set_rate(h->s, wpm);
}

JNIEXPORT void JNICALL
Java_org_truevoicedroid_tts_TruVoiceNative_nativeSetPitch(
        JNIEnv *env, jclass cls, jlong handle, jint pitch) {
    (void)env;
    (void)cls;
    Handle *h = (Handle *)(intptr_t)handle;
    if (h && h->s) tvtts_set_pitch(h->s, pitch);
}

JNIEXPORT jint JNICALL
Java_org_truevoicedroid_tts_TruVoiceNative_nativeAddLexicon(
        JNIEnv *env, jclass cls, jstring word, jstring phonemes) {
    (void)cls;
    char *w = jstring_to_cstr(env, word);
    char *p = jstring_to_cstr(env, phonemes);
    if (!w || !p) {
        free(w);
        free(p);
        return -1;
    }
    int rc = tvtts_add_lexicon(w, p);
    free(w);
    free(p);
    return rc;
}

JNIEXPORT jint JNICALL
Java_org_truevoicedroid_tts_TruVoiceNative_nativeVoiceCount(
        JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    return tvtts_voice_count();
}

JNIEXPORT jstring JNICALL
Java_org_truevoicedroid_tts_TruVoiceNative_nativeVoiceName(
        JNIEnv *env, jclass cls, jint voice) {
    (void)cls;
    const char *name = tvtts_voice_name(voice);
    if (!name) return NULL;
    return (*env)->NewStringUTF(env, name);
}

JNIEXPORT jint JNICALL
Java_org_truevoicedroid_tts_TruVoiceNative_nativeVoiceRate(
        JNIEnv *env, jclass cls, jint voice) {
    (void)env;
    (void)cls;
    return tvtts_voice_rate(voice);
}

JNIEXPORT jint JNICALL
Java_org_truevoicedroid_tts_TruVoiceNative_nativeVoicePitch(
        JNIEnv *env, jclass cls, jint voice) {
    (void)env;
    (void)cls;
    return tvtts_voice_pitch(voice);
}

JNIEXPORT jstring JNICALL
Java_org_truevoicedroid_tts_TruVoiceNative_nativeMarkSequence(
        JNIEnv *env, jclass cls, jint mark) {
    (void)cls;
    char buf[16];
    int n = tvtts_mark_sequence(buf, sizeof(buf), (uint32_t)mark);
    if (n <= 0) return NULL;
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jstring JNICALL
Java_org_truevoicedroid_tts_TruVoiceNative_nativeBreakSequence(
        JNIEnv *env, jclass cls, jint ms) {
    (void)cls;
    char buf[16];
    int n = tvtts_break_sequence(buf, sizeof(buf), (uint32_t)ms);
    if (n <= 0) return NULL;
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT void JNICALL
Java_org_truevoicedroid_tts_TruVoiceNative_nativeRequestAbort(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)env;
    (void)cls;
    Handle *h = (Handle *)(intptr_t)handle;
    if (h) h->abort = 1;
}

JNIEXPORT jobject JNICALL
Java_org_truevoicedroid_tts_TruVoiceNative_nativeSynthesize(
        JNIEnv *env, jclass cls, jlong handle, jstring text) {
    (void)cls;
    Handle *h = (Handle *)(intptr_t)handle;
    if (!h || !h->s) return NULL;
    size_t len = 0;
    char *utf8 = jstring_to_utf8(env, text, &len);
    if (!utf8 || len == 0) {
        free(utf8);
        return NULL;
    }

    Acc acc;
    memset(&acc, 0, sizeof(acc));
    acc.abort = &h->abort;
    h->abort = 0;

    int rc = tvtts_speak_utf8(h->s, utf8, on_event, &acc);
    free(utf8);

    jobject result = NULL;
    if (rc >= 0 && !acc.failed && acc.len > 0) {
        jshortArray samples = (*env)->NewShortArray(env, (jsize)acc.len);
        jintArray ids = (*env)->NewIntArray(env, (jsize)acc.mlen);
        jintArray pos = (*env)->NewIntArray(env, (jsize)acc.mlen);
        if (samples && ids && pos) {
            (*env)->SetShortArrayRegion(env, samples, 0, (jsize)acc.len, acc.pcm);
            /* Narrowing uint32 marks to int; ids and positions are small. */
            for (size_t i = 0; i < acc.mlen; i++) {
                jint id = (jint)acc.ids[i];
                jint p = (jint)acc.pos[i];
                (*env)->SetIntArrayRegion(env, ids, (jsize)i, 1, &id);
                (*env)->SetIntArrayRegion(env, pos, (jsize)i, 1, &p);
            }
            jclass resultCls = (*env)->FindClass(
                env, "org/truevoicedroid/tts/SynthResult");
            if (resultCls) {
                jmethodID ctor = (*env)->GetMethodID(
                    env, resultCls, "<init>", "([S[I[I)V");
                if (ctor) {
                    result = (*env)->NewObject(
                        env, resultCls, ctor, samples, ids, pos);
                }
            }
        }
    }
    free(acc.pcm);
    free(acc.ids);
    free(acc.pos);
    return result;
}
