package com.video.transcribe.transcription;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.video.transcribe.model.TranscriptData;

/** Rejects empty, music-only, sparse, or low-confidence repetitive Whisper output. */
public final class TranscriptQualityGate {
    private TranscriptQualityGate() {
    }

    public static boolean hasUsableSpeech(TranscriptData transcript) {
        if (transcript == null || transcript.getFullText() == null) return false;
        String text = transcript.getFullText().trim();
        if (text.isBlank()) return false;

        String markerCheck = text.toLowerCase(Locale.ROOT)
            .replaceAll("[\\[\\](){}.,!?।]+", " ")
            .replaceAll("\\s+", " ").trim();
        if (List.of("music", "applause", "noise", "silence").contains(markerCheck)) return false;

        List<TranscriptData.Segment> segments = transcript.getSegments();
        if (segments == null || segments.isEmpty()) {
            return text.split("\\s+").length >= 4;
        }

        int wordCount = 0;
        int probabilityCount = 0;
        double probabilityTotal = 0.0;
        double speechSeconds = 0.0;
        Map<String, Integer> repeatedSegments = new HashMap<>();
        for (TranscriptData.Segment segment : segments) {
            if (segment == null) continue;
            speechSeconds += Math.max(0.0, segment.getEnd() - segment.getStart());
            String normalized = normalize(segment.getText());
            if (!normalized.isBlank()) repeatedSegments.merge(normalized, 1, Integer::sum);
            if (segment.getWords() == null) continue;
            for (TranscriptData.Word word : segment.getWords()) {
                if (word == null || word.getWord() == null || word.getWord().isBlank()) continue;
                wordCount++;
                probabilityTotal += word.getProbability();
                probabilityCount++;
            }
        }

        if (wordCount < 4) return false;
        double duration = Math.max(1.0, transcript.getDuration());
        double averageProbability = probabilityCount == 0 ? 1.0 : probabilityTotal / probabilityCount;
        double wordsPerSecond = wordCount / duration;
        double activeSpeechRatio = speechSeconds / duration;
        int dominantCount = repeatedSegments.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        double dominantRatio = segments.isEmpty() ? 0.0 : dominantCount / (double) segments.size();
        boolean repetitive = segments.size() >= 4
            && repeatedSegments.size() <= Math.max(2, segments.size() / 4)
            && dominantRatio >= 0.60;

        if (repetitive && averageProbability < 0.45) return false;
        if (duration > 30.0 && wordsPerSecond < 0.35 && averageProbability < 0.35) return false;
        if (duration > 30.0 && activeSpeechRatio < 0.10 && averageProbability < 0.30) return false;
        return true;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}]+", " ")
            .replaceAll("\\s+", " ").trim();
    }
}
