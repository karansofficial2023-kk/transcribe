package com.video.transcribe;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.video.transcribe.config.AppConfig;
import com.video.transcribe.llm.OllamaClient;
import com.video.transcribe.scene.SceneStoryboardGenerator;
import com.video.transcribe.scene.StoryboardDocument;
import com.video.transcribe.scene.StoryboardQualityGate;
import com.video.transcribe.scene.VisualDirector;

/** Re-plans the visuals of an existing storyboard JSON and re-exports DOCX + contract JSON. */
public final class VisualDirectorRunner {
    private VisualDirectorRunner() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: VisualDirectorRunner <storyboard.json> <output-folder> [language-code]");
        }
        Path input = Paths.get(args[0]);
        Path outputDir = Paths.get(args[1]);
        Files.createDirectories(outputDir);
        String baseName = input.getFileName().toString().replaceFirst("(?i)_storyboard(\\.draft)?\\.json$", "");
        AppConfig config = new AppConfig();
        OllamaClient ollama = new OllamaClient(config, config.getOllamaFastModel());
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        StoryboardDocument storyboard = gson.fromJson(Files.readString(input), StoryboardDocument.class);
        SceneStoryboardGenerator generator = new SceneStoryboardGenerator(ollama, config.isStoryboardAnimationEnabled(),
            config.getStoryboardVideoProvider(), config.isStoryboardCurriculumEnrichmentEnabled());
        // optional 3rd argument: the lesson's language code (needed when scripts are shared, e.g. Marathi vs Hindi); otherwise it is read from the text
        String languageCode = args.length > 2 ? args[2] : null;
        generator.setLanguageHint(languageCode);
        generator.setVisualDirector(new VisualDirector(ollama, generator::finishImagePrompt, generator::enforceFormulaRouting));
        generator.finalizeProductionContract(storyboard);
        Files.writeString(outputDir.resolve(baseName + "_storyboard.draft.json"), gson.toJson(storyboard));
        StoryboardQualityGate.validate(storyboard);
        Files.writeString(outputDir.resolve(baseName + "_storyboard.json"), gson.toJson(storyboard));
        StoryboardDocxExporter exporter = new StoryboardDocxExporter(config.getStoryboardVideoProvider());
        if (languageCode != null && !languageCode.isBlank()) {
            exporter.withLanguage(StoryboardContract.LanguagePackage.forWhisperCode(languageCode));
        }
        exporter
            .export(storyboard, outputDir.resolve(baseName + "_storyboard.docx").toString());
        System.out.println("Exported " + outputDir.resolve(baseName + "_storyboard.docx").toAbsolutePath());
    }
}
