package com.video.transcribe.transcription;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.video.transcribe.config.AppConfig;
import com.video.transcribe.model.TranscriptData;

/**
 * Local Whisper transcriber - all paths from application.properties
 */
public class LocalWhisperTranscriber {
    
    private static final Logger logger = LoggerFactory.getLogger(LocalWhisperTranscriber.class);
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    
    private final String pythonPath;
    private final String whisperScriptPath;
    private final String modelSize;
    private final String device;
    private final int timeoutMinutes;
    private final String specialists;
    private String scriptSha256;
    
    public LocalWhisperTranscriber(AppConfig config) {
        this.pythonPath = config.getPythonPath();
        this.whisperScriptPath = config.getWhisperScript();
        this.modelSize = config.getWhisperModel();
        this.device = config.getWhisperDevice();
        this.timeoutMinutes = 60;
        this.specialists = config.getWhisperSpecialists();
        
        verifyPython();
        verifyScript();
    }

    /** Fails fast when the ASR script is missing, and records exactly which script (path + SHA-256) a run used. */
    private void verifyScript() {
        java.nio.file.Path script = java.nio.file.Paths.get(whisperScriptPath).toAbsolutePath().normalize();
        if (!java.nio.file.Files.isRegularFile(script)) {
            throw new IllegalStateException("Whisper script not found: " + script + " (set tool.whisper.script)");
        }
        this.scriptSha256 = sha256(script);
        logger.info("✓ Whisper script: {} (sha256 {})", script, scriptSha256.substring(0, 12));
    }

    private static String sha256(java.nio.file.Path file) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(file));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot hash " + file + ": " + e.getMessage(), e);
        }
    }

    /** Path and hash of the ASR script in use, for the job manifest. */
    public java.util.Map<String, String> scriptInfo() {
        return java.util.Map.of("path", java.nio.file.Paths.get(whisperScriptPath).toAbsolutePath().normalize().toString(),
            "sha256", scriptSha256 == null ? "" : scriptSha256, "model", modelSize);
    }
    
    private void verifyPython() {
        try {
            ProcessBuilder pb = new ProcessBuilder(pythonPath, "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor(5, TimeUnit.SECONDS);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String version = r.readLine();
                logger.info("✓ Python: {}", version);
            }
        } catch (Exception e) {
            logger.error("✗ Python not found: {}", pythonPath);
        }
    }
    
    /**
     * Transcribe audio file using local Whisper
     */
    public TranscriptData transcribe(Path audioPath, String language, Path outputJson) throws Exception {
        return transcribe(audioPath, language, outputJson, java.util.List.of());
    }

    public TranscriptData transcribe(Path audioPath, String language, Path outputJson, java.util.List<String> hotwords) throws Exception {
        try {
            return runWhisper(device, audioPath, language, outputJson, hotwords);
        } catch (IOException gpuFailure) {
            // A missing CUDA library, an out-of-memory GPU or a hung driver must not stop the video: the same script runs on the CPU
            // (slower, same model, same result). A genuinely bad input fails again there and the second error is the one reported.
            if ("cpu".equalsIgnoreCase(device) || !Boolean.parseBoolean(System.getProperty("whisper.cpu-fallback", "true"))) {
                throw gpuFailure;
            }
            logger.warn("Whisper failed on '{}' ({}); retrying on the CPU so the video can continue", device, firstLine(gpuFailure.getMessage()));
            return runWhisper("cpu", audioPath, language, outputJson, hotwords);
        }
    }

    private static String firstLine(String text) {
        String line = String.valueOf(text).strip().lines().filter(l -> !l.isBlank()).reduce((first, second) -> second).orElse("no details");
        return line.length() > 160 ? line.substring(0, 160) + "..." : line;
    }

    /** For tests: explicit settings, no config file and no environment checks. */
    LocalWhisperTranscriber(String pythonPath, String whisperScriptPath, String modelSize, String device, int timeoutMinutes) {
        this.pythonPath = pythonPath;
        this.whisperScriptPath = whisperScriptPath;
        this.modelSize = modelSize;
        this.device = device;
        this.timeoutMinutes = timeoutMinutes;
        this.specialists = "";
    }

    private TranscriptData runWhisper(String runDevice, Path audioPath, String language, Path outputJson, java.util.List<String> hotwords) throws Exception {
        logger.info("Starting Whisper: model={}, device={}, file={}", modelSize, runDevice, audioPath);

        List<String> command = new ArrayList<>();
        command.add(pythonPath);
        command.add(whisperScriptPath);
        command.add(audioPath.toString());
        command.add("--model");
        command.add(modelSize);
        command.add("--device");
        command.add(runDevice);
        
        if (language != null) {
            command.add("--language");
            command.add(language);
        }
        
        if (specialists != null && !specialists.isBlank()) {
            command.add("--specialists");
            command.add(specialists);
        }

        if (hotwords != null && !hotwords.isEmpty()) {
            Path file = java.nio.file.Files.createTempFile("hotwords", ".txt");
            java.nio.file.Files.write(file, hotwords, java.nio.charset.StandardCharsets.UTF_8);
            file.toFile().deleteOnExit();
            command.add("--hotwords-file");
            command.add(file.toString());
        }

        if (outputJson != null) {
            command.add("--output");
            command.add(outputJson.toString());
        }
        
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        
        long startTime = System.currentTimeMillis();
        Process process = pb.start();
        
        // The output is drained on its own thread so the time limit below really applies: reading to the end of the stream first
        // would wait forever on a hung process.
        StringBuilder output = new StringBuilder();
        Thread drain = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (output) {
                        output.append(line).append("\n");
                    }
                    logger.debug("Whisper: {}", line);
                }
            } catch (IOException e) {
                logger.debug("Whisper output closed: {}", e.getMessage());
            }
        }, "whisper-output");
        drain.setDaemon(true);
        drain.start();

        boolean finished;
        try {
            finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();                  // never leave a recogniser running on the GPU after the job was cancelled
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Whisper timeout after " + timeoutMinutes + " minutes");
        }
        drain.join(5_000);
        
        if (process.exitValue() != 0) {
            throw new IOException("Whisper failed:\n" + output);
        }
        
        long duration = System.currentTimeMillis() - startTime;
        logger.info("Transcription done in {} ms", duration);
        
        if (outputJson != null && Files.exists(outputJson)) {
            String json = Files.readString(outputJson);
            return gson.fromJson(json, TranscriptData.class);
        }
        
        return gson.fromJson(output.toString(), TranscriptData.class);
    }
    
    public TranscriptData transcribe(Path audioPath) throws Exception {
        Path outputJson = Paths.get(audioPath.getParent().toString(), 
            getBaseName(audioPath.getFileName().toString()) + "_transcript.json");
        return transcribe(audioPath, null, outputJson);
    }
    
    private String getBaseName(String filename) {
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(0, lastDot) : filename;
    }
}