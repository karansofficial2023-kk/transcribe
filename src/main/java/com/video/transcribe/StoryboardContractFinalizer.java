package com.video.transcribe;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.video.transcribe.scene.SceneStoryboardGenerator;
import com.video.transcribe.scene.StoryboardDocument;
import com.video.transcribe.scene.StoryboardQualityGate;

/** Applies deterministic production guards to a saved storyboard without an LLM call. */
public final class StoryboardContractFinalizer {
    private StoryboardContractFinalizer() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseArgs(args);
        Path input = requiredPath(options, "input");
        if (!Files.isRegularFile(input)) {
            throw new IllegalArgumentException("Storyboard JSON does not exist: " + input);
        }
        Path outputDir = Paths.get(options.getOrDefault("output",
            input.toAbsolutePath().getParent().toString()));
        String baseName = options.getOrDefault("base-name", baseName(input));
        Files.createDirectories(outputDir);

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        StoryboardDocument storyboard = gson.fromJson(Files.readString(input), StoryboardDocument.class);
        SceneStoryboardGenerator generator = new SceneStoryboardGenerator(null, false, "ltx", true);
        generator.finalizeProductionContract(storyboard);
        StoryboardQualityGate.validate(storyboard);

        Path jsonPath = outputDir.resolve(baseName + "_storyboard.json");
        Path docxPath = outputDir.resolve(baseName + "_storyboard.docx");
        Files.writeString(jsonPath, gson.toJson(storyboard));
        new StoryboardDocxExporter("ltx").export(storyboard, docxPath.toString());
        System.out.println("Storyboard JSON: " + jsonPath.toAbsolutePath());
        System.out.println("Storyboard DOCX: " + docxPath.toAbsolutePath());
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int index = 0; index < args.length; index++) {
            String arg = args[index];
            if (!arg.startsWith("--") || index + 1 >= args.length) throw usage();
            options.put(arg.substring(2), args[++index]);
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
            "Usage: StoryboardContractFinalizer --input <storyboard.json> "
                + "[--output <folder>] [--base-name <name>]");
    }

    private static String baseName(Path input) {
        return input.getFileName().toString()
            .replaceFirst("(?i)_storyboard(?:\\.draft)?\\.json$", "");
    }
}
