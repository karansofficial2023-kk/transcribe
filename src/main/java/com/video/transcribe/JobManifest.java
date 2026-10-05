package com.video.transcribe;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

/** Immutable job identity: source, size, duration, SHA-256 fingerprint. The filename is only a label. */
public final class JobManifest {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private JobManifest() {
    }

    public static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 20];
            int read;
            while ((read = in.read(buffer)) > 0) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Duration in seconds via ffprobe, or -1 when unavailable. */
    public static double probeDuration(String ffprobe, Path file) {
        if (ffprobe == null || ffprobe.isBlank()) return -1;
        try {
            Process p = new ProcessBuilder(ffprobe.trim(), "-v", "error", "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1", file.toString()).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return -1;
            }
            return Double.parseDouble(out);
        } catch (Exception e) {
            return -1;
        }
    }

    public static Map<String, Object> build(Path media, String sourceLabel, String language, String ffprobe)
            throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", sourceLabel == null ? media.toString() : sourceLabel);
        m.put("local_path", media.toAbsolutePath().toString());
        m.put("bytes", Files.size(media));
        m.put("duration_seconds", probeDuration(ffprobe, media));
        m.put("sha256", sha256(media));
        m.put("language", language == null ? "auto" : language);
        m.put("created_at", Instant.now().toString());
        return m;
    }

    public static Path manifestPath(Path outputDir, String baseName) {
        return outputDir.resolve(baseName + "_manifest.json");
    }

    public static void write(Path outputDir, String baseName, Map<String, Object> manifest) throws IOException {
        Files.createDirectories(outputDir);
        Files.writeString(manifestPath(outputDir, baseName), GSON.toJson(manifest), StandardCharsets.UTF_8);
    }

    /**
     * Finds a previously processed job in outputDir with the same fingerprint and a saved transcript.
     * Returns that job's base name, or null when this media is new.
     */
    public static String findDuplicate(Path outputDir, String sha256) throws IOException {
        if (!Files.isDirectory(outputDir)) return null;
        try (Stream<Path> files = Files.list(outputDir)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String name = file.getFileName().toString();
                if (!name.endsWith("_manifest.json")) continue;
                Map<String, Object> other = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, Object>>() { }.getType());
                if (other != null && sha256.equals(other.get("sha256"))) {
                    String base = name.substring(0, name.length() - "_manifest.json".length());
                    if (Files.isRegularFile(outputDir.resolve(base + "_transcript.json"))) return base;
                }
            }
        }
        return null;
    }
}
