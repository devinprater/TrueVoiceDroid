package org.truevoicedroid.tts;

/**
 * The TruVoice engine, in a process of its own.
 *
 * The engine keeps state between calls -- a synth handle per voice, the
 * lexicon, the output sample rate -- so the worker owns all of it and the
 * TTS service owns none.  A call that will not return is cleared by the
 * owner killing this process, never by interrupting the thread inside it.
 */
interface ITruVoiceWorker {
    /**
     * Synthesizes one chunk.  Returns a request id; if the previous request
     * is still running it waits up to [waitMs], then answers BUSY.
     */
    int say(int voice, int wpm, int pitch, String text, int waitMs);

    /**
     * The next block of little-endian 16-bit samples, or null when the
     * request is finished, cancelled, unknown, or [waitMs] passed with
     * nothing ready.
     */
    byte[] pull(int requestId, int maxBytes, int waitMs);

    /** Word marks for a finished request, as id, position, id, position... */
    int[] marks(int requestId);

    /** True once [requestId] has completed, or the engine failed. */
    boolean finished(int requestId);

    /** Asks the synthesis thread to stop between steps.  Never interrupts it. */
    void cancel(int requestId);

    /** Kills this worker process.  Only the owner may call this. */
    void shutdown();

    /** Sets the output rate; returns the rate in force, or 0 on error. */
    int setSampleRate(int hz);

    /** The output rate in force. */
    int sampleRate();

    /** Engine default words per minute for a voice. */
    int defaultRate(int voice);

    /** Engine default pitch for a voice. */
    int defaultPitch(int voice);

    /** The user lexicon, as key, say, key, say... */
    String[] dictionary();

    /** Engine mark escapes for ids 1..255, indexed from zero. */
    String[] markSequences();
}
