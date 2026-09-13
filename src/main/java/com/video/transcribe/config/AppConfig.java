package com.video.transcribe.config;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads and provides access to application.properties
 */
public class AppConfig {

	private static final Logger logger = LoggerFactory.getLogger(AppConfig.class);
	private final Properties props;

	public AppConfig() {
		this.props = new Properties();
		loadConfig();
	}

	private void loadConfig() {
		// Try multiple locations
		String[] locations = { "./application.properties", // Current directory
				"application.properties", // Current directory (relative)
				"src/main/resources/application.properties", // Maven project
				System.getProperty("user.home") + "/.video-processor/application.properties" // User home
		};

		boolean loaded = false;
		for (String location : locations) {
			Path path = Paths.get(location);
			if (Files.exists(path)) {
				try (InputStream is = new FileInputStream(path.toFile())) {
					props.load(is);
					logger.info("Loaded config from: {}", path.toAbsolutePath());
					loaded = true;
					break;
				} catch (IOException e) {
					logger.warn("Failed to load {}: {}", location, e.getMessage());
				}
			}
		}

		if (!loaded) {
			logger.warn("No application.properties found. Using defaults.");
			loadDefaults();
		}
	}

	private void loadDefaults() {
		props.setProperty("video.input.type", "folder");
		props.setProperty("video.input.folder", "./videos");
		props.setProperty("video.input.watch", "true");
		props.setProperty("video.output.dir", "./output");
		props.setProperty("video.processing.mode", "sequential");
		props.setProperty("video.processing.threads", "1");
		props.setProperty("tool.ffmpeg.path", "ffmpeg");
		props.setProperty("tool.ffprobe.path", "ffprobe");
		props.setProperty("tool.python.path", "python3");
		props.setProperty("tool.whisper.script", "./whisper_transcribe.py");
		props.setProperty("tool.whisper.model", "base");
		props.setProperty("tool.whisper.device", "cuda");
		props.setProperty("tool.ollama.url", "http://localhost:11434");
		props.setProperty("tool.ollama.model", "llama3.1:8b");
		props.setProperty("tool.piper.path", "piper");
		props.setProperty("storyboard.animation.enabled", "true");
		props.setProperty("temp.dir", "./temp");
		props.setProperty("cleanup.downloads", "true");
		props.setProperty("download.timeout.seconds", "300");
		props.setProperty("logging.level", "INFO");
	}

	// === GETTERS ===

	public String getString(String key) {
		return props.getProperty(key);
	}

	public String getString(String key, String defaultValue) {
		return props.getProperty(key, defaultValue);
	}

	public int getInt(String key, int defaultValue) {
		try {
			return Integer.parseInt(props.getProperty(key, String.valueOf(defaultValue)));
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}

	public boolean getBoolean(String key, boolean defaultValue) {
		String val = props.getProperty(key, String.valueOf(defaultValue));
		return Boolean.parseBoolean(val);
	}

	public int getValidationThreshold() {
		return getInt("validation.threshold", 80);
	}

	public int getValidationMaxRetries() {
		return getInt("validation.max.retries", 3);
	}

	public boolean isStoryboardAnimationEnabled() {
		return getBoolean("storyboard.animation.enabled", true);
	}
	// === CONVENIENCE METHODS ===

	// Input
	public String getInputType() {
		return getString("video.input.type", "folder");
	}

	public String getInputFolder() {
		return getString("video.input.folder", "./videos");
	}

	public String getInputUrl() {
		return getString("video.input.url", "");
	}

	public String getInputUrlFile() {
		return getString("video.input.urlfile", "");
	}

	public boolean isWatchEnabled() {
		return getBoolean("video.input.watch", true);
	}

	// Output
	public String getOutputDir() {
		return getString("video.output.dir", "./output");
	}

	public boolean keepTranscript() {
		return getBoolean("video.output.keep.transcript", true);
	}

	public boolean keepRephrased() {
		return getBoolean("video.output.keep.rephrased", true);
	}

	// Processing
	public boolean isSequential() {
		return "sequential".equalsIgnoreCase(getString("video.processing.mode", "sequential"));
	}

	public int getThreads() {
		return getInt("video.processing.threads", 1);
	}

	// Language & Style
	public String getLanguage() {
		String lang = getString("video.language", "auto");
		return "auto".equals(lang) ? null : lang;
	}

	public String getParaphraseStyle() {
		return getString("video.paraphrase.style", "professional");
	}

	// Tool Paths
	public String getFfmpegPath() {
		return getString("tool.ffmpeg.path", "ffmpeg");
	}

	public String getFfprobePath() {
		return getString("tool.ffprobe.path", "ffprobe");
	}

	public String getPythonPath() {
		return getString("tool.python.path", "python3");
	}

	public String getWhisperScript() {
		return getString("tool.whisper.script", "./whisper_transcribe.py");
	}

	public String getWhisperModel() {
		return getString("tool.whisper.model", "base");
	}

	public String getWhisperDevice() {
		return getString("tool.whisper.device", "cuda");
	}

	public String getOllamaUrl() {
		return getString("tool.ollama.url", "http://localhost:11434");
	}

	public String getOllamaModel() {
		return getString("tool.ollama.model", "llama3.1:8b");
	}

	public String getPiperPath() {
		return getString("tool.piper.path", "piper");
	}

	public String getPiperModel() {
		return getString("tool.piper.model", "");
	}

	public String getPiperModelConfig() {
		return getString("tool.piper.model.config", "");
	}

	// Temp & Cleanup
	public String getTempDir() {
		return getString("temp.dir", "./temp");
	}

	public boolean cleanupDownloads() {
		return getBoolean("cleanup.downloads", true);
	}

	public boolean cleanupTempAudio() {
		return getBoolean("cleanup.temp.audio", true);
	}

	// Download
	public int getDownloadTimeout() {
		return getInt("download.timeout.seconds", 300);
	}

	public int getDownloadRetryCount() {
		return getInt("download.retry.count", 3);
	}

	// Logging
	public String getLogLevel() {
		return getString("logging.level", "INFO");
	}

	public String getLogFile() {
		return getString("logging.file", "./logs/video-processor.log");
	}

	/**
	 * Print all configuration
	 */
	public void printConfig() {
		System.out.println("\n" + "=".repeat(60));
		System.out.println("  CONFIGURATION");
		System.out.println("=".repeat(60));

		// Input
		System.out.println("  Input Type:     " + getInputType());
		System.out.println("  Input Folder:   " + getInputFolder());
		System.out.println("  Watch Folder:   " + isWatchEnabled());

		// Output
		System.out.println("  Output Dir:     " + getOutputDir());

		// Processing
		System.out.println("  Mode:           " + (isSequential() ? "Sequential (one-by-one)" : "Parallel"));
		System.out.println("  Threads:        " + getThreads());

		// Tools
		System.out.println("  FFmpeg:         " + getFfmpegPath());
		System.out.println("  Python:         " + getPythonPath());
		System.out.println("  Whisper Model:  " + getWhisperModel() + " (" + getWhisperDevice() + ")");
		System.out.println("  Ollama Model:   " + getOllamaModel());
		System.out.println("  Ollama URL:     " + getOllamaUrl());
		System.out.println("  Piper Model:    " + getPiperModel());

		// Temp
		System.out.println("  Temp Dir:       " + getTempDir());

		System.out.println("=".repeat(60));
	}
}
