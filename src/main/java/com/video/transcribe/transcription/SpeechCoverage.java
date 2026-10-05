package com.video.transcribe.transcription;

import com.video.transcribe.model.TranscriptData;

/**
 * Production gate: a lesson whose transcript is far too short for its length has no usable narration (music-only or silent video,
 * or ASR that only produced a hallucinated "thanks for watching"). Building a storyboard from it would give a fake video that passes every
 * later check, so the run stops here with a clear reason.
 */
public final class SpeechCoverage {

    /** Real teaching narration runs about 100-160 words per minute; anything below this is not a spoken lesson. */
    static final double MIN_WORDS_PER_MINUTE = 12.0;
    static final int MIN_WORDS = 10;
    /** Clips shorter than this are exempt (a short intro can legitimately be a few words). */
    static final double MIN_CHECKED_SECONDS = 30.0;

    private SpeechCoverage() {
    }

    /** @return null when the transcript is long enough for the video, otherwise the reason it is not. */
    public static String problem(TranscriptData transcript, double videoSeconds) {
        String text = transcript == null ? null : transcript.getFullText();
        return problem(text, videoSeconds > 0 ? videoSeconds : (transcript == null ? 0 : transcript.getDuration()));
    }

    static String problem(String text, double seconds) {
        int words = countWords(text);
        if (seconds < MIN_CHECKED_SECONDS) {
            return words == 0 ? "the transcript is empty" : null;
        }
        double perMinute = words / (seconds / 60.0);
        if (words < MIN_WORDS || perMinute < MIN_WORDS_PER_MINUTE) {
            return String.format(java.util.Locale.ROOT,
                "only %d spoken word(s) were found in %.0f seconds (%.1f words/min; a spoken lesson has about 100-160). "
                    + "The video probably has no narration (music only or silent). Add a narrated video or a script.",
                words, seconds, perMinute);
        }
        return null;
    }

    static int countWords(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.trim().split("\\s+").length;
    }
}
