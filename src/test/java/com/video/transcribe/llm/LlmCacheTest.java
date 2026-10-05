package com.video.transcribe.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LlmCacheTest {
    @Test
    void anAnswerComesBackForTheSameRequestOnly(@TempDir Path dir) {
        LlmCache cache = new LlmCache(dir.toString());
        assertTrue(cache.enabled());
        assertNull(cache.get("{\"model\":\"m\",\"prompt\":\"a\"}"));
        cache.put("{\"model\":\"m\",\"prompt\":\"a\"}", "{\"answer\":\"தமிழ்\"}");
        assertEquals("{\"answer\":\"தமிழ்\"}", cache.get("{\"model\":\"m\",\"prompt\":\"a\"}"));
        assertNull(cache.get("{\"model\":\"m\",\"prompt\":\"b\"}"));          // any change to the request is a different key
        assertNull(cache.get("{\"model\":\"other\",\"prompt\":\"a\"}"));
    }

    @Test
    void emptyAnswersAreNeverStored(@TempDir Path dir) {
        LlmCache cache = new LlmCache(dir.toString());
        cache.put("request", "   ");
        cache.put("request", null);
        assertNull(cache.get("request"));
    }

    @Test
    void aBlankFolderDisablesCaching() {
        LlmCache cache = new LlmCache("");
        assertFalse(cache.enabled());
        cache.put("request", "answer");
        assertNull(cache.get("request"));
    }

    @Test
    void hitsAndMissesAreCounted(@TempDir Path dir) {
        LlmCache cache = new LlmCache(dir.toString());
        long hits = LlmCache.HITS.get();
        long misses = LlmCache.MISSES.get();
        cache.get("x");
        cache.put("x", "answer");
        cache.get("x");
        assertEquals(misses + 1, LlmCache.MISSES.get());
        assertEquals(hits + 1, LlmCache.HITS.get());
    }
}
