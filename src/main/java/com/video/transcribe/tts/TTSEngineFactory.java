package com.video.transcribe.tts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.video.transcribe.config.AppConfig;

/**
 * Factory to create TTS provider based on config
 */
public class TTSEngineFactory {
    
    private static final Logger logger = LoggerFactory.getLogger(TTSEngineFactory.class);
    
    public enum TTSProviderType {
        PIPER, EDGE
    }
    
    /**
     * Create provider from config property "tts.provider" (piper or edge)
     */
    public static TTSProvider createProvider(AppConfig config) {
        String provider = config.getString("tts.provider", "piper").toLowerCase();
        
        if ("edge".equals(provider)) {
            logger.info("TTS Engine: Edge TTS");
            return new EdgeTTS(config);
        } else {
            logger.info("TTS Engine: Piper TTS");
            return new PiperTTS(config);
        }
    }
    
    /**
     * Create specific provider by enum
     */
    public static TTSProvider createProvider(AppConfig config, TTSProviderType type) {
        switch (type) {
            case EDGE: return new EdgeTTS(config);
            case PIPER: 
            default: return new PiperTTS(config);
        }
    }
}