package com.video.transcribe.audio;

import java.io.BufferedReader;
import java.io.File;
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

/**
 * FFmpeg Audio Extractor - reads paths from application.properties
 */
public class FFmpegAudioExtractor {
    
    private static final Logger logger = LoggerFactory.getLogger(FFmpegAudioExtractor.class);
    
    private final String ffmpegPath;
    private final String ffprobePath;
    private final int timeoutMinutes;
    
    public FFmpegAudioExtractor(AppConfig config) {
        this.ffmpegPath = config.getFfmpegPath();
        this.ffprobePath = config.getFfprobePath();
        this.timeoutMinutes = 30;
        
        // Verify tools exist
        verifyTool("FFmpeg", ffmpegPath);
        verifyTool("FFprobe", ffprobePath);
    }
    
    private void verifyTool(String name, String path) {
        try {
            ProcessBuilder pb = new ProcessBuilder(path, "-version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean ok = p.waitFor(5, TimeUnit.SECONDS);
            if (ok && p.exitValue() == 0) {
                logger.info("✓ {} found: {}", name, path);
            } else {
                logger.warn("⚠ {} may not be working: {}", name, path);
            }
        } catch (Exception e) {
            logger.error("✗ {} not found or not working: {} - {}", name, path, e.getMessage());
        }
    }
    
    /**
     * Extract audio optimized for Whisper (16kHz, mono, WAV)
     */
    public Path extractAudioForWhisper(String videoPath, String outputDir) throws Exception {
        File videoFile = new File(videoPath);
        if (!videoFile.exists()) {
            throw new IOException("Video not found: " + videoPath);
        }
        
        String baseName = getBaseName(videoFile.getName());
        Path audioPath = Paths.get(outputDir, baseName + "_audio.wav");
        
        new File(outputDir).mkdirs();
        
        ProcessBuilder pb = new ProcessBuilder(
            ffmpegPath,
            "-i", videoPath,
            "-vn",
            "-acodec", "pcm_s16le",
            "-ar", "16000",
            "-ac", "1",
            "-y",
            audioPath.toString()
        );
        
        pb.redirectErrorStream(true);
        logger.info("Extracting audio: {} → {}", videoPath, audioPath);
        
        Process process = pb.start();
        
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logger.debug("FFmpeg: {}", line);
            }
        }
        
        boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("FFmpeg timeout after " + timeoutMinutes + " minutes");
        }
        
        if (process.exitValue() != 0) {
            throw new IOException("FFmpeg failed with code: " + process.exitValue());
        }
        
        logger.info("Audio extracted: {} ({} bytes)", audioPath, Files.size(audioPath));
        return audioPath;
    }
    
    /**
     * Get video duration using ffprobe
     */
    public double getDuration(String videoPath) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
            ffprobePath,
            "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
            videoPath
        );
        
        pb.redirectErrorStream(true);
        Process process = pb.start();
        
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line.trim());
            }
        }
        
        process.waitFor(1, TimeUnit.MINUTES);
        return Double.parseDouble(output.toString().trim());
    }
    
    /**
     * Split audio into chunks
     */
    public List<Path> splitAudio(String audioPath, String outputDir, double chunkSeconds) throws Exception {
        List<Path> chunks = new ArrayList<>();
        double duration = getDuration(audioPath);
        int numChunks = (int) Math.ceil(duration / chunkSeconds);
        
        new File(outputDir).mkdirs();
        String baseName = getBaseName(new File(audioPath).getName());
        
        for (int i = 0; i < numChunks; i++) {
            double start = i * chunkSeconds;
            double len = Math.min(chunkSeconds, duration - start);
            
            Path chunkPath = Paths.get(outputDir, baseName + "_chunk_" + i + ".wav");
            
            ProcessBuilder pb = new ProcessBuilder(
                ffmpegPath,
                "-i", audioPath,
                "-ss", String.valueOf(start),
                "-t", String.valueOf(len),
                "-c", "copy",
                "-y",
                chunkPath.toString()
            );
            
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
            
            chunks.add(chunkPath);
            logger.info("Created chunk {}: {} ({}s - {}s)", i, chunkPath, start, start + len);
        }
        
        return chunks;
    }
    
    /**
     * Merge new audio with original video
     */
    public Path mergeAudioVideo(String videoPath, String newAudioPath, String outputPath) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
            ffmpegPath,
            "-i", videoPath,
            "-i", newAudioPath,
            "-c:v", "copy",
            "-c:a", "aac",
            "-b:a", "192k",
            "-map", "0:v:0",
            "-map", "1:a:0",
            "-shortest",
            "-y",
            outputPath
        );
        
        pb.redirectErrorStream(true);
        logger.info("Merging: video={} + audio={} → {}", videoPath, newAudioPath, outputPath);
        
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logger.debug("FFmpeg: {}", line);
            }
        }
        
        process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        
        if (process.exitValue() != 0) {
            throw new IOException("Merge failed with code: " + process.exitValue());
        }
        
        return Paths.get(outputPath);
    }
    
    private String getBaseName(String filename) {
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(0, lastDot) : filename;
    }
}