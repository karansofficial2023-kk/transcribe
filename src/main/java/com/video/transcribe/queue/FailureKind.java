package com.video.transcribe.queue;

import java.util.List;
import java.util.Locale;

/**
 * Tells a transient fault (worth another attempt) from a final verdict (retrying only repeats the verdict).
 *
 * Final: the pipeline's own quality gates and missing input. They are deterministic, because model answers are cached, so a retry would
 * return the same refusal after the same wait. Everything else (Ollama or ffmpeg or Whisper hiccups, timeouts, I/O errors) is retried.
 */
public final class FailureKind {

    private static final List<String> FINAL_MARKERS = List.of(
        "no usable narration",                         // speech-coverage gate: a video without speech cannot be narrated
        "does not match the input filename",           // topic-identity gate
        "paraphrase validation failed",                // accuracy gates held the paraphrase after their own retries
        "production accuracy gate",
        "storyboard quality gate",
        "transcription produced no text",
        "video file not found",
        "no such file",
        "unsupported");

    private FailureKind() {
    }

    public static boolean isFinal(String message) {
        if (message == null) {
            return false;
        }
        String text = message.toLowerCase(Locale.ROOT);
        return FINAL_MARKERS.stream().anyMatch(text::contains);
    }
}
