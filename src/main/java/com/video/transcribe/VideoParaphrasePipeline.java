package com.video.transcribe;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
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
	private final OllamaClient fastOllama;
	private volatile String detectedLanguage = "";
	private final ExecutorService executor;
	private final AppConfig config;
	private final TTSProvider tts;
	private final AccuracyValidator validator;
	private final SceneStoryboardGenerator sceneGenerator;
	private final StoryboardDocxExporter docxExporter;

	/**
	 * Initialize pipeline with configuration. TTS is optional because the
	 * production storyboard workflow normally stops after DOCX export.
	 */
	public VideoParaphrasePipeline(AppConfig config) {
		this.config = config;
		this.audioExtractor = new FFmpegAudioExtractor(config);
		this.whisper = new LocalWhisperTranscriber(config);
		this.ollama = new OllamaClient(config);
		this.validator = new AccuracyValidator(ollama);
		this.fastOllama = new OllamaClient(config, config.getOllamaFastModel());
		this.sceneGenerator = new SceneStoryboardGenerator(
				fastOllama,
				config.isStoryboardAnimationEnabled(),
				config.getStoryboardVideoProvider(),
				config.isStoryboardCurriculumEnrichmentEnabled());
		if (config.isStoryboardVisualDirectorEnabled()) {
			this.sceneGenerator.setVisualDirector(new com.video.transcribe.scene.VisualDirector(
				fastOllama, sceneGenerator::finishImagePrompt, sceneGenerator::enforceFormulaRouting));
		}
		this.docxExporter = new StoryboardDocxExporter(config.getStoryboardVideoProvider());

		this.tts = config.isTtsEnabled() ? TTSEngineFactory.createProvider(config) : null;

		int threads = config.isSequential() ? 1 : config.getThreads();
		this.executor = Executors.newFixedThreadPool(threads);

		new File(config.getTempDir()).mkdirs();
		new File(config.getOutputDir()).mkdirs();

		logger.info("Enhanced pipeline initialized: {} thread(s), mode={}, device={}, TTS={}",
				threads,
				config.isSequential() ? "sequential" : "parallel",
				config.getWhisperDevice(),
				config.isTtsEnabled() ? tts.getName() : "disabled (storyboard-only mode)");
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
		ollama.unload();                      // one heavy model at a time on a 12 GB card
		fastOllama.unload();
		java.util.List<String> hotwords = com.video.transcribe.transcription.Glossary
			.load(language, config.getStoryboardMaterialsDir(), baseName).hotwords();
		TranscriptData transcript = whisper.transcribe(audioPath, language, transcriptJson, hotwords);
		if (transcript != null && transcript.getLanguage() != null) {
			detectedLanguage = transcript.getLanguage();
			logger.info("Detected source language: {}", detectedLanguage);
		}

		if (transcript != null && transcript.getFullText() != null) {
			transcript.setText(com.video.transcribe.transcription.TranscriptPunctuator.restore(transcript.getFullText(), fastOllama));
		}

		if (transcript == null || transcript.getFullText() == null) {
			throw new IOException("Transcription produced no text for " + baseName);
		}
		String coverageProblem = com.video.transcribe.transcription.SpeechCoverage.problem(transcript, transcript.getDuration());
		if (coverageProblem != null) {
			throw new IOException("No usable narration in " + baseName + ": " + coverageProblem);
		}
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

	/** Writes the finished storyboard in the extra languages from storyboard.target.languages (translation only; no re-planning). */
	private void localizeStoryboard(StoryboardDocument storyboard, String baseName) {
		String wanted = config.getStoryboardTargetLanguages();
		if (wanted == null || wanted.isBlank()) return;
		try {
			ollama.unload();
			fastOllama.unload();
			com.video.transcribe.LanguageSupport.Language source = com.video.transcribe.LanguageSupport.detect(
				StoryboardLocalizer.joinedNarration(storyboard), detectedLanguage);
			new StoryboardLocalizer(config, ollama).localize(storyboard, baseName, source,
				StoryboardLocalizer.parseTargets(wanted), Paths.get(config.getOutputDir()));
		} catch (Exception e) {
			// not a warning to scroll past: the job asked for these languages, so leave a marker file next to the outputs
			logger.error("Additional storyboard languages '{}' were NOT written: {}", wanted, e.getMessage());
			try {
				Files.writeString(Paths.get(config.getOutputDir(), baseName + "_localization_FAILED.txt"),
					"Requested languages: " + wanted + System.lineSeparator() + "Error: " + e.getMessage() + System.lineSeparator()
						+ "Re-run just the translation with StoryboardTranslatorRunner (see docs/LANGUAGES_AND_VERIFICATION.md)." + System.lineSeparator());
			} catch (IOException ignored) {
				// the error is already logged
			}
		}
	}

	/** Source tracing: marks shots whose key terms or numbers appear in no source, and writes <base>_grounding.json. */
	private void traceClaimsToSources(StoryboardDocument storyboard, String baseName, String paraphrasedText,
			StoryboardProjectMaterials materials) {
		try {
			// The only sources are what the lecturer actually said (the transcript) and approved reference materials. The paraphrase and
			// the enriched narration are deliberately NOT sources: whatever they added must show up as unsupported for a person to judge.
			StringBuilder sources = new StringBuilder();
			Path transcriptFile = Paths.get(config.getOutputDir(), baseName + "_transcript.txt");
			if (Files.isRegularFile(transcriptFile)) sources.append(Files.readString(transcriptFile)).append("\n");
			if (sources.length() == 0 && paraphrasedText != null) sources.append(paraphrasedText);
			if (materials != null && materials.promptContext() != null) sources.append(materials.promptContext());
			com.video.transcribe.scene.ClaimGrounding.Report report =
				com.video.transcribe.scene.ClaimGrounding.check(storyboard, sources.toString());
			com.video.transcribe.scene.ClaimGrounding.annotate(storyboard, report);
			Files.writeString(Paths.get(config.getOutputDir(), baseName + "_grounding.json"),
				new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(report.toMap()));
			logger.info("Source tracing: {} supported, {} partly supported, {} need expert review",
				report.count(com.video.transcribe.scene.ClaimGrounding.Status.SUPPORTED),
				report.count(com.video.transcribe.scene.ClaimGrounding.Status.PARTLY_SUPPORTED),
				report.count(com.video.transcribe.scene.ClaimGrounding.Status.UNSUPPORTED));
		} catch (Exception e) {
			logger.warn("Source tracing skipped: {}", e.getMessage());
		}
	}

	public StoryboardDocument generateStoryboard(String paraphrasedText, String baseName) throws Exception {
		StoryboardProjectMaterials materials = loadStoryboardMaterials(baseName);
		return generateStoryboard(paraphrasedText, baseName, materials);
	}

	public StoryboardDocument generateStoryboard(String paraphrasedText, String baseName,
			StoryboardProjectMaterials materials) throws Exception {
		logger.info("=== PHASE 3c: Generating Scene Storyboard ===");
		if (!materials.isEmpty()) {
			logger.info("Loaded storyboard project materials: {} approved/reference assets",
				materials.approvedAssets().size());
		}
		sceneGenerator.setLanguageHint(detectedLanguage);
		StoryboardDocument storyboard = sceneGenerator.generateStoryboard(
			paraphrasedText, baseName, materials);
		// Keep a diagnostic draft even when the quality gate rejects the storyboard.
		Files.writeString(Paths.get(config.getOutputDir(), baseName + "_storyboard.draft.json"), gson.toJson(storyboard));
		StoryboardQualityGate.validate(storyboard);
		validateStoryboardIdentity(storyboard, baseName, paraphrasedText);

		// The exporter separates formula lines from prose before writing the DOCX and contract. Do it first, so the saved
		// _storyboard.json, the DOCX, the contract and any translation all describe the same storyboard.
		com.video.transcribe.scene.StoryboardContractAudit.separateFormulaProse(storyboard);
		traceClaimsToSources(storyboard, baseName, paraphrasedText, materials);

		// Save as JSON
		Path storyboardJson = Paths.get(config.getOutputDir(), baseName + "_storyboard.json");
		Files.writeString(storyboardJson, gson.toJson(storyboard));
		logger.info("Storyboard JSON saved: {}", storyboardJson);

		docxExporter.withLanguage(StoryboardContract.LanguagePackage.forWhisperCode(detectedLanguage));
		// Export as Word document
		Path docxPath = Paths.get(config.getOutputDir(), baseName + "_storyboard.docx");
		docxExporter.export(storyboard, docxPath.toString());
		logger.info("Storyboard DOCX saved: {}", docxPath);

		localizeStoryboard(storyboard, baseName);
		return storyboard;
	}

	private void validateStoryboardIdentity(StoryboardDocument storyboard, String baseName, String sourceText) {
		String title = storyboard == null ? "" : storyboard.getTitle();
		String evidence = (baseName == null ? "" : baseName) + " " + (sourceText == null ? "" : sourceText);
		if (!titleMatchesEvidence(title, evidence)) {
			throw new IllegalStateException(
				"Storyboard topic does not match the input filename or usable transcript; refusing cross-topic export."
			);
		}
	}

	/**
	 * A title belongs to its source when they share at least one significant word. Words are compared by their first five letters so
	 * "Minimizing" in the title matches "minimum" in the transcript (an exact match rejected a correct calculus storyboard).
	 */
	static boolean titleMatchesEvidence(String title, String evidence) {
		Set<String> titleTerms = stems(significantTermsOf(title));
		if (titleTerms.isEmpty()) {
			return true;
		}
		Set<String> evidenceTerms = stems(significantTermsOf(evidence));
		return titleTerms.stream().anyMatch(evidenceTerms::contains);
	}

	private static Set<String> stems(Set<String> terms) {
		Set<String> result = new HashSet<>();
		for (String term : terms) {
			result.add(term.substring(0, Math.min(5, term.length())));
		}
		return result;
	}

	private Set<String> significantTerms(String value) {
		return significantTermsOf(value);
	}

	private static Set<String> significantTermsOf(String value) {
		Set<String> ignored = Set.of(
			"storyboard", "lesson", "video", "introduction", "application", "applications",
			"type", "types", "about", "using", "with", "from", "into", "that", "this"
		);
		Set<String> result = new HashSet<>();
		for (String token : (value == null ? "" : value).toLowerCase(Locale.ROOT)
			.split("[^\\p{L}\\p{M}\\p{N}]+")) {
			if (token.length() >= 4 && !ignored.contains(token)) result.add(token);
		}
		return result;
	}

	// ============================================
	// PHASE 4: Paraphrased TXT → TTS Audio (WAV or MP3)
	// ============================================
	// Output format depends on TTS provider:
	//   - Piper TTS → .wav file
	//   - Edge TTS  → .mp3 file
	// ============================================

	public Path generateAudio(String paraphrasedText, String baseName) throws Exception {
		if (!config.isTtsEnabled() || tts == null) {
			logger.info("=== PHASE 4: TTS skipped (storyboard-only mode) ===");
			return null;
		}
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

		detectedLanguage = language;        // never inherit the previous queue item's language
		try (GpuLease gpu = GpuLease.acquire("transcribe", java.time.Duration.ofHours(4))) {
			// Phase 0: immutable job identity; identical media reuses the saved transcript
			TranscriptData transcript = null;
			try {
				Path media = Paths.get(videoPath);
				Path outDir = Paths.get(config.getOutputDir());
				java.util.Map<String, Object> manifest = JobManifest.build(media, videoPath, language, config.getFfprobePath());
				String duplicateOf = JobManifest.findDuplicate(outDir, (String) manifest.get("sha256"));
				if (duplicateOf != null) {
					Path saved = outDir.resolve(duplicateOf + "_transcript.json");
					transcript = reusableTranscript(saved, outDir.resolve(duplicateOf + "_transcript.txt"), language);
					if (transcript != null) {
						logger.info("Duplicate media fingerprint (same as '{}'): reusing transcript {}", duplicateOf, saved);
						manifest.put("duplicate_of", duplicateOf);
						Files.copy(saved, outDir.resolve(baseName + "_transcript.json"),
							java.nio.file.StandardCopyOption.REPLACE_EXISTING);
						Files.writeString(outDir.resolve(baseName + "_transcript.txt"), transcript.getFullText());
					} else {
						transcript = null;
					}
				}
				manifest.put("asr", whisper.scriptInfo());
				JobManifest.write(outDir, baseName, manifest);
			} catch (Exception e) {
				logger.warn("Job manifest step skipped: {}", e.getMessage());
				transcript = null;
			}

			if (transcript == null) {
				// Phase 1: Extract audio from video
				Path audioPath = extractAudio(videoPath);

				// Phase 2: Transcribe audio → JSON + TXT
				transcript = transcribeAudio(audioPath, language, baseName);
			}
			if (transcript.getLanguage() != null && !transcript.getLanguage().isBlank()) {
				detectedLanguage = transcript.getLanguage();      // also when the transcript was reused from an identical earlier job
			}
			String originalText = transcript.getFullText();
			com.video.transcribe.transcription.Glossary glossary = com.video.transcribe.transcription.Glossary.load(
				detectedLanguage, config.getStoryboardMaterialsDir(), baseName);
			if (glossary.size() > 0) {
				originalText = glossary.apply(originalText);          // fix known ASR mis-spellings before any LLM sees the text
			}
			if (!TranscriptQualityGate.hasUsableSpeech(transcript)) {
				logSkippedVideo(videoPath, baseName, "No speech detected or only music/background audio");
				logger.warn("Skipping video with no usable speech: {}", videoPath);
				return new PipelineResult(false, null, transcript, null,
						null, null, System.currentTimeMillis() - startTime,
						"Skipped: no speech detected or only music/background audio");
			}

			// Phase 3 + 3b: Paraphrase, validate, and retry until content coverage passes
			ParaphraseValidation paraphraseValidation = paraphraseUntilValid(originalText, style, baseName);
			StoryboardProjectMaterials materials = loadStoryboardMaterials(baseName);
			String paraphrased = maybeEnrichParaphrase(originalText, paraphraseValidation.paraphrasedText,
				style, baseName, materials);
			paraphrased = factCheckNarration(paraphrased, materials);
			ParaphraseValidation finalNarration = finalizeNarrationUntilValid(
				originalText,
				paraphrased,
				style,
				baseName,
				materials
			);
			paraphrased = finalNarration.paraphrasedText;
			ValidationResult validation = finalNarration.validation;
			saveParaphrase(paraphrased, baseName);

			// Phase 3c: Generate scene storyboard
			StoryboardDocument storyboard = generateStoryboard(paraphrased, baseName, materials);

			// Phase 4 is disabled in storyboard-only production mode.
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
			List<String> failedGates = AccuracyValidator.failedQualityGates(
				validation,
				config.getValidationThreshold()
			);
			if (validation.isPassed() && failedGates.isEmpty()) {
				logger.info("Paraphrase accepted with validation score {}/100 on attempt {}",
					validation.getOverallScore(), attempt);
				return new ParaphraseValidation(paraphrased, validation);
			}

			logger.warn("Paraphrase failed production quality gates: {}. Overall score: {}/100; retrying if attempts remain",
				failedGates.isEmpty() ? "validator returned passed=false" : String.join("; ", failedGates),
				validation.getOverallScore());
		}

		if (validation != null && isAcceptableAfterRetries(validation)) {
			logger.warn("Accepting paraphrase after retries with near-threshold score {}/100. Review validation report for warnings.",
				validation.getOverallScore());
			return new ParaphraseValidation(paraphrased, validation);
		}

		String failedGateSummary = validation == null
			? "validation result unavailable"
			: String.join("; ", AccuracyValidator.failedQualityGates(
				validation,
				config.getValidationThreshold()
			));
		throw new IOException("Paraphrase validation failed after " + maxAttempts
			+ " attempts. Last score: " + (validation != null ? validation.getOverallScore() : "none")
			+ ". Failed gates: " + failedGateSummary);
	}

	/**
	 * The saved transcript of identical media, or null when it cannot stand in for a fresh one: unreadable, empty, or made in
	 * another language than the one requested now. The JSON holds the raw recogniser text; the punctuated text the first job
	 * produced sits in the .txt next to it and replaces it.
	 */
	static TranscriptData reusableTranscript(Path json, Path punctuatedText, String language) throws IOException {
		TranscriptData saved = gson.fromJson(Files.readString(json), TranscriptData.class);
		if (saved == null || saved.getFullText() == null || saved.getFullText().isBlank()) return null;
		boolean otherLanguage = language != null && !language.isBlank() && saved.getLanguage() != null
			&& !saved.getLanguage().equalsIgnoreCase(language);
		if (otherLanguage) return null;
		if (Files.isRegularFile(punctuatedText)) {
			String text = Files.readString(punctuatedText);
			if (!text.isBlank()) saved.setText(text);
		}
		return saved;
	}

	private boolean isAcceptableAfterRetries(ValidationResult validation) {
		double nearThreshold = Math.max(70.0, config.getValidationThreshold() - 10.0);
		return validation.getOverallScore() >= nearThreshold
			&& validation.getScientificAccuracyScore() >= 85.0
			&& validation.getTopicCoverageScore() >= 70.0
			&& validation.getHallucinationScore() >= 85.0;
	}

	private ParaphraseValidation finalizeNarrationUntilValid(
			String originalText,
			String candidate,
			String style,
			String baseName,
			StoryboardProjectMaterials materials) throws Exception {
		String narration = candidate;
		ValidationResult validation = null;
		int maxAttempts = Math.max(1, config.getValidationMaxRetries());

		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			logger.info("Final narration validation attempt {}/{}", attempt, maxAttempts);
			if (attempt > 1) {
				String repaired = ollama.repairParaphrase(
					originalText,
					narration,
					formatValidationIssues(validation),
					style
				);
				repaired = cleanGeneratedNarration(repaired);
				if (repaired.isBlank()) {
					logger.warn("Final narration repair returned blank text; retaining the previous candidate");
				} else {
					narration = repaired;
				}
				try {
					narration = ollama.proofreadEducationalTerminology(narration);
				} catch (IOException e) {
					logger.warn("Final terminology proofread could not be applied: {}", e.getMessage());
				}
				narration = factCheckNarration(narration, materials);
			}

			validation = validateParaphrase(originalText, narration, baseName);
			List<String> failedGates = AccuracyValidator.failedQualityGates(
				validation,
				config.getValidationThreshold()
			);
			if (validation.isPassed() && failedGates.isEmpty()) {
				logger.info("Final narration accepted with validation score {}/100 on attempt {}",
					validation.getOverallScore(), attempt);
				return new ParaphraseValidation(narration, validation);
			}

			logger.warn("Final narration failed production quality gates: {}. Repairing if attempts remain",
				failedGates.isEmpty() ? "validator returned passed=false" : String.join("; ", failedGates));
		}

		throw new IOException("Final narration failed the production accuracy gate after "
			+ maxAttempts + " attempts:\n" + formatValidationIssues(validation));
	}

	private String formatValidationIssues(ValidationResult validation) {
		if (validation == null) {
			return "No validation details available. Improve topic coverage and factual consistency.";
		}
		StringBuilder text = new StringBuilder();
		text.append("Overall score: ").append(validation.getOverallScore()).append("/100\n");
		text.append("Semantic similarity: ").append(validation.getSemanticSimilarityScore()).append("/100\n");
		text.append("Factual consistency: ").append(validation.getFactualConsistencyScore()).append("/100\n");
		text.append("Scientific accuracy: ").append(validation.getScientificAccuracyScore()).append("/100\n");
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

	private StoryboardProjectMaterials loadStoryboardMaterials(String baseName) throws IOException {
		return StoryboardProjectMaterialsLoader.load(config.getStoryboardMaterialsDir(), baseName);
	}

	private String maybeEnrichParaphrase(String originalText, String validatedParaphrase,
			String style, String baseName, StoryboardProjectMaterials materials) {
		if (!config.isStoryboardCurriculumEnrichmentEnabled()) {
			return validatedParaphrase;
		}
		if ((materials == null || !materials.hasTextEvidence()) && !config.getBoolean("storyboard.curriculum.enrichment.allow_model_knowledge", false)) {
			// Production rule: added content must be traceable to approved project materials, never to the model's memory.
			logger.info("Curriculum enrichment skipped: no approved project materials (set "
				+ "storyboard.curriculum.enrichment.allow_model_knowledge=true to allow unsourced additions)");
			return validatedParaphrase;
		}
		logger.info("=== PHASE 3b.5: Curriculum Enrichment ===");
		try {
			String enriched = ollama.enrichParaphraseForCurriculum(
				originalText,
				validatedParaphrase,
				materials.promptContext(),
				style
			);
			enriched = cleanGeneratedNarration(enriched);
			if (enriched.isBlank()) {
				logger.warn("Curriculum enrichment returned blank text; using validated paraphrase");
				return validatedParaphrase;
			}
			if (enriched.length() < validatedParaphrase.length() * 0.9) {
				logger.warn("Curriculum enrichment shortened the validated paraphrase; using validated paraphrase");
				return validatedParaphrase;
			}
			if (!containsMeaningfulPrefix(enriched, validatedParaphrase)) {
				logger.warn("Curriculum enrichment did not preserve the validated paraphrase prefix clearly; using validated paraphrase");
				return validatedParaphrase;
			}
			if (enriched.length() > validatedParaphrase.length() + 120) {
				Path enrichmentPath = Paths.get(config.getOutputDir(), baseName + "_curriculum_enriched.txt");
				Files.writeString(enrichmentPath, enriched);
				logger.info("Curriculum-enriched narration saved: {}", enrichmentPath);
			} else {
				logger.info("Curriculum enrichment made no substantial additions; continuing with validated paraphrase");
			}
			return enriched;
		} catch (Exception e) {
			logger.warn("Curriculum enrichment failed; continuing with validated paraphrase: {}", e.getMessage());
			return validatedParaphrase;
		}
	}

	private String factCheckNarration(String narration, StoryboardProjectMaterials materials) {
		logger.info("=== PHASE 3b.6: Subject-Aware Factual Audit ===");
		try {
			String corrected = ollama.factCheckEducationalNarration(
				narration,
				materials == null ? "" : materials.promptContext());
			corrected = cleanGeneratedNarration(corrected);
			if (!corrected.isBlank()) {
				String problem = factCheckProblem(narration, corrected);
				if (problem == null) {
					return corrected;
				}
				logger.warn("Factual audit rewrite rejected ({}); keeping the narration as it was", problem);
				return narration;
			}
			logger.warn("Factual audit returned blank text; keeping the enriched narration");
		} catch (IOException e) {
			logger.warn("Factual audit could not be applied; keeping the enriched narration: {}",
				e.getMessage());
		}
		return narration;
	}

	/**
	 * A fact-check rewrite may fix wording, never numbers or scope: every number of the original must still be there and the text must
	 * stay about the same length. Returns the reason to reject the rewrite, or null to accept it.
	 */
	static String factCheckProblem(String original, String corrected) {
		java.util.Set<String> kept = com.video.transcribe.scene.ClaimGrounding.digitNumbers(corrected);
		for (String number : com.video.transcribe.scene.ClaimGrounding.digitNumbers(original)) {
			if (!kept.contains(number)) return "the number " + number + " was lost";
		}
		double ratio = original.isBlank() ? 1.0 : (double) corrected.length() / original.length();
		if (ratio < 0.75 || ratio > 1.35) return "the length changed by " + Math.round((ratio - 1) * 100) + "%";
		return null;
	}

	private String cleanGeneratedNarration(String value) {
		if (value == null) {
			return "";
		}
		String cleaned = value
			.replaceAll("(?im)^\\s*curriculum enrichment\\s*:\\s*", "")
			.replaceAll("(?m)^```[a-zA-Z]*\\s*$", "")
			.replaceAll("(?m)^```\\s*$", "")
			.replace("**", "")
			.replace("__", "")
			.replace("`", "")
			.replaceAll("(?m)^\\s*[-*]\\s+", "")
			.replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "")
			.replaceAll("[ \\t]+", " ")
			.replaceAll("\\n{3,}", "\n\n")
			.trim();
		return cleaned;
	}

	private boolean containsMeaningfulPrefix(String enriched, String validatedParaphrase) {
		String enrichedNormalized = normalizeForPrefixCheck(enriched);
		String paraphraseNormalized = normalizeForPrefixCheck(validatedParaphrase);
		int required = Math.min(paraphraseNormalized.length(), 400);
		if (required < 80) {
			return enrichedNormalized.contains(paraphraseNormalized);
		}
		return enrichedNormalized.contains(paraphraseNormalized.substring(0, required));
	}

	private String normalizeForPrefixCheck(String value) {
		if (value == null) {
			return "";
		}
		return value.toLowerCase()
			.replaceAll("[^\\p{L}\\p{M}\\p{N}]+", " ")
			.replaceAll("\\s+", " ")
			.trim();
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
