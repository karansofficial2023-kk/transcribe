package com.video.transcribe.tts;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.video.transcribe.config.AppConfig;

@Service
public class TTSService {
    
    private static final Logger logger = LoggerFactory.getLogger(TTSService.class);
    
    private final Map<VoiceConfig.TTSProviderType, TTSProvider> providers = new HashMap<>();
    private final AppConfig appConfig;
    
    public TTSService(AppConfig appConfig) {
        this.appConfig = appConfig;
    }
    
    /**
     * Synthesize speech with given configuration
     */
    public File synthesize(String text, VoiceConfig config) throws Exception {
        TTSProvider provider = providers.computeIfAbsent(config.getProvider(), this::createProvider);
        
        if (provider == null) {
            throw new IllegalArgumentException("Unknown TTS provider: " + config.getProvider());
        }
        
        if (!provider.isAvailable()) {
            throw new RuntimeException("TTS provider not available: " + provider.getName());
        }
        
        // Create temp output file
        String extension = config.getProvider() == VoiceConfig.TTSProviderType.EDGE ? ".mp3" : ".wav";
        File outputFile = File.createTempFile("tts_", extension);
        
        // Convert File to Path and pass voice-specific settings
        Path outputPath = outputFile.toPath();
        Path resultPath;
        
        if (provider instanceof EdgeTTS && config.getVoiceId() != null) {
            // EdgeTTS supports custom voice and rate
            EdgeTTS edge = (EdgeTTS) provider;
            resultPath = edge.synthesizeWithRate(text, outputPath, config.getVoiceId(), config.getSpeed());
        } else {
            // PiperTTS and fallback use standard synthesize
            resultPath = provider.synthesize(text, outputPath);
        }
        
        if (resultPath == null || !resultPath.toFile().exists() || resultPath.toFile().length() == 0) {
            outputFile.delete();
            throw new RuntimeException("TTS synthesis failed");
        }
        
        return resultPath.toFile();
    }
    
    /**
     * Quick synthesize with Edge TTS (Neerja) default settings
     */
    public File synthesizeWithNeerja(String text) throws Exception {
        return synthesize(text, VoiceConfig.defaultEdge());
    }
    
    /**
     * Quick synthesize with Piper default settings
     */
    public File synthesizeWithPiper(String text) throws Exception {
        return synthesize(text, VoiceConfig.defaultPiper());
    }
    
    /**
     * Check provider availability
     */
    public boolean isProviderAvailable(VoiceConfig.TTSProviderType type) {
        TTSProvider provider = providers.computeIfAbsent(type, this::createProvider);
        return provider != null && provider.isAvailable();
    }

    private TTSProvider createProvider(VoiceConfig.TTSProviderType type) {
        return switch (type) {
            case EDGE -> new EdgeTTS(appConfig);
            case PIPER -> new PiperTTS(appConfig);
        };
    }
}
