package com.video.transcribe.source;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Local file video source
 */
public class LocalFileSource implements VideoSource {
    
    private final Path filePath;
    private final String fileName;
    
    public LocalFileSource(String filePath) {
        this.filePath = Paths.get(filePath);
        this.fileName = this.filePath.getFileName().toString();
    }
    
    @Override
    public String getId() {
        return "local:" + filePath.toString();
    }
    
    @Override
    public Path getVideoPath() {
        return filePath;
    }
    
    @Override
    public String getFileName() {
        return fileName;
    }
    
    @Override
    public void cleanup() {
        // Nothing to clean for local files
    }
    
    @Override
    public boolean isUrl() {
        return false;
    }
}