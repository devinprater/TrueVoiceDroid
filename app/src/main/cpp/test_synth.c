/* Headless smoke test for the TruVoice engine on-device.
 * Usage: test_synth "text to speak" [lang] [voice] [wpm] [pitch]
 * Writes raw 16-bit mono 16kHz PCM to stdout; diagnostics to stderr.
 * Exit 0 when at least one nonzero sample was produced.
 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "tvtts.h"

typedef struct {
    int16_t *pcm;
    size_t len, cap;
    int marks;
    uint32_t ids[2048];
    uint32_t poss[2048];
    int silent;
} Acc;

static int on_event(const tvtts_event *ev, void *user) {
    Acc *a = (Acc *)user;
    if (ev->type == TVTTS_AUDIO) {
        if (ev->count > 0 && ev->samples) {
            if (a->len + ev->count > a->cap) {
                size_t cap = a->cap ? a->cap * 2 : 65536;
                while (cap < a->len + ev->count) cap *= 2;
                int16_t *p = (int16_t *)realloc(a->pcm, cap * sizeof(int16_t));
                if (!p) return 1;
                a->pcm = p;
                a->cap = cap;
            }
            memcpy(a->pcm + a->len, ev->samples, ev->count * sizeof(int16_t));
            a->len += ev->count;
        }
    } else if (ev->type == TVTTS_MARK) {
        if (a->marks < 2048) {
            a->ids[a->marks] = ev->mark;
            a->poss[a->marks] = ev->sample_pos;
        }
        a->marks++;
    }
    return 0;
}

int main(int argc, char **argv) {
    const char *text = argc > 1 ? argv[1] : "Hello world. This is TruVoice speaking.";
    const char *lang = argc > 2 ? argv[2] : "en";
    int voice = argc > 3 ? atoi(argv[3]) : (lang[0] == 'e' ? 0 : 10);
    int wpm = argc > 4 ? atoi(argv[4]) : 0;
    int pitch = argc > 5 ? atoi(argv[5]) : 0;

    tvtts_synth *s = tvtts_create_lang(16000, lang);
    if (!s) {
        fprintf(stderr, "FAIL create_lang(%s)\n", lang);
        return 2;
    }
    tvtts_set_voice(s, voice);
    if (wpm > 0) tvtts_set_rate(s, wpm);
    if (pitch > 0) tvtts_set_pitch(s, pitch);

    /* A couple of the shipped lexicon fixes, as the app installs them. */
    tvtts_add_lexicon("Devin", "De1V|N");
    tvtts_add_lexicon("repo", "RE1PO");

    /* Word marks: one escape before each word, ids 1..N. */
    char marked[65536];
    size_t o = 0;
    char tmp[65536];
    strncpy(tmp, text, sizeof(tmp) - 1);
    tmp[sizeof(tmp) - 1] = '\0';
    int id = 0;
    char *tok = strtok(tmp, " ");
    while (tok && o + 64 < sizeof(marked)) {
        char esc[16];
        if (tvtts_mark_sequence(esc, sizeof(esc), (uint32_t)++id) > 0) {
            size_t n = strlen(esc);
            memcpy(marked + o, esc, n);
            o += n;
        }
        marked[o++] = ' ';
        size_t n = strlen(tok);
        if (o + n + 2 >= sizeof(marked)) break;
        memcpy(marked + o, tok, n);
        o += n;
        tok = strtok(NULL, " ");
    }
    marked[o] = '\0';

    Acc acc;
    memset(&acc, 0, sizeof(acc));
    int rc = tvtts_speak_utf8(s, marked, on_event, &acc);
    tvtts_destroy(s);

    size_t nonzero = 0;
    for (size_t i = 0; i < acc.len; i++)
        if (acc.pcm[i] != 0) nonzero++;

    fprintf(stderr, "rc=%d samples=%zu nonzero=%zu marks=%d words=%d\n",
            rc, acc.len, nonzero, acc.marks, id);
    {
        int i, n = acc.marks < 2048 ? acc.marks : 2048;
        fprintf(stderr, "markids:");
        for (i = 0; i < n && i < 40; i++)
            fprintf(stderr, " %u@%u", acc.ids[i], acc.poss[i]);
        fprintf(stderr, "%s\n", n > 40 ? " ..." : "");
    }

    if (rc < 0 || acc.len == 0 || nonzero == 0) {
        free(acc.pcm);
        fprintf(stderr, "FAIL no speech\n");
        return 1;
    }
    /* PCM to stdout. */
    fwrite(acc.pcm, sizeof(int16_t), acc.len, stdout);
    free(acc.pcm);
    fprintf(stderr, "PASS\n");
    return 0;
}
