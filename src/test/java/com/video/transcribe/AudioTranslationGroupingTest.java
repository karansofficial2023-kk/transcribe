package com.video.transcribe;

import com.video.transcribe.model.TranscriptData;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AudioTranslationGroupingTest {
    private static TranscriptData.Word word(String text, double start, double end) {
        var word = new TranscriptData.Word();
        word.setWord(text);
        word.setStart(start);
        word.setEnd(end);
        word.setProbability(0.9);
        return word;
    }

    private static TranscriptData.Segment chunk(List<TranscriptData.Word> words) {
        var chunk = new TranscriptData.Segment();
        chunk.setStart(words.get(0).getStart());
        chunk.setEnd(words.get(words.size() - 1).getEnd());
        chunk.setWords(words);
        chunk.setText(String.join(" ", words.stream().map(TranscriptData.Word::getWord).toList()));
        return chunk;
    }

    @Test
    void joinsSentenceAcrossWhisperChunksAndSplitsSentencesInsideChunks() {
        var first = chunk(List.of(word("Sulfur", 1, 2), word("exists", 2, 3)));
        var second = chunk(List.of(word("as", 3, 4), word("rhombic", 4, 5), word("sulfur.", 5, 6),
            word("Oxygen", 7, 8), word("exists", 8, 9), word("as", 9, 10), word("O2.", 10, 11)));
        var groups = StoryboardLocalizer.audioTranslationGroups(List.of(first, second));
        assertEquals(2, groups.size());
        assertEquals("Sulfur exists as rhombic sulfur.", groups.get(0).get(0).getText());
        assertEquals(1, groups.get(0).get(0).getStart());
        assertEquals(6, groups.get(0).get(0).getEnd());
        assertEquals("Oxygen exists as O2.", groups.get(1).get(0).getText());
    }
}
