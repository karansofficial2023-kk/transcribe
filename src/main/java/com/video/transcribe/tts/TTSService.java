package com.video.transcribe.tts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

@Service
public class TTSService {
    
    private static final Logger logger = LoggerFactory.getLogger(TTSService.class);
    
    private final Map<VoiceConfig.TTSProviderType, TTSProvider> providers = new HashMap<>();
    
    public TTSService() {
        // Register providers
        providers.put(VoiceConfig.TTSProviderType.PIPER, new PiperTTS());
        providers.put(VoiceConfig.TTSProviderType.EDGE, new EdgeTTS());
    }
    
    /**
     * Synthesize speech with given configuration
     */
    public File synthesize(String text, VoiceConfig config) throws Exception {
        TTSProvider provider = providers.get(config.getProvider());
        
        if (provider == null) {
            throw new IllegalArgumentException("Unknown TTS provider: " + config.getProvider());
        }
        
        if (!provider.isAvailable()) {
            throw new RuntimeException("TTS provider not available: " + provider.getName());
        }
        
        // Create temp output file
        String extension = config.getProvider() == VoiceConfig.TTSProviderType.EDGE ? ".mp3" : ".wav";
        File outputFile = File.createTempFile("tts_", extension);
        
        boolean success = provider.synthesize(text, outputFile);
        
        if (!success) {
            outputFile.delete();
            throw new RuntimeException("TTS synthesis failed");
        }
        
        return outputFile;
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
        TTSProvider provider = providers.get(type);
        return provider != null && provider.isAvailable();
    }
}