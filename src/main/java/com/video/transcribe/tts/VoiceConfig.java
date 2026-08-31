package com.video.transcribe.tts;

public class VoiceConfig {
    
    public enum TTSProviderType {
        PIPER, EDGE
    }
    
    private TTSProviderType provider;
    private String voiceId;           // e.g., "en-IN-NeerjaNeural" or piper model name
    private String speed;             // e.g., "+50%", "-30%", "+0%"
    private String language;          // e.g., "en", "hi"
    
    // Constructors
    public VoiceConfig() {}
    
    public VoiceConfig(TTSProviderType provider, String voiceId, String speed) {
        this.provider = provider;
        this.voiceId = voiceId;
        this.speed = speed;
    }
    
    // Getters and Setters
    public TTSProviderType getProvider() { return provider; }
    public void setProvider(TTSProviderType provider) { this.provider = provider; }
    
    public String getVoiceId() { return voiceId; }
    public void setVoiceId(String voiceId) { this.voiceId = voiceId; }
    
    public String getSpeed() { return speed; }
    public void setSpeed(String speed) { this.speed = speed; }
    
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    
    // Default configs
    public static VoiceConfig defaultPiper() {
        return new VoiceConfig(TTSProviderType.PIPER, "en_US-lessac-medium", "+0%");
    }
    
    public static VoiceConfig defaultEdge() {
        return new VoiceConfig(TTSProviderType.EDGE, "en-IN-NeerjaNeural", "+0%");
    }
}