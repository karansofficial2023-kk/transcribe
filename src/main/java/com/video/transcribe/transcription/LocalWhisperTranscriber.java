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
    
    public LocalWhisperTranscriber(AppConfig config) {
        this.pythonPath = config.getPythonPath();
        this.whisperScriptPath = config.getWhisperScript();
        this.modelSize = config.getWhisperModel();
        this.device = config.getWhisperDevice();
        this.timeoutMinutes = 60;
        
        verifyPython();
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
        logger.info("Starting Whisper: model={}, device={}, file={}", modelSize, device, audioPath);
        
        List<String> command = new ArrayList<>();
        command.add(pythonPath);
        command.add(whisperScriptPath);
        command.add(audioPath.toString());
        command.add("--model");
        command.add(modelSize);
        command.add("--device");
        command.add(device);
        
        if (language != null) {
            command.add("--language");
            command.add(language);
        }
        
        if (outputJson != null) {
            command.add("--output");
            command.add(outputJson.toString());
        }
        
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        
        long startTime = System.currentTimeMillis();
        Process process = pb.start();
        
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                logger.debug("Whisper: {}", line);
            }
        }
        
        boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Whisper timeout after " + timeoutMinutes + " minutes");
        }
        
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