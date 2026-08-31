package com.video.transcribe.tts;

import java.nio.file.Path;

/**
 * Common interface for all TTS providers
 */
public interface TTSProvider {
    
    /**
     * Synthesize text to speech
     */
    Path synthesize(String text, Path outputPath) throws Exception;
    
    /**
     * Synthesize long text by chunking
     */
    Path synthesizeLongText(String text, Path outputDir, String baseName) throws Exception;
    
    String getName();
    boolean isAvailable();
}