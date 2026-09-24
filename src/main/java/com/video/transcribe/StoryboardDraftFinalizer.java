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

/** Repairs label plans in a saved draft without regenerating its scenes or narration. */
public final class StoryboardDraftFinalizer {

    private StoryboardDraftFinalizer() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseArgs(args);
        Path draftPath = requiredPath(options, "draft");
        if (!Files.isRegularFile(draftPath)) {
            throw new IllegalArgumentException("Storyboard draft does not exist: " + draftPath);
        }
        Path outputDir = Paths.get(options.getOrDefault("output",
            draftPath.toAbsolutePath().getParent().toString()));
        String baseName = options.getOrDefault("base-name", baseName(draftPath));
        Files.createDirectories(outputDir);

        AppConfig config = new AppConfig();
        OllamaClient ollama = new OllamaClient(config);
        if (!ollama.isAvailable()) {
            throw new IllegalStateException("Ollama is unavailable at " + config.getOllamaUrl());
        }
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        StoryboardDocument storyboard = gson.fromJson(Files.readString(draftPath), StoryboardDocument.class);
        StoryboardProjectMaterials materials = StoryboardProjectMaterialsLoader.load(
            config.getStoryboardMaterialsDir(), baseName);
        SceneStoryboardGenerator generator = new SceneStoryboardGenerator(
            ollama,
            config.isStoryboardAnimationEnabled(),
            config.getStoryboardVideoProvider(),
            config.isStoryboardCurriculumEnrichmentEnabled());

        generator.repairProductionLabels(storyboard, baseName, materials);
        StoryboardQualityGate.validate(storyboard);
        Path jsonPath = outputDir.resolve(baseName + "_storyboard.json");
        Path docxPath = outputDir.resolve(baseName + "_storyboard.docx");
        Files.writeString(jsonPath, gson.toJson(storyboard));
        new StoryboardDocxExporter(config.getStoryboardVideoProvider())
            .export(storyboard, docxPath.toString());

        System.out.println("Storyboard JSON: " + jsonPath.toAbsolutePath());
        System.out.println("Storyboard DOCX: " + docxPath.toAbsolutePath());
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--") || i + 1 >= args.length) throw usage();
            options.put(arg.substring(2), args[++i]);
        }
        return options;
    }

    private static Path requiredPath(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) throw usage();
        return Paths.get(value);
    }

    private static IllegalArgumentException usage() {
        return new IllegalArgumentException(
            "Usage: StoryboardDraftFinalizer --draft <storyboard.draft.json> "
                + "[--output <folder>] [--base-name <name>]");
    }

    private static String baseName(Path draft) {
        String name = draft.getFileName().toString();
        return name.replaceFirst("(?i)_storyboard(?:\\.draft)?\\.json$", "");
    }
}
