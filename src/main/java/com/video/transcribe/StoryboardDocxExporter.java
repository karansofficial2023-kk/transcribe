package com.video.transcribe;

import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblWidth;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTblWidth;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STPageOrientation;

import com.video.transcribe.scene.Scene;
import com.video.transcribe.scene.SceneSegment;
import com.video.transcribe.scene.StoryboardContractAudit;
import com.video.transcribe.scene.StoryboardDocument;

/** Exports a storyboard as one exact two-column production record per shot. */
public class StoryboardDocxExporter {
    private final String videoProvider;

    public StoryboardDocxExporter() {
        this("wan");
    }

    public StoryboardDocxExporter(String videoProvider) {
        this.videoProvider = normalizeVideoProvider(videoProvider);
    }
    
    private StoryboardContract.LanguagePackage language = StoryboardContract.LanguagePackage.defaults();
    private boolean languageSet;

    public StoryboardDocxExporter withLanguage(StoryboardContract.LanguagePackage language) {
        if (language != null) {
            this.language = language;
            this.languageSet = true;
        }
        return this;
    }

    /**
     * The language package written into the contract. When the caller did not say, it is read from the narration itself: an exporter used
     * by a re-plan or regeneration tool must never turn a Tamil storyboard into an English ("en-IN") contract.
     */
    StoryboardContract.LanguagePackage languageFor(StoryboardDocument storyboard) {
        if (languageSet) return language;
        StringBuilder text = new StringBuilder();
        if (storyboard.getScenes() != null) {
            for (Scene scene : storyboard.getScenes()) {
                if (scene.getSegments() == null) continue;
                for (SceneSegment segment : scene.getSegments()) {
                    if (segment.getSentence() != null) text.append(segment.getSentence()).append(' ');
                }
            }
        }
        return StoryboardContract.LanguagePackage.forWhisperCode(LanguageSupport.detect(text.toString()).code());
    }

    public void export(StoryboardDocument storyboard, String outputPath) throws IOException {
        StoryboardContractAudit.separateFormulaProse(storyboard);
        exportDocx(storyboard, outputPath);
        writeContractFiles(storyboard, outputPath);
    }

    /** Writes <base>_contract.json (schema 2.0) and <base>_audit.json next to the DOCX. */
    private void writeContractFiles(StoryboardDocument storyboard, String outputPath) throws IOException {
        java.nio.file.Path docx = java.nio.file.Paths.get(outputPath);
        String name = docx.getFileName().toString().replaceFirst("(?i)\\.docx$", "")
            .replaceFirst("_storyboard$", "");
        java.nio.file.Path dir = docx.toAbsolutePath().getParent();
        com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting()
            .disableHtmlEscaping().create();
        java.nio.file.Files.writeString(dir.resolve(name + "_contract.json"),
            gson.toJson(StoryboardContract.toContract(storyboard, languageFor(storyboard))), java.nio.charset.StandardCharsets.UTF_8);
        List<java.util.Map<String, Object>> issues = new ArrayList<>();
        for (StoryboardContractAudit.Issue issue : StoryboardContractAudit.inspect(storyboard)) {
            issues.add(issue.toMap());
        }
        java.util.Map<String, Object> audit = new java.util.LinkedHashMap<>();
        audit.put("schema_version", StoryboardContract.SCHEMA_VERSION);
        audit.put("issue_count", issues.size());
        audit.put("issues", issues);
        java.nio.file.Files.writeString(dir.resolve(name + "_audit.json"), gson.toJson(audit),
            java.nio.charset.StandardCharsets.UTF_8);
    }

    private void exportDocx(StoryboardDocument storyboard, String outputPath) throws IOException {
        try (XWPFDocument document = new XWPFDocument()) {
            
            // Set document margins
            setDocumentMargins(document);
            
            // Title
            addTitle(document, "Storyboard: " + storyboard.getTitle());
            
            // Which shots move (opening, closing, planned natural motion) is decided once, here and in the JSON contract
            com.video.transcribe.scene.MotionPlanner.plan(storyboard.getScenes());

            // Process each scene
            for (Scene scene : storyboard.getScenes()) {
                addScene(document, scene);
            }
            
            // Save
            try (FileOutputStream out = new FileOutputStream(outputPath)) {
                document.write(out);
            }
        }
    }
    
    private void setDocumentMargins(XWPFDocument document) {
        // Set narrow margins for better table display
        CTSectPr sectPr = document.getDocument().getBody().addNewSectPr();
        org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar pageMar = sectPr.addNewPgMar();
        pageMar.setLeft(BigInteger.valueOf(720));   // 0.5 inch
        pageMar.setRight(BigInteger.valueOf(720));  // 0.5 inch
        pageMar.setTop(BigInteger.valueOf(720));
        pageMar.setBottom(BigInteger.valueOf(720));
        sectPr.addNewPgSz().setOrient(STPageOrientation.LANDSCAPE);
        sectPr.getPgSz().setW(BigInteger.valueOf(15840));
        sectPr.getPgSz().setH(BigInteger.valueOf(12240));
    }
    
    private void addTitle(XWPFDocument doc, String text) {
        XWPFParagraph title = doc.createParagraph();
        title.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun run = title.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontSize(18);
        run.setFontFamily("Arial");
        run.setColor("000000");
    }

    private void addScene(XWPFDocument doc, Scene scene) {
        // Scene Header
        XWPFParagraph sceneHeader = doc.createParagraph();
        sceneHeader.setSpacingBefore(200);
        XWPFRun headerRun = sceneHeader.createRun();
        headerRun.setText("Scene " + scene.getSceneNumber() + ": " + scene.getSceneTitle());
        headerRun.setBold(true);
        headerRun.setFontSize(14);
        headerRun.setFontFamily("Arial");
        headerRun.setColor("000000");
        
        // Narration/Audiobook label
        XWPFParagraph narrLabel = doc.createParagraph();
        narrLabel.setSpacingBefore(100);
        XWPFRun narrLabelRun = narrLabel.createRun();
        narrLabelRun.setText("Narration/Audio/Voiceover:");
        narrLabelRun.setBold(true);
        narrLabelRun.setItalic(true);
        narrLabelRun.setFontSize(11);
        narrLabelRun.setFontFamily("Arial");
        
        // Narration text (quoted)
        XWPFParagraph narrText = doc.createParagraph();
        narrText.setIndentationLeft(400);
        XWPFRun narrRun = narrText.createRun();
        narrRun.setText("\"" + scene.getNarration() + "\"");
        narrRun.setItalic(true);
        narrRun.setFontSize(10);
        narrRun.setFontFamily("Arial");
        
        // Each segment is a readable production specification, not a wide spreadsheet row.
        if (scene.getSegments() != null && !scene.getSegments().isEmpty()) {
            for (SceneSegment segment : scene.getSegments()) {
                addSegmentSpecification(doc, scene.getSceneNumber(), segment);
            }
        }
        addEmptyLine(doc);
    }

    private void addSegmentSpecification(XWPFDocument doc, int sceneNumber, SceneSegment segment) {
        XWPFParagraph shotHeader = doc.createParagraph();
        shotHeader.setSpacingBefore(140);
        shotHeader.setSpacingAfter(70);
        XWPFRun header = shotHeader.createRun();
        String shotLabel = "Shot " + sceneNumber + "." + segment.getSegmentNumber();
        if (segment.getHeading() != null && !segment.getHeading().isBlank()) {
            shotLabel += ": " + segment.getHeading();
        }
        header.setText(shotLabel);
        header.setBold(true);
        header.setFontFamily("Arial");
        header.setFontSize(12);
        header.setColor("000000");

        List<String[]> fields = new ArrayList<>();
        for (java.util.Map.Entry<String, Object> entry : StoryboardContract.shotRecord(sceneNumber, segment).entrySet()) {
            String name = entry.getKey();
            Object value = entry.getValue();
            String text;
            if (value instanceof List<?> items) {
                text = items.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining("\n"));
                if (text.isBlank() && StoryboardContract.OPTIONAL_LIST_FIELDS.contains(name)) continue;
            } else if (value instanceof Double seconds) {
                text = seconds + " sec";
            } else {
                text = String.valueOf(value);
            }
            fields.add(new String[] {name, text});
        }

        XWPFTable table = doc.createTable(fields.size(), 2);
        table.setWidth("100%");
        CTTblWidth tblWidth = table.getCTTbl().getTblPr().addNewTblW();
        tblWidth.setType(STTblWidth.PCT);
        tblWidth.setW(BigInteger.valueOf(5000));
        var grid = table.getCTTbl().addNewTblGrid();
        grid.addNewGridCol().setW(BigInteger.valueOf(2400));
        grid.addNewGridCol().setW(BigInteger.valueOf(10800));
        for (XWPFTableRow row : table.getRows()) {
            row.setCantSplitRow(true);
        }

        for (int i = 0; i < fields.size(); i++) {
            setFieldRow(table.getRow(i), fields.get(i)[0], fields.get(i)[1]);
        }
    }

    private String shotPrompt(com.video.transcribe.scene.Shot shot) {
        return shot == null ? "" : safe(shot.getPrompt());
    }

    private String productionMediaType(SceneSegment segment) {
        if (segment.getAssetPath() != null && !segment.getAssetPath().isBlank()) return "supplied image";
        if ("manim".equals(segment.getTool())) return "Manim animation";
        if ("short_motion_clip".equals(segment.getVisualType())) {
            if (segment.getLtxShot() != null || "ltx_video".equals(segment.getTool())) return "LTX clip";
            return "Wan clip";
        }
        if ("diagram_overlay".equals(segment.getVisualType())
                || "process_steps".equals(segment.getVisualType())) return "local diagram";
        if ("split_screen".equals(segment.getVisualType())) return "FFmpeg composite";
        return "generated still";
    }

    private void addField(List<String[]> fields, String name, String value) {
        if (value != null && !value.isBlank()) {
            fields.add(new String[] {name, value.trim()});
        }
    }

    private void setFieldRow(XWPFTableRow row, String field, String value) {
        XWPFTableCell fieldCell = row.getCell(0);
        setCellText(fieldCell, field);
        fieldCell.setColor("D9E2F3");
        styleCell(fieldCell, ParagraphAlignment.LEFT);
        XWPFRun fieldRun = fieldCell.getParagraphs().get(0).getRuns().get(0);
        fieldRun.setBold(true);
        fieldRun.setColor("1F355E");

        XWPFTableCell valueCell = row.getCell(1);
        setCellText(valueCell, value == null ? "" : value);
        styleCell(valueCell, ParagraphAlignment.LEFT);
    }

    private void setCellText(XWPFTableCell cell, String value) {
        XWPFParagraph paragraph = cell.getParagraphs().get(0);
        for (int i = paragraph.getRuns().size() - 1; i >= 0; i--) {
            paragraph.removeRun(i);
        }
        String[] lines = (value == null ? "" : value).split("\\R", -1);
        XWPFRun run = paragraph.createRun();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) run.addBreak();
            run.setText(lines[i]);
        }
    }

    private String formatShot(com.video.transcribe.scene.Shot shot) {
        if (shot == null) {
            return "";
        }

        String template = shot.getTemplate() != null ? shot.getTemplate() : "";
        String heading = shot.getHeading() != null ? shot.getHeading() : "";
        if (template.isBlank() && heading.isBlank()) {
            return "";
        }
        String prompt = shot.getPrompt() != null ? shot.getPrompt() : "";
        String negativePrompt = shot.getNegativePrompt() != null ? shot.getNegativePrompt() : "";
        return "template: " + template + "\nheading: " + heading
            + "\nduration: " + shot.getDurationSeconds() + " sec"
            + "\nclip duration: " + shot.getClipDurationSeconds() + " sec"
            + "\nclip count: " + shot.getClipCount()
            + "\njoin: " + (shot.getJoinInstructions() != null ? shot.getJoinInstructions() : "")
            + "\nprompt: " + prompt + "\nnegative: " + negativePrompt;
    }

    private String formatTiming(SceneSegment segment) {
        return joinNonBlank(
            "narration_duration: " + segment.getEstimatedNarrationSeconds() + " sec",
            "duration: " + segment.getRecommendedClipSeconds() + " sec",
            safe(segment.getTimingNotes()));
    }

    private String formatTemplate(String template) {
        if (template == null || template.isBlank()) {
            return "";
        }
        return switch (template) {
            case "title_card" -> "Title card";
            case "photo" -> "Photo";
            case "labeled_image" -> "Labeled image";
            case "comparison" -> "Comparison";
            case "process" -> "Process";
            case "formula" -> "Formula";
            case "split_screen" -> "Split screen";
            case "video_broll" -> "Video b-roll";
            default -> template;
        };
    }

    private String formatVisualSubject(SceneSegment segment) {
        return joinNonBlank(
            fieldLine("visual_type", segment.getVisualType()),
            fieldLine("image_requirement", segment.getVisualSubject()),
            fieldLine("visual notes", segment.getVisualAnimation()),
            fieldLine("local animation", segment.getLocalAnimation()));
    }

    private String formatAssetPrompt(SceneSegment segment) {
        return joinNonBlank(
            fieldLine("asset_path", segment.getAssetPath()),
            fieldBlock("image recommendations", formatList(segment.getImageRecommendations())),
            fieldBlock("image_prompt", segment.getComfyPrompt()));
    }

    private String formatMotionAndSubtitle(SceneSegment segment) {
        return joinNonBlank(
            fieldLine("motion", segment.getMotion()),
            fieldLine("subtitle", segment.getSubtitle()),
            fieldLine("subtitle_style", segment.getSubtitleStyle()),
            fieldBlock("arrows", formatList(segment.getArrows())),
            fieldBlock("highlights", formatList(segment.getHighlights())));
    }

    private String formatCoverageAndQuality(SceneSegment segment) {
        return joinNonBlank(
            safe(segment.getCoverageNotes()),
            fieldBlock("asset quality", segment.getAssetQualityNotes()),
            fieldBlock("formula lines", formatList(segment.getFormulaLines())),
            fieldBlock("explain steps", formatList(segment.getExplainSteps())),
            fieldBlock("steps", formatList(segment.getSteps())),
            fieldBlock("columns", formatList(segment.getColumns())));
    }

    private String fieldLine(String name, String value) {
        return value == null || value.isBlank() ? "" : name + ": " + value.trim();
    }

    private String fieldBlock(String name, String value) {
        return value == null || value.isBlank() ? "" : name + ":\n" + value.trim();
    }

    private String joinNonBlank(String... values) {
        return java.util.Arrays.stream(values)
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .collect(java.util.stream.Collectors.joining("\n"));
    }

    private String safe(String value) {
        return value != null ? value : "";
    }

    private String formatMediaType(String mediaType) {
        if (mediaType == null || mediaType.isBlank()) {
            return "";
        }
        return switch (mediaType) {
            case "photo" -> "Photo";
            case "diagram" -> "Diagram";
            case "animation" -> "Animation";
            case "photo_with_labels" -> "Photo with labels";
            case "animation_with_labels" -> "Animation with labels";
            case "wan_video" -> videoProviderLabel() + " video";
            default -> mediaType;
        };
    }

    private String formatMotionType(String motionType) {
        if (motionType == null || motionType.isBlank()) {
            return "";
        }
        return switch (motionType) {
            case "wan_video" -> videoProviderLabel() + " video";
            case "local_animation" -> "Local animation";
            case "static_image" -> "Static image";
            default -> motionType;
        };
    }

    private String videoProviderLabel() {
        return switch (videoProvider) {
            case "ltx" -> "LTX";
            case "all" -> "Wan/LTX";
            default -> "Wan";
        };
    }

    private String normalizeVideoProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return "wan";
        }
        String normalized = provider.trim().toLowerCase();
        if ("ltx".equals(normalized) || "wan".equals(normalized) || "all".equals(normalized)) {
            return normalized;
        }
        return "wan";
    }

    private String formatList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                text.append(value).append("\n");
            }
        }
        return text.toString().trim();
    }
    
    private void styleCell(XWPFTableCell cell, ParagraphAlignment alignment) {
        XWPFParagraph para = cell.getParagraphs().get(0);
        para.setAlignment(alignment);
        if (para.getRuns().isEmpty()) {
            para.createRun();
        }
        for (XWPFRun run : para.getRuns()) {
            run.setFontSize(9);
            run.setFontFamily("Arial");
        }
        
        // Add borders
        cell.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER);
    }
    
    private void addEmptyLine(XWPFDocument doc) {
        XWPFParagraph empty = doc.createParagraph();
        empty.setSpacingAfter(100);
    }
}
