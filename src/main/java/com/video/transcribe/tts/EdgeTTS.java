package com.video.transcribe.tts;

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

public class EdgeTTS implements TTSProvider {
    
    private static final Logger logger = LoggerFactory.getLogger(EdgeTTS.class);
    
    private final String pythonPath;
    private final String edgeScript;
    private final String defaultVoice;
    private final String defaultRate;
    private final int timeoutMinutes;
    
    public EdgeTTS(AppConfig config) {
        this.pythonPath = config.getPythonPath();
        this.edgeScript = config.getString("tool.edge_tts.script", "./edge_tts.py");
        this.defaultVoice = config.getString("tool.edge_tts.voice", "en-IN-NeerjaNeural");
        this.defaultRate = config.getString("tool.edge_tts.rate", "+0%");
        this.timeoutMinutes = 10;
        verifyEdgeTTS();
    }
    
    private void verifyEdgeTTS() {
        try {
            ProcessBuilder pb = new ProcessBuilder(pythonPath, "-c", "import edge_tts");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean ok = p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
            if (ok) logger.info("✓ Edge TTS available");
            else logger.error("✗ Edge TTS not found. Run: pip install edge-tts");
        } catch (Exception e) {
            logger.error("✗ Edge TTS check failed: {}", e.getMessage());
        }
    }
    
    @Override
    public String getName() { return "Edge-TTS"; }
    
    @Override
    public boolean isAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder(pythonPath, "-c", "import edge_tts");
            Process p = pb.start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) { return false; }
    }
    
    @Override
    public Path synthesize(String text, Path outputPath) throws Exception {
        return synthesizeWithRate(text, outputPath, defaultVoice, defaultRate);
    }
    
    public Path synthesizeWithRate(String text, Path outputPath, String voice, String rate) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(pythonPath);
        command.add(edgeScript);
        command.add(text);
        command.add("--output");
        command.add(outputPath.toString());
        command.add("--voice");
        command.add(voice != null ? voice : defaultVoice);
        command.add("--rate");
        command.add(rate != null ? rate : defaultRate);
        
        logger.info("Edge TTS: {} chars → {} (voice={}, rate={})", 
            text.length(), outputPath, voice, rate);
        long start = System.currentTimeMillis();
        
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(System.getProperty("user.dir")));
        pb.redirectErrorStream(true);
        
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logger.info("EdgeTTS: {}", line);
            }
        }
        
        boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Edge TTS timed out");
        }
        if (process.exitValue() != 0) {
            throw new IOException("Edge TTS failed: " + process.exitValue());
        }
        if (!Files.exists(outputPath) || Files.size(outputPath) == 0) {
            throw new IOException("Edge TTS produced no output");
        }
        
        logger.info("Edge done in {} ms", System.currentTimeMillis() - start);
        return outputPath;
    }
    
    @Override
    public Path synthesizeLongText(String text, Path outputDir, String baseName) throws Exception {
        // Edge can handle ~3000 chars, chunk if longer
        if (text.length() <= 3000) {
            Path out = Paths.get(outputDir.toString(), baseName + "_audio.mp3");
            return synthesize(text, out);
        }
        
        String[] sentences = text.split("(?<=[.!?])\\s+");
        List<Path> chunkFiles = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();
        int chunkIndex = 0;
        
        for (String sentence : sentences) {
            if (currentChunk.length() + sentence.length() > 3000) {
                Path chunkPath = Paths.get(outputDir.toString(), baseName + "_tts_" + chunkIndex + ".mp3");
                synthesize(currentChunk.toString().trim(), chunkPath);
                chunkFiles.add(chunkPath);
                currentChunk = new StringBuilder();
                chunkIndex++;
            }
            currentChunk.append(sentence).append(" ");
        }
        
        if (currentChunk.length() > 0) {
            Path chunkPath = Paths.get(outputDir.toString(), baseName + "_tts_" + chunkIndex + ".mp3");
            synthesize(currentChunk.toString().trim(), chunkPath);
            chunkFiles.add(chunkPath);
        }
        
        return concatenateMp3Files(chunkFiles, Paths.get(outputDir.toString(), baseName + "_audio.mp3"));
    }
    
    private Path concatenateMp3Files(List<Path> files, Path output) throws Exception {
        Path listFile = Files.createTempFile("concat_list_", ".txt");
        StringBuilder list = new StringBuilder();
        for (Path f : files) {
            list.append("file '").append(f.toAbsolutePath().toString().replace("'", "'\\''")).append("'\n");
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