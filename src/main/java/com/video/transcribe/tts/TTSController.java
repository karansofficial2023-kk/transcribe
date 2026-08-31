package com.video.transcribe.tts;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tts")
public class TTSController {
    
    @Autowired
    private TTSService ttsService;
    
    /**
     * Synthesize with voice selection and speed control
     */
    @PostMapping("/synthesize")
    public ResponseEntity<byte[]> synthesize(
            @RequestParam String text,
            @RequestParam(defaultValue = "EDGE") VoiceConfig.TTSProviderType provider,
            @RequestParam(defaultValue = "en-IN-NeerjaNeural") String voice,
            @RequestParam(defaultValue = "+0%") String speed) {
        
        try {
            VoiceConfig config = new VoiceConfig();
            config.setProvider(provider);
            config.setVoiceId(voice);
            config.setSpeed(speed);
            
            File audioFile = ttsService.synthesize(text, config);
            
            byte[] audioData = Files.readAllBytes(audioFile.toPath());
            audioFile.delete(); // Clean up temp file
            
            String contentType = provider == VoiceConfig.TTSProviderType.EDGE 
                ? "audio/mpeg" : "audio/wav";
            
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=speech." + (provider == VoiceConfig.TTSProviderType.EDGE ? "mp3" : "wav"))
                .contentType(MediaType.parseMediaType(contentType))
                .body(audioData);
                
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * Get available voices for each provider
     */
    @GetMapping("/voices")
    public ResponseEntity<Map<String, Object>> getVoices() {
        Map<String, Object> response = new HashMap<>();
        
        // Edge TTS voices (popular ones)
        List<Map<String, String>> edgeVoices = Arrays.asList(
            Map.of("id", "en-IN-NeerjaNeural", "name", "Neerja (English India)", "gender", "Female"),
            Map.of("id", "en-IN-PrabhatNeural", "name", "Prabhat (English India)", "gender", "Male"),
            Map.of("id", "en-US-AriaNeural", "name", "Aria (English US)", "gender", "Female"),
            Map.of("id", "en-GB-SoniaNeural", "name", "Sonia (English UK)", "gender", "Female"),
            Map.of("id", "hi-IN-SwaraNeural", "name", "Swara (Hindi India)", "gender", "Female"),
            Map.of("id", "hi-IN-MadhurNeural", "name", "Madhur (Hindi India)", "gender", "Male")
        );
        
        // Piper voices (local models you have downloaded)
        List<Map<String, String>> piperVoices = Arrays.asList(
            Map.of("id", "en_US-lessac-medium", "name", "Lessac (English US)", "gender", "Female"),
            Map.of("id", "en_GB-cori-medium", "name", "Cori (English UK)", "gender", "Female")
            // Add your downloaded piper models here
        );
        
        response.put("edge", edgeVoices);
        response.put("piper", piperVoices);
        response.put("speedOptions", Arrays.asList(
            Map.of("label", "Very Slow", "value", "-50%"),
            Map.of("label", "Slow", "value", "-25%"),
            Map.of("label", "Normal", "value", "+0%"),
            Map.of("label", "Fast", "value", "+25%"),
            Map.of("label", "Very Fast", "value", "+50%")
        ));
        
        return ResponseEntity.ok(response);
    }
}