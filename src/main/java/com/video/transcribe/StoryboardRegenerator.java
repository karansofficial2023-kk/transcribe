package com.video.transcribe;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.video.transcribe.config.AppConfig;
import com.video.transcribe.llm.OllamaClient;
import com.video.transcribe.scene.SceneStoryboardGenerator;
import com.video.transcribe.scene.StoryboardDocument;
import com.video.transcribe.scene.StoryboardProjectMaterials;
import com.video.transcribe.scene.StoryboardProjectMaterialsLoader;
import com.video.transcribe.scene.StoryboardQualityGate;

/** Noninteractive storyboard regeneration from an existing narration or transcript. */
public final class StoryboardRegenerator {

    private StoryboardRegenerator() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseArgs(args);
        Path input = requiredPath(options, "input");
        Path outputDir = Paths.get(options.getOrDefault("output", "./output"));
        String baseName = options.getOrDefault("base-name", baseName(input));

        if (!Files.isRegularFile(input)) {
            throw new IllegalArgumentException("Input narration does not exist: " + input);
        }
        Files.createDirectories(outputDir);

        AppConfig config = new AppConfig();
        OllamaClient ollama = new OllamaClient(config);
        if (!ollama.isAvailable()) {
            throw new IllegalStateException("Ollama is unavailable at " + config.getOllamaUrl());
        }

        String narration = Files.readString(input).trim();
        if (narration.isBlank()) {
            throw new IllegalArgumentException("Input narration is empty: " + input);
        }

        StoryboardProjectMaterials materials = StoryboardProjectMaterialsLoader.load(
            config.getStoryboardMaterialsDir(), baseName);
        boolean prepared = Boolean.parseBoolean(options.getOrDefault("prepared", "false"));
        boolean skipEnrichment = Boolean.parseBoolean(options.getOrDefault("skip-enrichment", "false"));
        if (!prepared && !skipEnrichment && config.isStoryboardCurriculumEnrichmentEnabled()) {
            narration = clean(ollama.enrichParaphraseForCurriculum(
                narration, narration, materials.promptContext(), config.getParaphraseStyle()));
        }
        if (!prepared) {
            narration = clean(ollama.factCheckEducationalNarration(narration, materials.promptContext()));
        }
        if (narration.isBlank()) {
            throw new IllegalStateException("Narration preparation returned empty text");
        }
        Path narrationPath = outputDir.resolve(baseName + "_production_narration.txt");
        Files.writeString(narrationPath, narration);
        if (Boolean.parseBoolean(options.getOrDefault("narration-only", "false"))) {
            System.out.println("Production narration: " + narrationPath.toAbsolutePath());
            return;
        }

        SceneStoryboardGenerator generator = new SceneStoryboardGenerator(
            ollama,
            config.isStoryboardAnimationEnabled(),
            config.getStoryboardVideoProvider(),
            config.isStoryboardCurriculumEnrichmentEnabled());
        StoryboardDocument storyboard = generator.generateStoryboard(narration, baseName, materials);
        Path jsonPath = outputDir.resolve(baseName + "_storyboard.json");
        Path docxPath = outputDir.resolve(baseName + "_storyboard.docx");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        Files.writeString(outputDir.resolve(baseName + "_storyboard.draft.json"), gson.toJson(storyboard));
        StoryboardQualityGate.validate(storyboard);
        Files.writeString(jsonPath, gson.toJson(storyboard));
        new StoryboardDocxExporter(config.getStoryboardVideoProvider())
            .export(storyboard, docxPath.toString());

        System.out.println("Production narration: " + narrationPath.toAbsolutePath());
        System.out.println("Storyboard JSON: " + jsonPath.toAbsolutePath());
        System.out.println("Storyboard DOCX: " + docxPath.toAbsolutePath());
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--") || i + 1 >= args.length) {
                throw usage();
            }
            options.put(arg.substring(2), args[++i]);
        }
        return options;
    }

    private static Path requiredPath(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw usage();
        }
        return Paths.get(value);
    }

    private static IllegalArgumentException usage() {
        return new IllegalArgumentException(
            "Usage: StoryboardRegenerator --input <narration.txt> "
                + "[--output <folder>] [--base-name <name>] [--prepared true] "
                + "[--skip-enrichment true] [--narration-only true]");
    }

    private static String baseName(Path input) {
        String name = input.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name.replaceFirst("(?i)_(transcript|paraphrased|storyboard)$", "");
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value
            .replaceAll("(?im)^\\s*curriculum enrichment\\s*:\\s*", "")
            .replaceAll("(?m)^```[a-zA-Z]*\\s*$", "")
            .replaceAll("(?m)^```\\s*$", "")
            .replace("**", "")
            .replace("__", "")
            .replace("`", "")
            .replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "")
            .replaceAll("[ \\t]+", " ")
            .replaceAll("\\n{3,}", "\n\n")
            .trim();
    }
}
