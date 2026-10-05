package com.video.transcribe.transcription;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.video.transcribe.llm.OllamaClient;

/**
 * Restores sentence punctuation and capitalisation in ASR text that came back as one long run-on. Every later stage
 * (sentence-level shots, subtitles, source-coverage checks) assumes sentences, so an unpunctuated transcript is repaired first.
 * The repair is word-for-word: a chunk is accepted only when its words are identical before and after.
 */
public final class TranscriptPunctuator {
    private static final Logger logger = LoggerFactory.getLogger(TranscriptPunctuator.class);
    private static final int CHUNK_WORDS = 160;
    private static final double MIN_TERMINATORS_PER_100_WORDS = 3.0;

    private TranscriptPunctuator() {
    }

    /** Terminators per 100 words (. ! ? and the Devanagari danda). */
    static double density(String text) {
        int words = words(text).size();
        if (words == 0) return 100;
        int terminators = 0;
        for (char ch : text.toCharArray()) {
            if (ch == '.' || ch == '!' || ch == '?' || ch == '।' || ch == '۔') terminators++;
        }
        return terminators * 100.0 / words;
    }

    static List<String> words(String text) {
        List<String> result = new ArrayList<>();
        for (String token : (text == null ? "" : text).toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{M}\\p{N}']+")) {
            if (!token.isBlank()) result.add(token);
        }
        return result;
    }

    /** Returns punctuated text; falls back to the original chunk whenever the model changes any word. */
    public static String restore(String text, OllamaClient llm) {
        if (text == null || text.isBlank()) return text;
        String[] tokens = text.trim().split("\\s+");
        StringBuilder out = new StringBuilder();
        for (int from = 0; from < tokens.length; from += CHUNK_WORDS) {
            String chunk = String.join(" ", java.util.Arrays.copyOfRange(tokens, from, Math.min(tokens.length, from + CHUNK_WORDS)));
            String fixed = chunk;
            if (density(chunk) < MIN_TERMINATORS_PER_100_WORDS) {
                try {
                    String candidate = llm.generate(
                        "You restore punctuation. Add sentence-ending punctuation, commas and capital letters to the text. "
                            + "Do NOT add, remove, reorder, translate or correct any word. Keep the language and script. "
                            + "Return only the punctuated text.", chunk).trim();
                    if (words(candidate).equals(words(chunk))) {
                        fixed = candidate;
                    } else {
                        logger.warn("Punctuation restoration changed words; keeping the original chunk");
                    }
                } catch (IOException e) {
                    logger.warn("Punctuation restoration skipped: {}", e.getMessage());
                }
            }
            if (out.length() > 0) out.append(' ');
            out.append(fixed);
        }
        return out.toString();
    }
}
