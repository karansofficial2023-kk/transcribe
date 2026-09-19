package com.video.transcribe.transcription;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.video.transcribe.model.TranscriptData;

class TranscriptQualityGateTest {
    @Test
    void rejectsSparseRepeatedLowConfidenceHallucination() {
        TranscriptData transcript = transcript(178.7, 7, "What's up?", 0.11, 30.0);
        assertFalse(TranscriptQualityGate.hasUsableSpeech(transcript));
    }

    @Test
    void acceptsNormalEducationalSpeech() {
        TranscriptData transcript = transcript(45.0, 6,
            "Cells connected in series increase the total voltage", 0.88, 6.0);
        assertTrue(TranscriptQualityGate.hasUsableSpeech(transcript));
    }

    @Test
    void acceptsUsableTamilSpeech() {
        TranscriptData transcript = transcript(35.0, 5,
            "மகரந்தச் சேர்க்கை தாவர இனப்பெருக்கத்தின் முக்கிய செயல்முறை", 0.82, 6.0);
        assertTrue(TranscriptQualityGate.hasUsableSpeech(transcript));
    }

    private TranscriptData transcript(double duration, int segmentCount, String text,
            double probability, double spacing) {
        TranscriptData transcript = new TranscriptData();
        transcript.setDuration(duration);
        List<TranscriptData.Segment> segments = new ArrayList<>();
        StringBuilder fullText = new StringBuilder();
        for (int i = 0; i < segmentCount; i++) {
            TranscriptData.Segment segment = new TranscriptData.Segment();
            segment.setId(i);
            segment.setStart(i * spacing);
            segment.setEnd(i * spacing + Math.min(4.0, spacing));
            segment.setText(text);
            List<TranscriptData.Word> words = new ArrayList<>();
            for (String token : text.split("\\s+")) {
                TranscriptData.Word word = new TranscriptData.Word();
                word.setWord(token);
                word.setProbability(probability);
                words.add(word);
            }
            segment.setWords(words);
            segments.add(segment);
            if (!fullText.isEmpty()) fullText.append(' ');
            fullText.append(text);
        }
        transcript.setSegments(segments);
        transcript.setText(fullText.toString());
        return transcript;
    }
}
