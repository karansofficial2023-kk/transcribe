package com.video.transcribe.scene;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

/** Loads optional per-project evidence without mixing materials between queued lessons. */
public final class StoryboardProjectMaterialsLoader {
    private static final int MAX_FILE_CHARS = 60_000;
    private static final int MAX_TOTAL_CHARS = 240_000;
    private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "md", "csv", "tsv", "json");
    private static final Set<String> ASSET_EXTENSIONS = Set.of(
        "png", "jpg", "jpeg", "webp", "bmp", "tif", "tiff", "svg",
        "mp4", "mov", "mkv", "avi", "webm");

    private StoryboardProjectMaterialsLoader() {
    }

    public static StoryboardProjectMaterials load(String configuredDirectory, String baseName)
            throws IOException {
        if (configuredDirectory == null || configuredDirectory.isBlank()) {
            return StoryboardProjectMaterials.empty();
        }
        String resolved = configuredDirectory.replace("{baseName}", safeFileName(baseName));
        Path root = Path.of(resolved).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IOException("Storyboard materials directory does not exist: " + root);
        }

        List<Path> files;
        try (Stream<Path> stream = Files.walk(root)) {
            files = stream.filter(Files::isRegularFile)
                .sorted(Comparator.comparingInt(StoryboardProjectMaterialsLoader::priority)
                    .thenComparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                .toList();
        }

        StringBuilder context = new StringBuilder();
        List<Path> assets = new ArrayList<>();
        for (Path file : files) {
            if (!isRelevantToProject(file, baseName)) {
                continue;
            }
            if (isUnapprovedGeneratedStoryboard(file) || isPipelineOutput(file, baseName) || isOwnSource(file, baseName) || isGlossary(file)) {
                continue;
            }
            String extension = extension(file);
            if (ASSET_EXTENSIONS.contains(extension)) {
                assets.add(file.toAbsolutePath().normalize());
                context.append("\n[SUPPLIED ASSET CANDIDATE; USE asset_path ONLY IF ITS APPROVAL IS EXPLICIT] ")
                    .append(file.toAbsolutePath().normalize()).append('\n');
                continue;
            }
            String content = extractText(file, extension);
            if (content == null || content.isBlank()) continue;
            String section = "\n[PRIORITY " + priority(file) + " | "
                + file.getFileName() + "]\n" + truncate(content, MAX_FILE_CHARS) + "\n";
            if (context.length() + section.length() > MAX_TOTAL_CHARS) {
                throw new IOException("Storyboard project materials exceed " + MAX_TOTAL_CHARS
                    + " characters; split or curate the project source folder");
            }
            context.append(section);
        }
        return new StoryboardProjectMaterials(context.toString().trim(), assets);
    }

    /**
     * Files this pipeline writes about a lesson: {@code <lesson>_transcript.txt} and the like. They are derived from the lesson, so they
     * can never serve as independent evidence. A teacher's own {@code narration_transcript.txt} (different prefix) still counts.
     */
    static boolean isPipelineOutput(Path file, String baseName) {
        if (baseName == null || baseName.isBlank()) return false;
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        String prefix = baseName.toLowerCase(Locale.ROOT);
        for (String suffix : new String[] {"_transcript.", "_paraphrased.", "_validation.", "_manifest.", "_contract.", "_audit.",
                "_grounding.", "_glossary_candidates.", "_curriculum_enriched.", "_translation_review.", "_storyboard.draft."}) {
            if (name.startsWith(prefix + suffix)) return true;
            // translated outputs: <lesson>_<code>_contract.json
            if (name.startsWith(prefix + "_") && name.matches(".*_[a-z]{2}" + java.util.regex.Pattern.quote(suffix) + ".*")) return true;
        }
        return false;
    }

    /** The lesson's own source recording is the thing being taught, not a reference for it. */
    static boolean isOwnSource(Path file, String baseName) {
        if (baseName == null || baseName.isBlank()) return false;
        String name = file.getFileName().toString();
        String extension = extension(file);
        if (!ASSET_EXTENSIONS.contains(extension) || !Set.of("mp4", "mov", "mkv", "avi", "webm").contains(extension)) return false;
        return normalizeKey(name.substring(0, name.length() - extension.length() - 1)).equals(normalizeKey(baseName));
    }

    private static boolean isUnapprovedGeneratedStoryboard(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.contains("storyboard")) return false;
        return !containsAny(name,
            "teacher approved", "teacher_approved", "teacher-approved",
            "approved storyboard", "approved_storyboard", "approved-storyboard");
    }

    private static String extractText(Path file, String extension) throws IOException {
        if (TEXT_EXTENSIONS.contains(extension)) {
            return Files.readString(file, StandardCharsets.UTF_8);
        }
        if ("docx".equals(extension)) {
            try (var input = Files.newInputStream(file); XWPFDocument document = new XWPFDocument(input)) {
                StringBuilder text = new StringBuilder();
                document.getParagraphs().forEach(paragraph -> text.append(paragraph.getText()).append('\n'));
                document.getTables().forEach(table -> table.getRows().forEach(row -> {
                    row.getTableCells().forEach(cell -> text.append(cell.getText()).append(" | "));
                    text.append('\n');
                }));
                return text.toString();
            }
        }
        if ("pptx".equals(extension)) {
            try (var input = Files.newInputStream(file); XMLSlideShow slideShow = new XMLSlideShow(input)) {
                StringBuilder text = new StringBuilder();
                int slideNumber = 0;
                for (var slide : slideShow.getSlides()) {
                    text.append("Slide ").append(++slideNumber).append(':').append('\n');
                    for (XSLFShape shape : slide.getShapes()) {
                        if (shape instanceof XSLFTextShape textShape) {
                            text.append(textShape.getText()).append('\n');
                        }
                    }
                }
                return text.toString();
            }
        }
        if ("pdf".equals(extension)) {
            try (var document = Loader.loadPDF(file.toFile())) {
                return new PDFTextStripper().getText(document);
            }
        }
        return "[SUPPLIED FILE INVENTORY] " + file.toAbsolutePath().normalize();
    }

    private static int priority(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (containsAny(name, "teacher correction", "teacher_correction", "approved correction")) return 1;
        if (containsAny(name, "approved script", "approved_script", "approved storyboard", "approved_storyboard")) return 2;
        if (containsAny(name, "curriculum", "learning objective", "learning_objective", "syllabus")) return 3;
        if (containsAny(name, "authoritative", "reference", "standard")) return 4;
        if (containsAny(name, "textbook", "lesson note", "lesson_note", "subject note", "subject_note")) return 5;
        if (containsAny(name, "transcript", "narration")) return 6;
        return 7;
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }

    private static boolean isRelevantToProject(Path file, String baseName) {
        if (baseName == null || baseName.isBlank()) {
            return true;
        }
        // The lesson name must appear as whole words in the path: "Lesson 1" must not pull in "Lesson 10 teacher correction.docx".
        if (containsWords(file.toString(), baseName)) {
            return true;
        }
        String fileName = file.getFileName().toString();
        int priority = priority(file);
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        boolean likelyLessonSpecific = containsAny(lowerName,
            "storyboard", "transcript", "paraphrase", "narration", "script", "lesson");
        return priority <= 5 && !likelyLessonSpecific;
    }

    /** True when {@code needle}'s words occur in {@code haystack} as a run of whole words (case-insensitive, any script). */
    static boolean containsWords(String haystack, String needle) {
        java.util.List<String> hay = words(haystack);
        java.util.List<String> want = words(needle);
        if (want.isEmpty()) return false;
        outer:
        for (int start = 0; start + want.size() <= hay.size(); start++) {
            for (int i = 0; i < want.size(); i++) {
                if (!hay.get(start + i).equals(want.get(i))) continue outer;
            }
            return true;
        }
        return false;
    }

    private static java.util.List<String> words(String text) {
        java.util.List<String> words = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("[\\p{L}\\p{M}\\p{N}]+").matcher(text == null ? "" : text.toLowerCase(Locale.ROOT));
        while (matcher.find()) words.add(matcher.group());
        return words;
    }

    /** Glossaries are spelling corrections for the recogniser, not statements about the subject. */
    static boolean isGlossary(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).contains("glossary");
    }

    private static String stripExtensionKey(String key) {
        return key.replaceAll("(docx|pptx|pdf|txt|md|json|csv|tsv|png|jpg|jpeg|webp|mp4|mov|mkv|avi|webm)$", "");
    }

    private static String normalizeKey(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{M}\\p{N}]+", "")
            .trim();
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String truncate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit) + "\n[TRUNCATED]";
    }

    private static String safeFileName(String value) {
        return value == null ? "" : value.replaceAll("[<>:\"/\\\\|?*]", "_").trim();
    }
}
