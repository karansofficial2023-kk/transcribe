package com.video.transcribe.source;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * URL video source - downloads video from URL to temp location
 */
public class UrlVideoSource implements VideoSource {
    
    private static final Logger logger = LoggerFactory.getLogger(UrlVideoSource.class);
    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)  // Long timeout for large downloads
        .build();
    
    private final String url;
    private final String originalFileName;
    private Path downloadedPath;
    private final String tempDir;
    
    public UrlVideoSource(String url, String tempDir) {
        this.url = url;
        this.tempDir = tempDir;
        
        // Extract filename from URL
        String name = url.substring(url.lastIndexOf('/') + 1);
        if (name.isEmpty() || name.contains("?")) {
            name = "video_" + UUID.randomUUID().toString().substring(0, 8) + ".mp4";
        }
        // Remove query parameters
        int queryIndex = name.indexOf('?');
        if (queryIndex > 0) {
            name = name.substring(0, queryIndex);
        }
        this.originalFileName = name;
    }
    
    @Override
    public String getId() {
        return "url:" + url;
    }
    
    @Override
    public Path getVideoPath() throws IOException {
        if (downloadedPath != null && Files.exists(downloadedPath)) {
            return downloadedPath;
        }
        
        // Create temp directory
        File temp = new File(tempDir + "/downloads");
        temp.mkdirs();
        
        downloadedPath = Paths.get(tempDir, "downloads", originalFileName);
        
        logger.info("Downloading video from URL: {} → {}", url, downloadedPath);
        
        Request request = new Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.0")
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Download failed: HTTP " + response.code());
            }
            
            long contentLength = response.body() != null ? response.body().contentLength() : -1;
            logger.info("Download size: {} bytes", contentLength);
            
            try (InputStream in = response.body().byteStream()) {
                Files.copy(in, downloadedPath, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        
        logger.info("Download complete: {} ({} bytes)", downloadedPath, Files.size(downloadedPath));
        return downloadedPath;
    }
    
    @Override
    public String getFileName() {
        return originalFileName;
    }
    
    @Override
    public void cleanup() {
        if (downloadedPath != null) {
            try {
                Files.deleteIfExists(downloadedPath);
                logger.info("Cleaned up downloaded file: {}", downloadedPath);
            } catch (IOException e) {
                logger.warn("Failed to cleanup: {}", downloadedPath);
            }
        }
    }
    
    @Override
    public boolean isUrl() {
        return true;
    }
}