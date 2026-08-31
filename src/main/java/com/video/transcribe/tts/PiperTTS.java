package com.video.transcribe.tts;

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

import com.video.transcribe.config.AppConfig;

public class PiperTTS implements TTSProvider {
    
    private static final Logger logger = LoggerFactory.getLogger(PiperTTS.class);
    
    private final String piperPath;
    private final String modelPath;
    private final String configPath;
    private final int timeoutMinutes;
    
    public PiperTTS(AppConfig config) {
        this.piperPath = config.getPiperPath();
        this.modelPath = config.getPiperModel();
        this.configPath = config.getPiperModelConfig();
        this.timeoutMinutes = 10;
        verifyPiper();
    }
    
    private void verifyPiper() {
        try {
            ProcessBuilder pb = new ProcessBuilder(piperPath, "--help");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor(5, TimeUnit.SECONDS);
            logger.info("✓ Piper TTS found: {}", piperPath);
        } catch (Exception e) {
            logger.error("✗ Piper not found: {}", piperPath);
        }
    }
    
    @Override
    public String getName() { return "Piper-TTS"; }
    
    @Override
    public boolean isAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder(piperPath, "--help");
            Process p = pb.start();
            return p.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) { return false; }
    }
    
    @Override
    public Path synthesize(String text, Path outputPath) throws Exception {
        Path tempText = Files.createTempFile("piper_input_", ".txt");
        Files.writeString(tempText, text);
        
        List<String> command = new ArrayList<>();
        command.add(piperPath);
        command.add("--model");
        command.add(modelPath);
        if (configPath != null && !configPath.isEmpty()) {
            command.add("--config");
            command.add(configPath);
        }
        command.add("--output_file");
        command.add(outputPath.toString());
        
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectInput(tempText.toFile());
        pb.redirectErrorStream(true);
        
        logger.info("Piper TTS: {} chars → {}", text.length(), outputPath);
        long start = System.currentTimeMillis();
        
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logger.debug("Piper: {}", line);
            }
        }
        
        boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        Files.deleteIfExists(tempText);
        
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Piper TTS timed out");
        }
        if (process.exitValue() != 0) {
            throw new IOException("Piper TTS failed: " + process.exitValue());
        }
        
        logger.info("Piper done in {} ms", System.currentTimeMillis() - start);
        return outputPath;
    }
    
    @Override
    public Path synthesizeLongText(String text, Path outputDir, String baseName) throws Exception {
        String[] sentences = text.split("(?<=[.!?])\\s+");
        List<Path> chunkFiles = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();
        int chunkIndex = 0;
        
        for (String sentence : sentences) {
            if (currentChunk.length() + sentence.length() > 500) {
                Path chunkPath = Paths.get(outputDir.toString(), baseName + "_tts_" + chunkIndex + ".wav");
                synthesize(currentChunk.toString().trim(), chunkPath);
                chunkFiles.add(chunkPath);
                currentChunk = new StringBuilder();
                chunkIndex++;
            }
            currentChunk.append(sentence).append(" ");
        }
        
        if (currentChunk.length() > 0) {
            Path chunkPath = Paths.get(outputDir.toString(), baseName + "_tts_" + chunkIndex + ".wav");
            synthesize(currentChunk.toString().trim(), chunkPath);
            chunkFiles.add(chunkPath);
        }
        
        return concatenateWavFiles(chunkFiles, Paths.get(outputDir.toString(), baseName + "_audio.wav"));
    }
    
    private Path concatenateWavFiles(List<Path> files, Path output) throws Exception {
        Path listFile = Files.createTempFile("concat_list_", ".txt");
        StringBuilder list = new StringBuilder();
        for (Path f : files) {
            list.append("file '").append(f.toAbsolutePath()).append("'\n");
        }
        Files.writeString(listFile, list.toString());
        
        ProcessBuilder pb = new ProcessBuilder(
            "ffmpeg", "-f", "concat", "-safe", "0", "-i", listFile.toString(),
            "-c", "copy", "-y", output.toString()
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();
        process.waitFor(5, TimeUnit.MINUTES);
        
        Files.deleteIfExists(listFile);
        for (Path f : files) Files.deleteIfExists(f);
        
        return output;
    }
}