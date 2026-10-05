package com.video.transcribe.llm;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Disk cache for model answers, keyed by the complete request (model, prompts, schema, sampling options).
 *
 * Re-running a lesson after a code change repeats mostly identical requests; their answers come back from disk in
 * milliseconds instead of minutes, and an identical input gives an identical storyboard. Any change to a prompt, model or option
 * changes the key, so stale answers are never reused for a different question. Delete the folder to force fresh answers.
 */
public final class LlmCache {
    public static final AtomicLong HITS = new AtomicLong();
    public static final AtomicLong MISSES = new AtomicLong();
    public static final AtomicLong MODEL_MILLIS = new AtomicLong();

    private final Path directory;

    /** @param directory cache folder, or null/blank to disable caching */
    public LlmCache(String directory) {
        this.directory = directory == null || directory.isBlank() ? null : Path.of(directory);
    }

    public boolean enabled() {
        return directory != null;
    }

    public String get(String request) {
        if (!enabled()) return null;
        try {
            Path file = file(request);
            if (Files.isRegularFile(file)) {
                String answer = Files.readString(file, StandardCharsets.UTF_8);
                if (!answer.isBlank()) {
                    HITS.incrementAndGet();
                    return answer;
                }
            }
        } catch (Exception ignored) {
            // an unreadable cache entry is just a miss
        }
        MISSES.incrementAndGet();
        return null;
    }

    public void put(String request, String answer) {
        if (!enabled() || answer == null || answer.isBlank()) return;
        try {
            Files.createDirectories(directory);
            Path target = file(request);
            Path temp = Files.createTempFile(directory, "answer", ".tmp");
            Files.writeString(temp, answer, StandardCharsets.UTF_8);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);     // atomic enough: readers never see half an answer
        } catch (Exception ignored) {
            // caching is an optimisation; failing to write must never fail the job
        }
    }

    private Path file(String request) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(request.getBytes(StandardCharsets.UTF_8))) {
            hex.append(String.format("%02x", b));
        }
        return directory.resolve(hex + ".txt");
    }
}
