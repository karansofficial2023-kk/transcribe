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
import com.video.transcribe.transcription.TerminologyGlossary;
import com.video.transcribe.model.TranscriptData;

/**
 * Writes an existing storyboard in further languages: translate, trace numbers to the original, then export the same files the
 * original has (storyboard JSON, DOCX, contract JSON, audit JSON) under {@code <base>_<code>_...}.
 * The planning is not repeated, so each language takes minutes instead of the half hour of storyboard generation.
 */
public final class StoryboardLocalizer {
    private static final Logger logger = LoggerFactory.getLogger(StoryboardLocalizer.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    public static final String ASR_PIPELINE_VERSION = "v2.3";

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
            TerminologyGlossary terminology = TerminologyGlossary.load(target.code(), config.getStoryboardMaterialsDir(), baseName);
            StoryboardTranslator.Result result = translateWithEngine(storyboard, sourceLanguage, target, glossary, terminology);
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
        return localizeTranscriptForAudio(transcript, baseName, gradeLevel, academicLevel, sourceLanguage, targets, outputDir,
            java.util.function.UnaryOperator.identity());
    }

    /** sourceFixer repairs known ASR mis-hearings (the lesson glossary) in every spoken source cue before it is translated. */
    public List<Path> localizeTranscriptForAudio(TranscriptData transcript, String baseName, String gradeLevel, String academicLevel,
                                                 Language sourceLanguage, List<Language> targets, Path outputDir,
                                                 java.util.function.UnaryOperator<String> sourceFixer) throws Exception {
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
        java.util.Map<Integer, String> sourceWarnings = new java.util.LinkedHashMap<>();
        int number = 0;
        for (List<TranscriptData.Segment> group : audioTranslationGroups(transcript.getSegments())) {
            TranscriptData.Segment first = group.get(0);
            TranscriptData.Segment last = group.get(group.size() - 1);
            String sourceLine = sourceFixer.apply(String.join(" ", group.stream().map(item -> item.getText().trim()).toList()));
            Scene scene = new Scene();
            scene.setSceneNumber(++number);
            scene.setSceneTitle("");
            scene.setNarration(sourceLine);
            SceneSegment shot = new SceneSegment();
            shot.setSegmentNumber(1);
            shot.setSentence(sourceLine);
            shot.setSourceNarration(sourceLine);
            shot.setSourceStart(first.getStart());
            shot.setSourceEnd(last.getEnd());
            shot.setRecommendedClipSeconds(last.getEnd() - first.getStart());
            shot.setEstimatedNarrationSeconds(last.getEnd() - first.getStart());
            shot.setSubtitle("");
            scene.setSegments(List.of(shot));
            scenes.add(scene);
            var uncertainWords = group.stream().filter(cue -> cue.getWords() != null)
                .flatMap(cue -> cue.getWords().stream())
                .filter(word -> word.getProbability() > 0 && word.getProbability() < 0.25)
                .map(word -> word.getWord().trim()).filter(word -> !word.isBlank()).distinct().toList();
            if (!uncertainWords.isEmpty()) {
                sourceWarnings.put(number, "English ASR needs source-audio verification at "
                    + String.format(java.util.Locale.ROOT, "%.2f-%.2fs", first.getStart(), last.getEnd())
                    + "; uncertain words: " + String.join(", ", uncertainWords));
            }
        }
        if (scenes.isEmpty()) throw new IllegalStateException("Transcript has no valid timed speech segments");
        source.setScenes(scenes);

        List<Path> contracts = new ArrayList<>();
        for (Language target : targets) {
            if (target.code().equals(sourceLanguage.code())) continue;
            Glossary glossary = Glossary.load(target.code(), config.getStoryboardMaterialsDir(), baseName);
            TerminologyGlossary terminology = TerminologyGlossary.load(target.code(), config.getStoryboardMaterialsDir(), baseName);
            StoryboardTranslator.Result result = translateWithEngine(source, sourceLanguage, target, glossary, terminology);
            // MADLAD remains fully usable without Ollama.  Probe the optional second
            // model once, instead of making every sentence wait through its retry loop.
            if (ollama != null && ollama.isAvailable()) {
                repairAudioTranslationWithSecondModel(source, result, sourceLanguage, target, glossary, terminology);
                auditAllAudioTranslationLines(source, result);
                repairSemanticAuditFindings(source, result, target, terminology);
            } else {
                logger.warn("Ollama bilingual audit is unavailable; continuing with offline translation and deterministic QA");
            }
            for (var warning : sourceWarnings.entrySet()) {
                var item = new java.util.LinkedHashMap<String, String>();
                item.put("where", "scene " + warning.getKey() + ".1 source_asr");
                item.put("source", source.getScenes().get(warning.getKey() - 1).getSegments().get(0).getSentence());
                item.put("translation", "");
                item.put("problem", warning.getValue());
                result.reviews().add(item);
            }
            String name = baseName + "_" + target.code();
            java.util.Map<String, Object> contract = StoryboardContract.toContract(
                result.storyboard(), StoryboardContract.LanguagePackage.forWhisperCode(target.code()));
            contract.put("audio_translation_only", true);
            contract.put("timing_source", "whisper_asr_segments");
            // Version of the ASR+translation recipe that produced this contract. The dubbing queue rebuilds contracts made by an
            // older recipe (v2.1: no cross-window conditioning, tail rescue, masked formulas, glossary on ASR text).
            contract.put("asr_pipeline", ASR_PIPELINE_VERSION);
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

    /** Translate complete spoken thoughts while retaining the original start/end of every grouped cue. */
    static List<List<TranscriptData.Segment>> audioTranslationGroups(List<TranscriptData.Segment> timed) {
        // Whisper chunk boundaries are decoder windows, not sentence boundaries. A
        // chunk may contain several sentences or end halfway through a chemistry
        // claim. Rebuild sentence cues from its word timestamps before translation.
        var words = timed.stream().filter(cue -> cue.getWords() != null)
            .flatMap(cue -> cue.getWords().stream())
            .filter(word -> word.getWord() != null && !word.getWord().isBlank()
                && word.getStart() >= 0 && word.getEnd() >= word.getStart())
            .toList();
        if (!words.isEmpty()) {
            List<List<TranscriptData.Segment>> sentences = new ArrayList<>();
            List<TranscriptData.Word> pendingWords = new ArrayList<>();
            for (var word : words) {
                pendingWords.add(word);
                String token = word.getWord().trim();
                boolean sentenceEnd = token.matches("(?s).*[.!?।][\\\"')\\]]*$") && !isNonTerminalToken(token);
                // A run-on stretch without punctuation must not become one 60 s cue (it was translated as a blob and spoken
                // at the wrong pace); cut it at the next natural pause once it is long.
                double span = word.getEnd() - pendingWords.get(0).getStart();
                if (sentenceEnd || span >= 45.0 || (span >= 18.0 && word.getEnd() - word.getStart() >= 0 && endsAtPause(words, word))) {
                    var cue = new TranscriptData.Segment();
                    cue.setStart(pendingWords.get(0).getStart());
                    cue.setEnd(word.getEnd());
                    cue.setWords(new ArrayList<>(pendingWords));
                    cue.setText(String.join(" ", pendingWords.stream().map(TranscriptData.Word::getWord).toList()));
                    sentences.add(List.of(cue));
                    pendingWords = new ArrayList<>();
                }
            }
            if (!pendingWords.isEmpty()) {
                var cue = new TranscriptData.Segment();
                cue.setStart(pendingWords.get(0).getStart());
                cue.setEnd(pendingWords.get(pendingWords.size() - 1).getEnd());
                cue.setWords(pendingWords);
                cue.setText(String.join(" ", pendingWords.stream().map(TranscriptData.Word::getWord).toList()));
                sentences.add(List.of(cue));
            }
            return sentences;
        }
        // Older ASR contracts may not contain word timings.
        List<List<TranscriptData.Segment>> groups = new ArrayList<>();
        List<TranscriptData.Segment> pending = new ArrayList<>();
        for (TranscriptData.Segment cue : timed) {
            if (cue.getText() == null || cue.getText().isBlank() || cue.getStart() < 0 || cue.getEnd() <= cue.getStart()) continue;
            if (!pending.isEmpty()) {
                TranscriptData.Segment previous = pending.get(pending.size() - 1);
                boolean sentenceEnded = previous.getText().trim().matches("(?s).*[.!?।][\\\"')\\]]*$");
                if (sentenceEnded || cue.getStart() - previous.getEnd() > 0.8
                        || cue.getEnd() - pending.get(0).getStart() > 25.0 || pending.size() >= 5) {
                    groups.add(pending);
                    pending = new ArrayList<>();
                }
            }
            pending.add(cue);
        }
        if (!pending.isEmpty()) groups.add(pending);
        return groups;
    }

    /** True when the next word starts at least 0.4 s after this one ends: a safe place to cut a cue that has no punctuation. */
    private static boolean endsAtPause(List<TranscriptData.Word> words, TranscriptData.Word word) {
        int index = words.indexOf(word);
        return index >= 0 && index + 1 < words.size() && words.get(index + 1).getStart() - word.getEnd() >= 0.4;
    }

    /** "1." / "2)" list numbers and common abbreviations end no sentence; treating them as one made 0.3 s scenes. */
    static boolean isNonTerminalToken(String token) {
        String bare = token.trim().replaceAll("^[\"'(\\[]+", "");
        return bare.matches("\\d{1,3}[.)]")
            || bare.toLowerCase(java.util.Locale.ROOT).matches("(e\\.g|i\\.e|fig|eq|no|dr|mr|mrs|vs|etc|approx)\\.");
    }

    private static String neighboringEnglish(StoryboardDocument source, int index) {
        var scenes = source.getScenes();
        String previous = index > 0 ? scenes.get(index - 1).getSegments().get(0).getSentence() : "";
        String next = index + 1 < scenes.size() ? scenes.get(index + 1).getSegments().get(0).getSentence() : "";
        return "\nPrevious context (do not translate): " + previous
            + "\nNext context (do not translate): " + next;
    }

    /** Qwen is loaded only after the MADLAD worker closes. A repaired line remains teacher-reviewable. */
    private void repairAudioTranslationWithSecondModel(StoryboardDocument source, StoryboardTranslator.Result result,
            Language from, Language to, Glossary glossary, TerminologyGlossary terminology) {
        var flagged = result.reviews().stream().filter(item -> {
            String reason = item.getOrDefault("problem", "");
            return reason.startsWith("meaning may have drifted") || reason.startsWith("length is implausible")
                || reason.startsWith("a sentence of the original is missing")
                || reason.startsWith("not written in") || reason.startsWith("formula or symbol")
                || reason.startsWith("number ");
        }).toList();
        if (flagged.isEmpty()) return;
        OllamaTextTranslator alternate = new OllamaTextTranslator(ollama, glossary);
        com.google.gson.JsonObject schema = com.google.gson.JsonParser.parseString("""
            {"type":"object","properties":{"faithful":{"type":"boolean"},
             "missing_facts":{"type":"array","items":{"type":"string"}},
             "added_or_changed_facts":{"type":"array","items":{"type":"string"}}},
             "required":["faithful","missing_facts","added_or_changed_facts"]}
            """).getAsJsonObject();
        com.google.gson.JsonObject candidateSchema = com.google.gson.JsonParser.parseString("""
            {"type":"object","properties":{"candidate":{"type":"string"}},"required":["candidate"]}
            """).getAsJsonObject();
        for (var item : flagged) {
            var match = java.util.regex.Pattern.compile("^scene (\\d+)\\.1 narration$").matcher(item.getOrDefault("where", ""));
            if (!match.matches()) continue;
            int index = Integer.parseInt(match.group(1)) - 1;
            if (index < 0 || index >= source.getScenes().size() || index >= result.storyboard().getScenes().size()) continue;
            SceneSegment original = source.getScenes().get(index).getSegments().get(0);
            SceneSegment translated = result.storyboard().getScenes().get(index).getSegments().get(0);
            try {
                String rejected = "";
                String accepted = null;
                for (int attempt = 0; attempt < 3 && accepted == null; attempt++) {
                    String candidate = switch (attempt) {
                        case 1 -> {
                            String split = sentenceBySentence(original.getSentence(), candidateSchema, terminology);
                            // a one-sentence line cannot be split: use this attempt to retry with the rejection reason instead
                            yield split != null ? split : faithfulCandidate(source, index, original, translated,
                                item.getOrDefault("problem", ""), rejected, candidateSchema, terminology);
                        }
                        default -> faithfulCandidate(source, index, original, translated, item.getOrDefault("problem", ""),
                            attempt == 2 ? rejected : "", candidateSchema, terminology);
                    };
                    if (candidate == null || candidate.isBlank()) { rejected = "empty candidate"; continue; }
                    if (terminology != null) candidate = terminology.normalize(original.getSentence(), candidate);
                    String bad = StoryboardTranslator.problem(original.getSentence(), candidate, to);
                    if (bad != null) { rejected = bad; continue; }
                    String back = alternate.translate(List.of(candidate), to, from).get(0);
                    double kept = StoryboardTranslator.overlap(original.getSentence(), back);
                    if (kept < 0.55) { rejected = "its back-translation kept only " + Math.round(kept * 100) + "% of the key words"; continue; }
                    String prompt = "English source: " + original.getSentence() + "\nHindi candidate: " + candidate
                        + "\nCheck every scientific agent, action, object, negation, formula, number and unit. "
                        + "Mark faithful false if any claim is lost, invented, or changed. Return JSON only.";
                    var audit = com.google.gson.JsonParser.parseString(ollama.generateStructured(
                        "You are a strict bilingual science-lesson translation auditor. Do not infer facts absent from the source.",
                        prompt, schema, 700)).getAsJsonObject();
                    if (!audit.get("faithful").getAsBoolean()
                        || audit.getAsJsonArray("missing_facts").size() != 0
                        || audit.getAsJsonArray("added_or_changed_facts").size() != 0) {
                        rejected = "the audit found lost or invented facts: " + audit.getAsJsonArray("missing_facts") + " "
                            + audit.getAsJsonArray("added_or_changed_facts");
                        continue;
                    }
                    accepted = candidate;
                    logger.info("Re-translation from the English accepted at {} on attempt {}", item.get("where"), attempt + 1);
                }
                if (accepted == null) {
                    logger.warn("No faithful re-translation found for {} (last rejection: {}); the line stays flagged", item.get("where"), rejected);
                    continue;
                }
                String candidate = accepted;
                translated.setSentence(candidate);
                translated.setCoverageNotes("SECOND MODEL REPAIR: requires teacher review before release");
                result.reviews().removeIf(review -> review.getOrDefault("where", "").equals(item.get("where")));
                var review = new java.util.LinkedHashMap<String, String>();
                review.put("where", item.get("where"));
                review.put("source", original.getSentence());
                review.put("translation", candidate);
                review.put("resolution", "verified_repair");
                review.put("problem", "second-model repair passed bilingual audit; teacher review still required");
                result.reviews().add(review);
                result.storyboard().getScenes().get(index).setNarration(candidate);
                logger.info("Second-model candidate accepted for review at {}", item.get("where"));
            } catch (Exception error) {
                logger.warn("Second-model translation audit unavailable; retaining flagged MADLAD lines: {}", error.toString());
                break;
            }
        }
    }


    /** One Qwen candidate for a flagged line. `rejected` (when set) tells the model why the previous candidate failed. */
    private String faithfulCandidate(StoryboardDocument source, int index, SceneSegment original, SceneSegment translated,
            String finding, String rejected, com.google.gson.JsonObject candidateSchema, TerminologyGlossary terminology) throws Exception {
        String terms = terminology == null ? "" : terminology.hints(original.getSentence());
        String feedback = (rejected.isBlank() ? "" : "\nThe previous attempt was rejected because: " + rejected
            + ". Translate every clause of the English line and add nothing that is not in it.")
            + (terms.isBlank() ? "" : "\nRequired school terminology (use exactly these Hindi words): " + terms);
        String prompt;
        if (finding.startsWith("number ") || finding.startsWith("formula or symbol")) {
            prompt = "English source: " + original.getSentence() + neighboringEnglish(source, index) + "\nCurrent Hindi: "
                + translated.getSentence() + "\nQA finding: " + finding
                + "\nWrite a complete faithful Hindi sentence. Include every exact number, unit and chemical formula "
                + "from English; do not omit clauses. Return JSON only." + feedback;
            return com.google.gson.JsonParser.parseString(ollama.generateStructured(
                "You are a careful Grade 10 science translator. Preserve all source facts exactly.",
                prompt, candidateSchema, 900)).getAsJsonObject().get("candidate").getAsString().trim();
        }
        // The current Hindi is deliberately NOT shown: when it is unrelated to the English it only misleads the model.
        prompt = "English source: " + original.getSentence() + neighboringEnglish(source, index)
            + "\nTranslate only the English source, every clause of it, as natural Hindi. Context only disambiguates terms; "
            + "do not include context facts. Return JSON only." + feedback;
        return com.google.gson.JsonParser.parseString(ollama.generateStructured(
            "You translate Grade 10 science lessons faithfully from English to Hindi.",
            prompt, candidateSchema, 900)).getAsJsonObject().get("candidate").getAsString().trim();
    }

    /** Translates a multi-sentence line one sentence at a time, so no sentence can be dropped. Null for a one-sentence line. */
    private String sentenceBySentence(String english, com.google.gson.JsonObject candidateSchema, TerminologyGlossary terminology) throws Exception {
        String[] sentences = english.trim().split("(?<=[.!?])\\s+(?=[A-Z0-9])");
        if (sentences.length < 2) return null;
        var joined = new StringBuilder();
        for (String sentence : sentences) {
            String terms = terminology == null ? "" : terminology.hints(sentence);
            String prompt = "English sentence: " + sentence
                + "\nTranslate exactly this sentence into natural Hindi, keeping formulas and numbers unchanged. Return JSON only."
                + (terms.isBlank() ? "" : "\nRequired school terminology (use exactly these Hindi words): " + terms);
            String hindi = com.google.gson.JsonParser.parseString(ollama.generateStructured(
                "You translate Grade 10 science lessons faithfully from English to Hindi.",
                prompt, candidateSchema, 600)).getAsJsonObject().get("candidate").getAsString().trim();
            if (hindi.isBlank()) return null;
            if (joined.length() > 0) joined.append(' ');
            joined.append(hindi);
        }
        return joined.toString();
    }

    /** Inspect every paired line, including lines the lexical back-translation heuristic accepted. */
    private void auditAllAudioTranslationLines(StoryboardDocument source, StoryboardTranslator.Result result) {
        var schema = com.google.gson.JsonParser.parseString("""
            {"type":"object","properties":{"findings":{"type":"array","items":{"type":"object",
             "properties":{"id":{"type":"integer"},"faithful":{"type":"boolean"},
             "classification":{"type":"string","enum":["correct_translation","hindi_translation_error","source_wording_issue","source_formula_ambiguity"]},
             "concern":{"type":"string"}},"required":["id","faithful","classification","concern"]}}},
             "required":["findings"]}
            """).getAsJsonObject();
        int count = source.getScenes().size();
        for (int start = 0; start < count; start += 5) {
            int end = Math.min(count, start + 5);
            var pairs = new com.google.gson.JsonArray();
            for (int index = start; index < end; index++) {
                var pair = new com.google.gson.JsonObject();
                pair.addProperty("id", index + 1);
                pair.addProperty("english", source.getScenes().get(index).getSegments().get(0).getSentence());
                pair.addProperty("hindi", result.storyboard().getScenes().get(index).getSegments().get(0).getSentence());
                pair.addProperty("previous_english_context", index > 0
                    ? source.getScenes().get(index - 1).getSegments().get(0).getSentence() : "");
                pair.addProperty("next_english_context", index + 1 < count
                    ? source.getScenes().get(index + 1).getSegments().get(0).getSentence() : "");
                pairs.add(pair);
            }
            try {
                var answer = com.google.gson.JsonParser.parseString(ollama.generateStructured(
                    "You are a strict bilingual Grade 10 science translation examiner. Compare each pair literally. "
                    + "Check entities, actions, negation, values, units, formulas and technical terms. "
                    + "Use adjacent English only to resolve ambiguous words. Judge only whether the Hindi preserves "
                    + "its paired English line; facts in adjacent lines must not be required or credited. "
                    + "If the paired English appears scientifically wrong or ambiguous, classify it as source_wording_issue or source_formula_ambiguity and keep faithful true when Hindi preserves it; never blame Hindi for a source defect. "
                    + "Do not flag style, fluency, synonyms, or emotional nuance. Do not invent a correction. "
                    + "Mark faithful false only for a clear omission or factual change and quote the exact differing phrases in concern. "
                    + "If uncertain, mark faithful true. Return every id once as JSON findings.",
                    pairs.toString(), schema, 1800)).getAsJsonObject();
                var seen = new java.util.HashSet<Integer>();
                for (var element : answer.getAsJsonArray("findings")) {
                    var finding = element.getAsJsonObject();
                    int id = finding.get("id").getAsInt();
                    if (id <= start || id > end || !seen.add(id)) continue;
                    String classification = finding.get("classification").getAsString();
                    // The examiner may blame "source wording" for Hindi that has nothing to do with the English (a line about
                    // hydrochloric acid translated as hydrogen peroxide). faithful=false means the Hindi diverges: never advisory.
                    if (!finding.get("faithful").getAsBoolean()) {
                        addSemanticReview(source, result, id - 1, "hindi_translation_error: " + finding.get("concern").getAsString());
                    } else if (classification.equals("source_wording_issue") || classification.equals("source_formula_ambiguity")) {
                        addSemanticReview(source, result, id - 1, classification + ": " + finding.get("concern").getAsString());
                    }
                }
                for (int id = start + 1; id <= end; id++) {
                    if (!seen.contains(id)) addSemanticReview(source, result, id - 1, "semantic audit omitted this line");
                }
            } catch (Exception error) {
                logger.warn("Bilingual audit unavailable for lines {}-{}: {}", start + 1, end, error.toString());
                for (int index = start; index < end; index++)
                    addSemanticReview(source, result, index, "bilingual audit unavailable: " + error.getClass().getSimpleName());
            }
        }
    }

    private static void addSemanticReview(StoryboardDocument source, StoryboardTranslator.Result result, int index, String reason) {
        var item = new java.util.LinkedHashMap<String, String>();
        item.put("where", "scene " + (index + 1) + ".1 semantic_audit");
        item.put("source", source.getScenes().get(index).getSegments().get(0).getSentence());
        item.put("translation", result.storyboard().getScenes().get(index).getSegments().get(0).getSentence());
        item.put("problem", reason);
        result.reviews().add(item);
    }

    /** A semantic failure gets one correction attempt and a separate audit pass. The result remains review-only. */
    private void repairSemanticAuditFindings(StoryboardDocument source, StoryboardTranslator.Result result,
            Language to, TerminologyGlossary terminology) {
        var flagged = result.reviews().stream()
            .filter(item -> item.getOrDefault("where", "").matches("scene \\d+\\.1 semantic_audit"))
            .toList();
        if (flagged.isEmpty()) return;
        var candidateSchema = com.google.gson.JsonParser.parseString("""
            {"type":"object","properties":{"candidate":{"type":"string"}},"required":["candidate"]}
            """).getAsJsonObject();
        var auditSchema = com.google.gson.JsonParser.parseString("""
            {"type":"object","properties":{"faithful":{"type":"boolean"},
             "missing_facts":{"type":"array","items":{"type":"string"}},
             "added_or_changed_facts":{"type":"array","items":{"type":"string"}}},
             "required":["faithful","missing_facts","added_or_changed_facts"]}
            """).getAsJsonObject();
        for (var item : flagged) {
            var match = java.util.regex.Pattern.compile("^scene (\\d+)\\.1 semantic_audit$")
                .matcher(item.getOrDefault("where", ""));
            if (!match.matches()) continue;
            int index = Integer.parseInt(match.group(1)) - 1;
            if (index < 0 || index >= source.getScenes().size()) continue;
            var original = source.getScenes().get(index).getSegments().get(0);
            var translated = result.storyboard().getScenes().get(index).getSegments().get(0);
            try {
                String prompt = "English line: " + original.getSentence() + neighboringEnglish(source, index) + "\nCurrent Hindi: "
                    + translated.getSentence() + "\nDetected concern: " + item.getOrDefault("problem", "")
                    + "\nProvide one faithful Hindi replacement for the exact English line. Preserve every "
                    + "chemical identity, agent, action, negation, formula, number and unit. "
                    + "If the English sounds wrong, translate it literally. Return JSON only."
                    + (terminology == null || terminology.hints(original.getSentence()).isBlank() ? ""
                        : "\nRequired school terminology (use exactly these Hindi words): " + terminology.hints(original.getSentence()));
                String candidate = com.google.gson.JsonParser.parseString(ollama.generateStructured(
                    "You translate Grade 10 science lessons accurately from English into Hindi.",
                    prompt, candidateSchema, 800)).getAsJsonObject().get("candidate").getAsString().trim();
                if (terminology != null) candidate = terminology.normalize(original.getSentence(), candidate);
                if (candidate.isBlank() || candidate.equals(translated.getSentence())
                    || StoryboardTranslator.problem(original.getSentence(), candidate, to) != null) continue;
                String auditPrompt = "English source: " + original.getSentence() + "\nHindi candidate: " + candidate
                    + "\nCompare literally. Ignore style. Check scientific entities, actions, negation, "
                    + "formulas, numbers and units. If uncertain, mark faithful false. Return JSON only.";
                var audit = com.google.gson.JsonParser.parseString(ollama.generateStructured(
                    "You are a strict bilingual translation auditor. Do not infer facts absent from the English source.",
                    auditPrompt, auditSchema, 700)).getAsJsonObject();
                if (!audit.get("faithful").getAsBoolean()
                    || audit.getAsJsonArray("missing_facts").size() != 0
                    || audit.getAsJsonArray("added_or_changed_facts").size() != 0) continue;
                translated.setSentence(candidate);
                result.storyboard().getScenes().get(index).setNarration(candidate);
                String narrationWhere = "scene " + (index + 1) + ".1 narration";
                result.reviews().removeIf(review -> narrationWhere.equals(review.getOrDefault("where", "")));
                item.put("translation", candidate);
                item.put("resolution", "verified_repair");
                item.put("problem", "semantic repair passed a separate bilingual audit; teacher review still required. Original concern: "
                    + item.getOrDefault("problem", ""));
                logger.info("Semantic translation candidate accepted for teacher review at scene {}.1", index + 1);
            } catch (Exception error) {
                logger.warn("Semantic correction unavailable at scene {}.1; retaining the review finding: {}",
                    index + 1, error.toString());
            }
        }
    }

    /** Chooses the offline MADLAD worker when it is installed (and requested or "auto"), otherwise the local Qwen model. */
    private StoryboardTranslator.Result translateWithEngine(StoryboardDocument storyboard, Language source, Language target,
                                                            Glossary glossary, TerminologyGlossary terminology) throws Exception {
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
                return new StoryboardTranslator(worker, 4, true, terminology).translate(storyboard, source, target);   // + back-translation drift check
            }
        }
        logger.info("Translating with the local language model ({}); install MADLAD-400 for better Indic quality", config.getOllamaModel());
        // Run the same source-grounded back-translation check for Ollama as for MADLAD.
        return new StoryboardTranslator(new OllamaTextTranslator(ollama, glossary), 14, true, terminology).translate(storyboard, source, target);
    }

    /** Writes a DOCX/contract for an already-built translation (used by tests and tools). */
    static void writeText(Path file, String text) throws IOException {
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }
}
