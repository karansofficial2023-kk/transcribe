package com.video.transcribe.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ItemRetryTest {

    @Test
    void aTransientFaultIsRetriedAndTheItemSucceeds() throws Exception {
        List<Long> pauses = new ArrayList<>();
        ItemRetry.Outcome outcome = ItemRetry.run(3, 100, pauses::add, attempt ->
            attempt < 3 ? new ItemRetry.Outcome(false, "Ollama request failed", false, attempt) : new ItemRetry.Outcome(true, null, false, attempt));
        assertTrue(outcome.success());
        assertEquals(3, outcome.attempts());
        assertEquals(List.of(100L, 200L), pauses);
    }

    @Test
    void anExceptionCountsAsATransientFault() throws Exception {
        ItemRetry.Outcome outcome = ItemRetry.run(2, 0, ms -> { }, attempt -> { throw new IOException("ffmpeg crashed"); });
        assertFalse(outcome.success());
        assertEquals(2, outcome.attempts());
        assertTrue(outcome.error().contains("ffmpeg crashed"));
    }

    @Test
    void aQualityGateVerdictIsFinalAndNotRepeated() throws Exception {
        ItemRetry.Outcome outcome = ItemRetry.run(3, 0, ms -> { }, attempt ->
            new ItemRetry.Outcome(false, "No usable narration in this video", FailureKind.isFinal("No usable narration in this video"), attempt));
        assertTrue(outcome.finalFailure());
        assertEquals(1, outcome.attempts());
    }

    @Test
    void classifiesGatesAsFinalAndInfrastructureAsRetryable() {
        assertTrue(FailureKind.isFinal("Paraphrase validation failed: science 75"));
        assertFalse(FailureKind.isFinal("Connection refused"));
        assertFalse(FailureKind.isFinal(null));
    }

    @Test
    void statusFileIsWritten(@TempDir Path dir) throws Exception {
        ItemStatusFile.write(dir.toString(), "lesson", "failed", 2, "boom", false);
        String json = Files.readString(dir.resolve("lesson_status.json"));
        assertTrue(json.contains("\"attempts\": 2") && json.contains("boom"), json);
    }
}
