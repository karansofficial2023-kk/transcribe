package com.video.transcribe.queue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/** A small <name>_status.json next to a video's outputs: what happened, how many attempts, and why it stopped. Never fails a job. */
public final class ItemStatusFile {

    private static final Logger logger = LoggerFactory.getLogger(ItemStatusFile.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private ItemStatusFile() {
    }

    public static void write(String outputDir, String baseName, String state, int attempts, String error, boolean finalFailure) {
        try {
            Path directory = Path.of(outputDir);
            Files.createDirectories(directory);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("video", baseName);
            data.put("state", state);
            data.put("attempts", attempts);
            data.put("final", finalFailure);
            data.put("error", error);
            data.put("updated", Instant.now().toString());
            Path target = directory.resolve(baseName + "_status.json");
            Path temp = Files.createTempFile(directory, "status", ".tmp");
            Files.writeString(temp, GSON.toJson(data), StandardCharsets.UTF_8);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            logger.debug("Status file not written: {}", e.getMessage());
        }
    }
}
