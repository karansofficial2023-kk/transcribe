package com.video.transcribe;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import com.google.gson.Gson;
import com.video.transcribe.LanguageSupport.Language;
import com.video.transcribe.config.AppConfig;
import com.video.transcribe.llm.OllamaClient;
import com.video.transcribe.scene.StoryboardDocument;

/**
 * Writes an existing storyboard in more languages without re-planning it.
 *
 * <pre>
 * mvnw spring-boot:run -Dspring-boot.run.main-class=com.video.transcribe.StoryboardTranslatorRunner \
 *      -Dspring-boot.run.arguments="&lt;lesson_storyboard.json&gt; &lt;output-folder&gt; ta,te"
 * </pre>
 * Targets are codes or names (ta, te, hi, Tamil, ...). The storyboard's own language is detected from its narration.
 */
public final class StoryboardTranslatorRunner {
    private StoryboardTranslatorRunner() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException("Usage: StoryboardTranslatorRunner <storyboard.json> <output-folder> <languages e.g. ta,te> [source-language-code]");
        }
        Path input = Paths.get(args[0]);
        Path outputDir = Paths.get(args[1]);
        List<Language> targets = StoryboardLocalizer.parseTargets(args[2]);
        String baseName = input.getFileName().toString().replaceFirst("(?i)_storyboard(\\.draft)?\\.json$", "");

        AppConfig config = new AppConfig();
        StoryboardDocument storyboard = new Gson().fromJson(Files.readString(input, StandardCharsets.UTF_8), StoryboardDocument.class);
        // optional 4th argument: the storyboard's own language code, needed when the script is shared (Marathi vs Hindi)
        Language source = LanguageSupport.detect(StoryboardLocalizer.joinedNarration(storyboard), args.length > 3 ? args[3] : null);
        System.out.println("Source language: " + source.name() + "; targets: " + targets.stream().map(Language::name).toList());

        OllamaClient mainModel = new OllamaClient(config);          // translation quality matters more than speed: the main model
        List<Path> written = new StoryboardLocalizer(config, mainModel).localize(storyboard, baseName, source, targets, outputDir);
        mainModel.unload();
        written.forEach(path -> System.out.println("Exported " + path.toAbsolutePath()));
    }
}
