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
