package com.video.transcribe;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
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
import com.video.transcribe.scene.SceneStoryboardGenerator;
import com.video.transcribe.scene.StoryboardProjectMaterials;
import com.video.transcribe.scene.StoryboardProjectMaterialsLoader;
import com.video.transcribe.scene.StoryboardDocument;
import com.video.transcribe.scene.StoryboardQualityGate;
import com.video.transcribe.transcription.LocalWhisperTranscriber;
import com.video.transcribe.transcription.TranscriptQualityGate;
import com.video.transcribe.tts.TTSProvider;
import com.video.transcribe.tts.TTSEngineFactory;
import com.video.transcribe.validator.AccuracyValidator;
import com.video.transcribe.validator.ValidationResult;

/**
 * Enhanced Audio-only pipeline:
 * Video → Audio → Transcript (JSON + TXT) → Paraphrase (TXT + JSON) 
 * → [VALIDATE] → [SCENE STORYBOARD] → [DOCX EXPORT] → TTS Audio (WAV/MP3)
 * 
 * NEW Features Added:
 * 1. AccuracyValidator - Checks paraphrase fidelity against original transcript
 * 2. SceneStoryboardGenerator - Creates structured scene breakdown from paraphrased text
 * 3. StoryboardDocxExporter - Exports scenes to Word document matching sample format
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
	private final AccuracyValidator validator;
	private final SceneStoryboardGenerator sceneGenerator;
	private final StoryboardDocxExporter docxExporter;

	/**
	 * Initialize pipeline with configuration.
	 * TTS provider is auto-selected based on tts.provider property.
	 */
	public VideoParaphrasePipeline(AppConfig config) {
		this.config = config;
		this.audioExtractor = new FFmpegAudioExtractor(config);
		this.whisper = new LocalWhisperTranscriber(config);
		this.ollama = new OllamaClient(config);
		this.validator = new AccuracyValidator(ollama);
		this.sceneGenerator = new SceneStoryboardGenerator(
				ollama,
				config.isStoryboardAnimationEnabled(),
				config.getStoryboardVideoProvider(),
				config.isStoryboardCurriculumEnrichmentEnabled());
		this.docxExporter = new StoryboardDocxExporter(config.getStoryboardVideoProvider());

		// Create TTS provider based on config (piper or edge)
		this.tts = TTSEngineFactory.createProvider(config);

		int threads = config.isSequential() ? 1 : config.getThreads();
		this.executor = Executors.newFixedThreadPool(threads);

		new File(config.getTempDir()).mkdirs();
		new File(config.getOutputDir()).mkdirs();

		logger.info("Enhanced pipeline initialized: {} thread(s), mode={}, device={}, TTS={}",
				threads,
				config.isSequential() ? "sequential" : "parallel",
				config.getWhisperDevice(),
				tts.getName());
		logger.info("Storyboard video provider: {}", config.getStoryboardVideoProvider());
		logger.info("Storyboard curriculum enrichment: {}", config.isStoryboardCurriculumEnrichmentEnabled());
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
	// PHASE 3b: Validate Paraphrase Accuracy
	// ============================================

	public ValidationResult validateParaphrase(String originalText, String paraphrasedText, String baseName) throws Exception {
		logger.info("=== PHASE 3b: Validating Paraphrase Accuracy ===");
		ValidationResult result = validator.validate(originalText, paraphrasedText);

		// Save validation report
		Path validationPath = Paths.get(config.getOutputDir(), baseName + "_validation.json");
		Files.writeString(validationPath, gson.toJson(result));
		logger.info("Validation report saved: {} | Score: {}/100 | Passed: {}",
				validationPath, result.getOverallScore(), result.isPassed());

		if (result.getOverallScore() < config.getValidationThreshold()) {
			logger.warn("LOW ACCURACY SCORE: {}% (threshold: {}%)",
				result.getOverallScore(), config.getValidationThreshold());
		}
		return result;
	}

	// ============================================
	// PHASE 3c: Generate Scene Storyboard
	// ============================================

	public StoryboardDocument generateStoryboard(String paraphrasedText, String baseName) throws Exception {
		logger.info("=== PHASE 3c: Generating Scene Storyboard ===");
		StoryboardProjectMaterials materials = StoryboardProjectMaterialsLoader.load(
			config.getStoryboardMaterialsDir(), baseName);
		if (!materials.isEmpty()) {
			logger.info("Loaded storyboard project materials: {} approved/reference assets",
				materials.approvedAssets().size());
		}
		StoryboardDocument storyboard = sceneGenerator.generateStoryboard(
			paraphrasedText, baseName, materials);
		StoryboardQualityGate.validate(storyboard);

		// Save as JSON
		Path storyboardJson = Paths.get(config.getOutputDir(), baseName + "_storyboard.json");
		Files.writeString(storyboardJson, gson.toJson(storyboard));
		logger.info("Storyboard JSON saved: {}", storyboardJson);

		// Export as Word document
		Path docxPath = Paths.get(config.getOutputDir(), baseName + "_storyboard.docx");
		docxExporter.export(storyboard, docxPath.toString());
		logger.info("Storyboard DOCX saved: {}", docxPath);

		return storyboard;
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
	// FULL ENHANCED PIPELINE
	// ============================================

	public PipelineResult processVideo(String videoPath, String language, String style) {
		String baseName = getBaseName(videoPath);
		long startTime = System.currentTimeMillis();

		try {
			// Phase 1: Extract audio from video
			Path audioPath = extractAudio(videoPath);

			// Phase 2: Transcribe audio → JSON + TXT
			TranscriptData transcript = transcribeAudio(audioPath, language, baseName);
			String originalText = transcript.getFullText();
			if (!TranscriptQualityGate.hasUsableSpeech(transcript)) {
				logSkippedVideo(videoPath, baseName, "No speech detected or only music/background audio");
				logger.warn("Skipping video with no usable speech: {}", videoPath);
				return new PipelineResult(false, null, transcript, null,
						null, null, System.currentTimeMillis() - startTime,
						"Skipped: no speech detected or only music/background audio");
			}

			// Phase 3 + 3b: Paraphrase, validate, and retry until content coverage passes
			ParaphraseValidation paraphraseValidation = paraphraseUntilValid(originalText, style, baseName);
			String paraphrased = paraphraseValidation.paraphrasedText;
			ValidationResult validation = paraphraseValidation.validation;
			saveParaphrase(paraphrased, baseName);

			// Phase 3c: Generate scene storyboard
			StoryboardDocument storyboard = generateStoryboard(paraphrased, baseName);

			// Phase 4: TTS from paraphrased text → Audio
			Path audioOutput = generateAudio(paraphrased, baseName);

			// Cleanup temp if configured
			if (config.cleanupTempAudio()) {
				cleanupTemp(baseName);
			}

			return new PipelineResult(true, audioOutput, transcript, paraphrased,
					storyboard, validation, System.currentTimeMillis() - startTime, null);

		} catch (Exception e) {
			logger.error("Pipeline failed: {}", e.getMessage(), e);
			return new PipelineResult(false, null, null, null,
					null, null, System.currentTimeMillis() - startTime, e.getMessage());
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

	private ParaphraseValidation paraphraseUntilValid(String originalText, String style, String baseName) throws Exception {
		String paraphrased = null;
		ValidationResult validation = null;
		int maxAttempts = Math.max(1, config.getValidationMaxRetries());

		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			logger.info("Paraphrase attempt {}/{}", attempt, maxAttempts);
			if (attempt == 1) {
				paraphrased = paraphraseScript(originalText, style);
			} else {
				paraphrased = ollama.repairParaphrase(
					originalText,
					paraphrased,
					formatValidationIssues(validation),
					style
				);
			}

			try {
				paraphrased = ollama.proofreadEducationalTerminology(paraphrased);
			} catch (IOException e) {
				logger.warn("SME terminology proofread could not be applied; validating the paraphrase as generated: {}",
					e.getMessage());
			}

			validation = validateParaphrase(originalText, paraphrased, baseName);
			if (validation.getOverallScore() >= config.getValidationThreshold()) {
				logger.info("Paraphrase accepted with validation score {}/100 on attempt {}",
					validation.getOverallScore(), attempt);
				return new ParaphraseValidation(paraphrased, validation);
			}

			logger.warn("Paraphrase score {}/100 below threshold {}; retrying if attempts remain",
				validation.getOverallScore(), config.getValidationThreshold());
		}

		if (validation != null && isAcceptableAfterRetries(validation)) {
			logger.warn("Accepting paraphrase after retries with near-threshold score {}/100. Review validation report for warnings.",
				validation.getOverallScore());
			return new ParaphraseValidation(paraphrased, validation);
		}

		throw new IOException("Paraphrase validation failed after " + maxAttempts
			+ " attempts. Last score: " + (validation != null ? validation.getOverallScore() : "none"));
	}

	private boolean isAcceptableAfterRetries(ValidationResult validation) {
		double nearThreshold = Math.max(70.0, config.getValidationThreshold() - 10.0);
		return validation.getOverallScore() >= nearThreshold
			&& validation.getFactualConsistencyScore() >= 70.0
			&& validation.getTopicCoverageScore() >= 70.0
			&& validation.getHallucinationScore() >= 80.0;
	}

	private String formatValidationIssues(ValidationResult validation) {
		if (validation == null) {
			return "No validation details available. Improve topic coverage and factual consistency.";
		}
		StringBuilder text = new StringBuilder();
		text.append("Overall score: ").append(validation.getOverallScore()).append("/100\n");
		text.append("Semantic similarity: ").append(validation.getSemanticSimilarityScore()).append("/100\n");
		text.append("Factual consistency: ").append(validation.getFactualConsistencyScore()).append("/100\n");
		text.append("Key concepts: ").append(validation.getKeyConceptPreservationScore()).append("/100\n");
		text.append("Topic coverage: ").append(validation.getTopicCoverageScore()).append("/100\n");
		text.append("Hallucination safety: ").append(validation.getHallucinationScore()).append("/100\n");
		if (validation.getIssues() != null && !validation.getIssues().isEmpty()) {
			text.append("Issues:\n");
			for (String issue : validation.getIssues()) {
				text.append("- ").append(issue).append("\n");
			}
		}
		return text.toString();
	}

	private void logSkippedVideo(String videoPath, String baseName, String reason) {
		try {
			Path skippedLog = Paths.get(config.getOutputDir(), "skipped_videos.csv");
			if (!Files.exists(skippedLog)) {
				Files.writeString(skippedLog, "timestamp,baseName,videoPath,reason\n",
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			}
			String row = String.format("%s,%s,%s,%s%n",
				java.time.Instant.now(),
				csv(baseName),
				csv(videoPath),
				csv(reason)
			);
			Files.writeString(skippedLog, row, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (Exception e) {
			logger.warn("Failed to write skipped video log: {}", e.getMessage());
		}
	}

	private String csv(String value) {
		if (value == null) {
			return "\"\"";
		}
		return "\"" + value.replace("\"", "\"\"") + "\"";
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

		public String getText() {
			return text;
		}

		public void setText(String text) {
			this.text = text;
		}

		public String getStyle() {
			return style;
		}

		public void setStyle(String style) {
			this.style = style;
		}

		public String getModel() {
			return model;
		}

		public void setModel(String model) {
			this.model = model;
		}
	}

	private static class ParaphraseValidation {
		private final String paraphrasedText;
		private final ValidationResult validation;

		private ParaphraseValidation(String paraphrasedText, ValidationResult validation) {
			this.paraphrasedText = paraphrasedText;
			this.validation = validation;
		}
	}

	// ============================================
	// ENHANCED RESULT CLASS
	// ============================================

	public static class PipelineResult {
		public final boolean success;
		public final Path audioOutput;      // TTS audio file (.wav for Piper, .mp3 for Edge)
		public final TranscriptData transcript;
		public final String paraphrasedText;
		public final StoryboardDocument storyboard;  // NEW: Scene breakdown
		public final ValidationResult validation;     // NEW: Accuracy report
		public final long totalTimeMs;
		public final String error;

		public PipelineResult(boolean success, Path audioOutput, TranscriptData transcript,
				String paraphrasedText, StoryboardDocument storyboard, ValidationResult validation,
				long totalTimeMs, String error) {
			this.success = success;
			this.audioOutput = audioOutput;
			this.transcript = transcript;
			this.paraphrasedText = paraphrasedText;
			this.storyboard = storyboard;
			this.validation = validation;
			this.totalTimeMs = totalTimeMs;
			this.error = error;
		}
	}
}
