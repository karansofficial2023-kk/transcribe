package com.video.transcribe;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.video.transcribe.LanguageSupport.Language;
import com.video.transcribe.config.AppConfig;
import com.video.transcribe.llm.OllamaClient;
import com.video.transcribe.scene.ClaimGrounding;
import com.video.transcribe.scene.OllamaTextTranslator;
import com.video.transcribe.scene.ProcessTextTranslator;
import com.video.transcribe.scene.Scene;
import com.video.transcribe.scene.SceneSegment;
import com.video.transcribe.scene.StoryboardDocument;
import com.video.transcribe.scene.StoryboardTranslator;
import com.video.transcribe.transcription.Glossary;
import com.video.transcribe.model.TranscriptData;

/**
 * Writes an existing storyboard in further languages: translate, trace numbers to the original, then export the same files the
 * original has (storyboard JSON, DOCX, contract JSON, audit JSON) under {@code <base>_<code>_...}.
 * The planning is not repeated, so each language takes minutes instead of the half hour of storyboard generation.
 */
public final class StoryboardLocalizer {
    private static final Logger logger = LoggerFactory.getLogger(StoryboardLocalizer.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final AppConfig config;
    private final OllamaClient ollama;

    public StoryboardLocalizer(AppConfig config, OllamaClient mainModelClient) {
        this.config = config;
        this.ollama = mainModelClient;
    }

    /** Parses a comma/space separated list of language codes or names; unsupported entries are reported, not silently dropped. */
    public static List<Language> parseTargets(String value) {
        List<Language> targets = new ArrayList<>();
        if (value == null) return targets;
        for (String part : value.split("[,;\\s]+")) {
            if (part.isBlank()) continue;
            targets.add(LanguageSupport.byNameOrCode(part).orElseThrow(
                () -> new IllegalArgumentException("Unsupported target language '" + part + "' (supported: "
                    + LanguageSupport.all().stream().map(Language::code).toList() + ")")));
        }
        return targets;
    }

    public static String joinedNarration(StoryboardDocument storyboard) {
        StringBuilder text = new StringBuilder();
        for (Scene scene : storyboard.getScenes()) {
            if (scene.getSegments() == null) continue;
            for (SceneSegment segment : scene.getSegments()) {
                if (segment.getSentence() != null) text.append(segment.getSentence()).append(' ');
            }
        }
        return text.toString().trim();
    }

    /** Translates {@code storyboard} into each target language and exports it; returns the exported DOCX paths. */
    public List<Path> localize(StoryboardDocument storyboard, String baseName, Language sourceLanguage, List<Language> targets,
                               Path outputDir) throws Exception {
        Files.createDirectories(outputDir);
        List<Path> exported = new ArrayList<>();
        String original = joinedNarration(storyboard);
        for (Language target : targets) {
            if (target.code().equals(sourceLanguage.code())) {
                logger.info("Skipping {}: it is the storyboard's own language", target.name());
                continue;
            }
            long started = System.currentTimeMillis();
            Glossary glossary = Glossary.load(target.code(), config.getStoryboardMaterialsDir(), baseName);
            StoryboardTranslator.Result result = translateWithEngine(storyboard, sourceLanguage, target, glossary);
            StoryboardDocument translated = result.storyboard();
            ClaimGrounding.Report report = ClaimGrounding.check(translated, original, true);   // cross-language: numbers are compared
            ClaimGrounding.annotate(translated, report);

            String name = baseName + "_" + target.code();
            Files.writeString(outputDir.resolve(name + "_storyboard.json"), GSON.toJson(translated), StandardCharsets.UTF_8);
            Files.writeString(outputDir.resolve(name + "_grounding.json"), GSON.toJson(report.toMap()), StandardCharsets.UTF_8);
            java.util.Map<String, Object> review = new java.util.LinkedHashMap<>();
            review.put("language", target.name());
            review.put("note", "Strings the automatic checks doubt (wrong script, lost number/formula, or meaning that did not survive a "
                + "back-translation). A fluent reviewer should correct these; add repeated fixes to the glossary.");
            review.put("count", result.reviews().size());
            review.put("items", result.reviews());
            Files.writeString(outputDir.resolve(name + "_translation_review.json"), GSON.toJson(review), StandardCharsets.UTF_8);
            Path docx = outputDir.resolve(name + "_storyboard.docx");
            new StoryboardDocxExporter(config.getStoryboardVideoProvider())
                .withLanguage(StoryboardContract.LanguagePackage.forWhisperCode(target.code()))
                .export(translated, docx.toString());
            exported.add(docx);
            logger.info("Storyboard translated to {}: {} strings, {} retried, {} failed checks, {} with possible meaning drift, {} s",
                target.name(), result.strings(), result.retried(), result.flagged(), result.drifted(), (System.currentTimeMillis() - started) / 1000);
        }
        return exported;
    }

    /** Build target-language audio contracts directly from timed ASR segments; this deliberately skips paraphrasing and visual planning. */
    public List<Path> localizeTranscriptForAudio(TranscriptData transcript, String baseName, String gradeLevel, String academicLevel,
                                                 Language sourceLanguage, List<Language> targets, Path outputDir) throws Exception {
        if (transcript.getSegments() == null || transcript.getSegments().isEmpty()) {
            throw new IllegalStateException("Audio translation requires timestamped transcript segments");
        }
        Files.createDirectories(outputDir);
        StoryboardDocument source = new StoryboardDocument();
        source.setTitle(baseName);
        source.setGradeLevel(gradeLevel);
        source.setAcademicLevel(academicLevel);
        source.setSourceText(transcript.getFullText());
        List<Scene> scenes = new ArrayList<>();
        int number = 0;
        for (TranscriptData.Segment timed : transcript.getSegments()) {
            if (timed.getText() == null || timed.getText().isBlank()) continue;
            if (timed.getStart() < 0 || timed.getEnd() <= timed.getStart()) continue;
            Scene scene = new Scene();
            scene.setSceneNumber(++number);
            scene.setSceneTitle("");
            scene.setNarration(timed.getText().trim());
            SceneSegment shot = new SceneSegment();
            shot.setSegmentNumber(1);
            shot.setSentence(timed.getText().trim());
            shot.setSourceStart(timed.getStart());
            shot.setSourceEnd(timed.getEnd());
            shot.setRecommendedClipSeconds(timed.getEnd() - timed.getStart());
            shot.setEstimatedNarrationSeconds(timed.getEnd() - timed.getStart());
            shot.setSubtitle("");
            scene.setSegments(List.of(shot));
            scenes.add(scene);
        }
        if (scenes.isEmpty()) throw new IllegalStateException("Transcript has no valid timed speech segments");
        source.setScenes(scenes);

        List<Path> contracts = new ArrayList<>();
        for (Language target : targets) {
            if (target.code().equals(sourceLanguage.code())) continue;
            Glossary glossary = Glossary.load(target.code(), config.getStoryboardMaterialsDir(), baseName);
            StoryboardTranslator.Result result = translateWithEngine(source, sourceLanguage, target, glossary);
            String name = baseName + "_" + target.code();
            java.util.Map<String, Object> contract = StoryboardContract.toContract(
                result.storyboard(), StoryboardContract.LanguagePackage.forWhisperCode(target.code()));
            contract.put("audio_translation_only", true);
            contract.put("timing_source", "whisper_asr_segments");
            Path path = outputDir.resolve(name + "_contract.json");
            Files.writeString(path, GSON.toJson(contract), StandardCharsets.UTF_8);
            java.util.Map<String, Object> review = new java.util.LinkedHashMap<>();
            review.put("translation_only", true);
            review.put("translated_segments", result.strings());
            review.put("review_items", result.reviews());
            Files.writeString(outputDir.resolve(name + "_translation_review.json"), GSON.toJson(review), StandardCharsets.UTF_8);
            contracts.add(path);
            logger.info("Audio translation contract saved: {} ({} timed speech segments; no paraphrase or storyboard planning)", path, scenes.size());
        }
        if (contracts.isEmpty()) throw new IllegalStateException("No target-language contracts were written; check target and source language codes");
        return contracts;
    }

    /** Chooses the offline MADLAD worker when it is installed (and requested or "auto"), otherwise the local Qwen model. */
    private StoryboardTranslator.Result translateWithEngine(StoryboardDocument storyboard, Language source, Language target,
                                                            Glossary glossary) throws Exception {
        String engine = config.getTranslationEngine().trim().toLowerCase(java.util.Locale.ROOT);
        boolean madlad = "madlad".equals(engine) || ("auto".equals(engine) && ProcessTextTranslator.available(config));
        if ("madlad".equals(engine) && !ProcessTextTranslator.available(config)) {
            throw new IllegalStateException("translation.engine=madlad but the worker or model is missing: "
                + config.getTranslateScript() + " / " + config.getTranslateModelDir());
        }
        if (madlad) {
            ollama.unload();                      // the 12 GB card holds one heavy model at a time
            logger.info("Translating with the offline MADLAD-400 worker");
            try (ProcessTextTranslator worker = new ProcessTextTranslator(config, glossary)) {
                return new StoryboardTranslator(worker, 24, true).translate(storyboard, source, target);   // + back-translation drift check
            }
        }
        logger.info("Translating with the local language model ({}); install MADLAD-400 for better Indic quality", config.getOllamaModel());
        return new StoryboardTranslator(new OllamaTextTranslator(ollama, glossary)).translate(storyboard, source, target);
    }

    /** Writes a DOCX/contract for an already-built translation (used by tests and tools). */
    static void writeText(Path file, String text) throws IOException {
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }
}
