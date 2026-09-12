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
import java.util.Locale;
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
        this.pythonPath = config.getPythonPath().trim();
        this.edgeScript = config.getString("tool.edge_tts.script", "./edge_tts.py").trim();
        this.defaultVoice = config.getString("tool.edge_tts.voice", "en-IN-NeerjaNeural").trim();
        this.defaultRate = config.getString("tool.edge_tts.rate", "+0%").trim();
        this.timeoutMinutes = 10;
        verifyEdgeTTS();
    }
    
    private void verifyEdgeTTS() {
        try {
            logger.info("EdgeTTS using python path: {}", pythonPath);
            ProcessBuilder pb = new ProcessBuilder(pythonPath, "-c", 
                "import edge_tts; print('VERSION:', edge_tts.__version__); print('COMMUNICATE:', hasattr(edge_tts, 'Communicate'))");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    logger.info("EdgeTTS verify: {}", line);
                }
            }
            
            boolean ok = p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
            if (ok) logger.info("✓ Edge TTS available");
            else logger.error("✗ Edge TTS verification failed. Output: {}", output.toString().trim());
        } catch (Exception e) {
            logger.error("✗ Edge TTS check failed: {}", e.getMessage());
        }
    }
    
    @Override
    public String getName() { return "Edge-TTS"; }
    
    @Override
    public boolean isAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder(pythonPath, "-c", 
                "import edge_tts; print(hasattr(edge_tts, 'Communicate'))");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }
            
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            return finished && p.exitValue() == 0 && output.toString().trim().equals("True");
        } catch (Exception e) { 
            return false; 
        }
    }
    
    @Override
    public Path synthesize(String text, Path outputPath) throws Exception {
        return synthesizeWithRate(text, outputPath, defaultVoice, defaultRate);
    }
    
    public Path synthesizeWithRate(String text, Path outputPath, String voice, String rate) throws Exception {
        String cleanText = sanitizeText(text);
        if (cleanText.isBlank()) {
            throw new IOException("Edge TTS received empty text after cleanup");
        }

        String selectedVoice = selectVoice(cleanText, voice);
        String selectedRate = normalizeRate(rate != null ? rate : defaultRate);

        // Write text to temp file to avoid shell escaping issues
        Path tempTextFile = Files.createTempFile("edge_tts_input_", ".txt");
        Files.writeString(tempTextFile, cleanText);
        
        List<String> command = new ArrayList<>();
        command.add(pythonPath);
        command.add(edgeScript);
        command.add(tempTextFile.toString());
        command.add("--output");
        command.add(outputPath.toString());
        command.add("--voice");
        command.add(selectedVoice);
        // FIX: Use --rate=VALUE syntax so negative values like -20% don't get parsed as flags
        command.add("--rate=" + selectedRate);
        

        logger.info("Edge TTS: {} chars → {} (voice={}, rate={})",
            cleanText.length(), outputPath, selectedVoice, selectedRate);
        long start = System.currentTimeMillis();
        
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(System.getProperty("user.dir")));
        pb.redirectErrorStream(true);
        
        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                logger.info("EdgeTTS: {}", line);
            }
        }
        
        boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        
        // Cleanup temp file
        try {
            Files.deleteIfExists(tempTextFile);
        } catch (IOException e) {
            logger.warn("Failed to delete temp text file: {}", e.getMessage());
        }
        
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Edge TTS timed out after " + timeoutMinutes + " minutes");
        }
        
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new IOException("Edge TTS failed with exit code " + exitCode + 
                ". Output: " + output.toString().trim());
        }
        
        if (!Files.exists(outputPath) || Files.size(outputPath) == 0) {
            throw new IOException("Edge TTS produced no output file");
        }
        
        logger.info("Edge done in {} ms", System.currentTimeMillis() - start);
        return outputPath;
    }
    
    @Override
    public Path synthesizeLongText(String text, Path outputDir, String baseName) throws Exception {
        String cleanText = sanitizeText(text);
        if (cleanText.length() <= 1800) {
            Path out = Paths.get(outputDir.toString(), baseName + "_audio.mp3");
            return synthesize(cleanText, out);
        }
        
        List<String> sentences = splitForTts(cleanText);
        List<Path> chunkFiles = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();
        int chunkIndex = 0;
        
        for (String sentence : sentences) {
            if (currentChunk.length() + sentence.length() > 1800 && currentChunk.length() > 0) {
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

    private String sanitizeText(String text) {
        if (text == null) {
            return "";
        }
        return text
            .replaceAll("(?m)^\\s*```.*$", " ")
            .replace("**", "")
            .replace("*", "")
            .replace("#", "")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private List<String> splitForTts(String text) {
        List<String> chunks = new ArrayList<>();
        String[] sentences = text.split("(?<=[.!?।。！？…]|[।!?]|[.])\\s+|(?<=[.!?])");
        for (String sentence : sentences) {
            String trimmed = sentence.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() <= 1800) {
                chunks.add(trimmed);
                continue;
            }
            for (int start = 0; start < trimmed.length(); start += 1200) {
                chunks.add(trimmed.substring(start, Math.min(start + 1200, trimmed.length())));
            }
        }
        return chunks;
    }

    private String selectVoice(String text, String configuredVoice) {
        String requested = configuredVoice != null ? configuredVoice.trim() : "";
        String detectedVoice = detectVoice(text);
        if (!requested.isBlank() && !requested.equalsIgnoreCase("auto")) {
            if (detectedVoice != null && requested.toLowerCase(Locale.ROOT).startsWith("en-")) {
                logger.warn("Indic text detected with English Edge voice {}; switching to {}", requested, detectedVoice);
                return detectedVoice;
            }
            return requested;
        }

        return detectedVoice != null ? detectedVoice : defaultVoice;
    }

    private String detectVoice(String text) {
        if (containsRange(text, '\u0B80', '\u0BFF')) return "ta-IN-PallaviNeural";
        if (containsRange(text, '\u0900', '\u097F')) return "hi-IN-SwaraNeural";
        if (containsRange(text, '\u0C00', '\u0C7F')) return "te-IN-ShrutiNeural";
        if (containsRange(text, '\u0D00', '\u0D7F')) return "ml-IN-SobhanaNeural";
        if (containsRange(text, '\u0C80', '\u0CFF')) return "kn-IN-SapnaNeural";
        if (containsRange(text, '\u0980', '\u09FF')) return "bn-IN-TanishaaNeural";
        if (containsRange(text, '\u0A80', '\u0AFF')) return "gu-IN-DhwaniNeural";
        if (containsRange(text, '\u0600', '\u06FF')) return "ur-IN-GulNeural";
        return null;
    }

    private boolean containsRange(String text, char start, char end) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= start && ch <= end) {
                return true;
            }
        }
        return false;
    }

    private String normalizeRate(String rate) {
        if (rate == null || rate.isBlank()) {
            return "+0%";
        }
        String trimmed = rate.trim();
        return trimmed.matches("[+-]?\\d+%") ? trimmed : "+0%";
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
