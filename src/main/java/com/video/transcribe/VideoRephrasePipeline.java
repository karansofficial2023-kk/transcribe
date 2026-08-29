package com.video.transcribe;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.video.transcribe.audio.FFmpegAudioExtractor;
import com.video.transcribe.config.AppConfig;
import com.video.transcribe.llm.OllamaClient;
import com.video.transcribe.model.TranscriptData;
import com.video.transcribe.transcription.LocalWhisperTranscriber;
import com.video.transcribe.tts.PiperTTS;

/**
 * Complete pipeline using configuration from application.properties
 */
public class VideoRephrasePipeline {

	private static final Logger logger = LoggerFactory.getLogger(VideoRephrasePipeline.class);

	private final FFmpegAudioExtractor audioExtractor;
	private final LocalWhisperTranscriber whisper;
	private final OllamaClient ollama;
	private final PiperTTS tts;
	private final ExecutorService executor;
	private final AppConfig config;

	/**
	 * Constructor using AppConfig - reads all paths/settings from
	 * application.properties
	 */
	public VideoRephrasePipeline(AppConfig config) {
		this.config = config;
		this.audioExtractor = new FFmpegAudioExtractor(config);
		this.whisper = new LocalWhisperTranscriber(config);
		this.ollama = new OllamaClient(config);
		this.tts = new PiperTTS(config);

		int threads = config.isSequential() ? 1 : config.getThreads();
		this.executor = Executors.newFixedThreadPool(threads);

		new File(config.getTempDir()).mkdirs();
		new File(config.getOutputDir()).mkdirs();

		logger.info("Pipeline initialized: {} thread(s), mode={}, device={}", threads,
				config.isSequential() ? "sequential" : "parallel", config.getWhisperDevice());
	}

	// ============================================
	// PHASE 1: Video → Transcript
	// ============================================

	public TranscriptData transcribeVideo(String videoPath, String language) throws Exception {
		logger.info("=== PHASE 1: Transcribing ===");

		Path audioPath = audioExtractor.extractAudioForWhisper(videoPath, config.getTempDir() + "\\audio");
		Path transcriptJson = Paths.get(config.getOutputDir(), getBaseName(videoPath) + "_transcript.json");
		TranscriptData transcript = whisper.transcribe(audioPath, language, transcriptJson);

		if (config.keepTranscript()) {
			Path textPath = Paths.get(config.getOutputDir(), getBaseName(videoPath) + "_transcript.txt");
			Files.writeString(textPath, transcript.getFullText());
		}

		return transcript;
	}

	// ============================================
	// PHASE 2: Transcript → Rephrased Script
	// ============================================

	public String rephraseScript(String originalText, String style) throws Exception {
		logger.info("=== PHASE 2: Rephrasing ===");

		if (!ollama.isAvailable()) {
			throw new IOException("Ollama not running! Start: ollama serve");
		}

		return ollama.rephraseTranscript(originalText, style);
	}

	// ============================================
	// PHASE 3: Rephrased Script → New Audio (TTS)
	// ============================================

	public Path generateAudio(String rephrasedText, String baseName) throws Exception {
		logger.info("=== PHASE 3: TTS ===");

		Path ttsOutput = Paths.get(config.getTempDir(), "tts", baseName + "_new_audio.wav");
		Files.createDirectories(ttsOutput.getParent());

		return tts.synthesizeLongText(rephrasedText, ttsOutput.getParent(), baseName);
	}

	// ============================================
	// PHASE 4: New Audio + Original Video → Final Video
	// ============================================

	public Path createFinalVideo(String originalVideoPath, String newAudioPath, String baseName) throws Exception {
		logger.info("=== PHASE 4: Final Video ===");

		Path finalVideo = Paths.get(config.getOutputDir(), baseName + "_REPHRASED.mp4");
		audioExtractor.mergeAudioVideo(originalVideoPath, newAudioPath, finalVideo.toString());

		return finalVideo;
	}

	// ============================================
	// FULL PIPELINE
	// ============================================

	public PipelineResult processVideo(String videoPath, String language, String style) {
		String baseName = getBaseName(videoPath);
		long startTime = System.currentTimeMillis();

		try {
			// Phase 1: Transcribe
			TranscriptData transcript = transcribeVideo(videoPath, language);

			// Phase 2: Rephrase
			String rephrased = rephraseScript(transcript.getFullText(), style);

			if (config.keepRephrased()) {
				Files.writeString(Paths.get(config.getOutputDir(), baseName + "_rephrased.txt"), rephrased);
			}

			// Phase 3: TTS
			Path newAudio = generateAudio(rephrased, baseName);

			// Phase 4: Merge
			Path finalVideo = createFinalVideo(videoPath, newAudio.toString(), baseName);

			// Cleanup temp if configured
			if (config.cleanupTempAudio()) {
				cleanupTemp(baseName);
			}

			return new PipelineResult(true, finalVideo, transcript, rephrased, System.currentTimeMillis() - startTime,
					null);

		} catch (Exception e) {
			logger.error("Pipeline failed: {}", e.getMessage(), e);
			return new PipelineResult(false, null, null, null, System.currentTimeMillis() - startTime, e.getMessage());
		}
	}

	private void cleanupTemp(String baseName) {
		try {
			Path tempDir = Paths.get(config.getTempDir());
			if (Files.exists(tempDir)) {
				Files.walk(tempDir).filter(p -> p.getFileName().toString().startsWith(baseName)).forEach(p -> {
					try {
						Files.deleteIfExists(p);
					} catch (Exception ignored) {
					}
				});
			}
		} catch (Exception e) {
			logger.warn("Cleanup failed: {}", e.getMessage());
		}
	}

	private String getBaseName(String path) {
		String name = new File(path).getName();
		int dot = name.lastIndexOf('.');
		return dot > 0 ? name.substring(0, dot) : name;
	}

	public void shutdown() {
		executor.shutdown();
	}

	// ============================================
	// RESULT CLASS
	// ============================================

	public static class PipelineResult {
		public final boolean success;
		public final Path finalVideo;
		public final TranscriptData transcript;
		public final String rephrasedText;
		public final long totalTimeMs;
		public final String error;

		public PipelineResult(boolean success, Path finalVideo, TranscriptData transcript, String rephrasedText,
				long totalTimeMs, String error) {
			this.success = success;
			this.finalVideo = finalVideo;
			this.transcript = transcript;
			this.rephrasedText = rephrasedText;
			this.totalTimeMs = totalTimeMs;
			this.error = error;
		}
	}
}