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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.video.transcribe.audio.FFmpegAudioExtractor;
import com.video.transcribe.config.AppConfig;
import com.video.transcribe.llm.OllamaClient;
import com.video.transcribe.model.TranscriptData;
import com.video.transcribe.transcription.LocalWhisperTranscriber;
import com.video.transcribe.tts.TTSProvider;
import com.video.transcribe.tts.TTSEngineFactory;

/**
 * Audio-only pipeline:
 * Video → Audio → Transcript (JSON + TXT) → Paraphrase (TXT + JSON) → TTS Audio (WAV/MP3)
 * NO video creation.
 * 
 * TTS Provider: Configurable via application.properties
 *   tts.provider=piper    → Offline local TTS (Piper)
 *   tts.provider=edge     → Online cloud TTS (Edge TTS / Microsoft Azure)
 * 
 * Edge TTS Voices (Indian English):
 *   en-IN-NeerjaNeural  (Female) ← Default
 *   en-IN-PrabhatNeural (Male)
 * 
 * Speed Control (Edge TTS only):
 *   tool.edge_tts.rate=-50%  → Very slow (0.5x)
 *   tool.edge_tts.rate=-25%  → Slow (0.75x)
 *   tool.edge_tts.rate=+0%   → Normal (1.0x) ← Default
 *   tool.edge_tts.rate=+25%  → Fast (1.25x)
 *   tool.edge_tts.rate=+50%  → Very fast (1.5x)
 *   tool.edge_tts.rate=+100% → Double speed (2.0x)
 */
public class VideoParaphrasePipeline {

	private static final Logger logger = LoggerFactory.getLogger(VideoParaphrasePipeline.class);
	private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

	private final FFmpegAudioExtractor audioExtractor;
	private final LocalWhisperTranscriber whisper;
	private final OllamaClient ollama;
	private final ExecutorService executor;
	private final AppConfig config;
	private final TTSProvider tts;

	/**
	 * Initialize pipeline with configuration
	 * TTS provider is auto-selected based on tts.provider property
	 */
	public VideoParaphrasePipeline(AppConfig config) {
		this.config = config;
		this.audioExtractor = new FFmpegAudioExtractor(config);
		this.whisper = new LocalWhisperTranscriber(config);
		this.ollama = new OllamaClient(config);
		
		// Create TTS provider based on config (piper or edge)
		this.tts = TTSEngineFactory.createProvider(config);

		int threads = config.isSequential() ? 1 : config.getThreads();
		this.executor = Executors.newFixedThreadPool(threads);

		new File(config.getTempDir()).mkdirs();
		new File(config.getOutputDir()).mkdirs();

		logger.info("Audio-only pipeline initialized: {} thread(s), mode={}, device={}, TTS={}", 
				threads,
				config.isSequential() ? "sequential" : "parallel", 
				config.getWhisperDevice(),
				tts.getName());
	}

	// ============================================
	// PHASE 1: Video → Audio
	// ============================================

	public Path extractAudio(String videoPath) throws Exception {
		logger.info("=== PHASE 1: Extracting Audio ===");
		Path audioPath = audioExtractor.extractAudioForWhisper(videoPath, config.getTempDir() + "\\audio");
		return audioPath;
	}

	// ============================================
	// PHASE 2: Audio → Transcript (JSON + Plain TXT)
	// ============================================

	public TranscriptData transcribeAudio(Path audioPath, String language, String baseName) throws Exception {
		logger.info("=== PHASE 2: Transcribing ===");

		Path transcriptJson = Paths.get(config.getOutputDir(), baseName + "_transcript.json");
		TranscriptData transcript = whisper.transcribe(audioPath, language, transcriptJson);

		// Save plain text transcript
		Path textPath = Paths.get(config.getOutputDir(), baseName + "_transcript.txt");
		Files.writeString(textPath, transcript.getFullText());
		logger.info("Plain text transcript saved: {}", textPath);

		return transcript;
	}

	// ============================================
	// PHASE 3: Transcript (JSON text) → Paraphrase (TXT + JSON)
	// ============================================

	public String paraphraseScript(String originalText, String style) throws Exception {
		logger.info("=== PHASE 3: Paraphrasing ===");

		if (!ollama.isAvailable()) {
			throw new IOException("Ollama not running! Start: ollama serve");
		}

		return ollama.paraphraseTranscript(originalText, style);
	}

	public void saveParaphrase(String paraphrasedText, String baseName) throws Exception {
		// Save as plain text
		Path txtPath = Paths.get(config.getOutputDir(), baseName + "_paraphrased.txt");
		Files.writeString(txtPath, paraphrasedText);
		logger.info("Paraphrased text saved: {}", txtPath);

		// Save as JSON
		Path jsonPath = Paths.get(config.getOutputDir(), baseName + "_paraphrased.json");
		ParaphraseData data = new ParaphraseData();
		data.setText(paraphrasedText);
		data.setStyle(config.getParaphraseStyle());
		data.setModel(config.getOllamaModel());
		Files.writeString(jsonPath, gson.toJson(data));
		logger.info("Paraphrased JSON saved: {}", jsonPath);
	}

	// ============================================
	// PHASE 4: Paraphrased TXT → TTS Audio (WAV or MP3)
	// ============================================
	// Output format depends on TTS provider:
	//   - Piper TTS → .wav file
	//   - Edge TTS  → .mp3 file
	// ============================================

	public Path generateAudio(String paraphrasedText, String baseName) throws Exception {
		logger.info("=== PHASE 4: TTS ({}) ===", tts.getName());

		Path ttsOutputDir = Paths.get(config.getOutputDir());
		Files.createDirectories(ttsOutputDir);

		return tts.synthesizeLongText(paraphrasedText, ttsOutputDir, baseName);
	}

	// ============================================
	// FULL PIPELINE (Audio-only, NO video)
	// ============================================

	public PipelineResult processVideo(String videoPath, String language, String style) {
		String baseName = getBaseName(videoPath);
		long startTime = System.currentTimeMillis();

		try {
			// Phase 1: Extract audio from video
			Path audioPath = extractAudio(videoPath);

			// Phase 2: Transcribe audio → JSON + TXT
			TranscriptData transcript = transcribeAudio(audioPath, language, baseName);

			// Phase 3: Paraphrase using transcript text → TXT + JSON
			String paraphrased = paraphraseScript(transcript.getFullText(), style);
			saveParaphrase(paraphrased, baseName);

			// Phase 4: TTS from paraphrased text → Audio
			Path audioOutput = generateAudio(paraphrased, baseName);

			// Cleanup temp if configured
			if (config.cleanupTempAudio()) {
				cleanupTemp(baseName);
			}

			return new PipelineResult(true, audioOutput, transcript, paraphrased, 
					System.currentTimeMillis() - startTime, null);

		} catch (Exception e) {
			logger.error("Pipeline failed: {}", e.getMessage(), e);
			return new PipelineResult(false, null, null, null, 
					System.currentTimeMillis() - startTime, e.getMessage());
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
	// PARAPHRASE DATA MODEL
	// ============================================

	public static class ParaphraseData {
		private String text;
		private String style;
		private String model;

		public String getText() { return text; }
		public void setText(String text) { this.text = text; }
		public String getStyle() { return style; }
		public void setStyle(String style) { this.style = style; }
		public String getModel() { return model; }
		public void setModel(String model) { this.model = model; }
	}

	// ============================================
	// RESULT CLASS
	// ============================================

	public static class PipelineResult {
		public final boolean success;
		public final Path audioOutput;      // TTS audio file (.wav for Piper, .mp3 for Edge)
		public final TranscriptData transcript;
		public final String paraphrasedText;
		public final long totalTimeMs;
		public final String error;

		public PipelineResult(boolean success, Path audioOutput, TranscriptData transcript, 
				String paraphrasedText, long totalTimeMs, String error) {
			this.success = success;
			this.audioOutput = audioOutput;
			this.transcript = transcript;
			this.paraphrasedText = paraphrasedText;
			this.totalTimeMs = totalTimeMs;
			this.error = error;
		}
	}
}