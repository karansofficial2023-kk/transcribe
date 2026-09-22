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
import com.video.transcribe.scene.StoryboardDocument;

/**
 * Exports StoryboardDocument to formatted Word document (.docx)
 * Matches the format of your sample "Types of Pollination.docx"
 */
public class StoryboardDocxExporter {
    private final String videoProvider;

    public StoryboardDocxExporter() {
        this("wan");
    }

    public StoryboardDocxExporter(String videoProvider) {
        this.videoProvider = normalizeVideoProvider(videoProvider);
    }
    
    public void export(StoryboardDocument storyboard, String outputPath) throws IOException {
        try (XWPFDocument document = new XWPFDocument()) {
            
            // Set document margins
            setDocumentMargins(document);
            
            // Title
            addTitle(document, "Storyboard: " + storyboard.getTitle());
            addStoryboardMetadata(document, storyboard);
            
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

    private void addStoryboardMetadata(XWPFDocument doc, StoryboardDocument storyboard) {
        XWPFTable table = doc.createTable(3, 2);
        table.setWidth("100%");
        setFieldRow(table.getRow(0), "Subject", safe(storyboard.getSubject()));
        setFieldRow(table.getRow(1), "Verified Topic", safe(storyboard.getTopic()));
        setFieldRow(table.getRow(2), "SME Review Role", safe(storyboard.getSmeRole()));
        addEmptyLine(doc);
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
        if ("title_card".equals(segment.getVisualType())
                && segment.getHeading() != null && !segment.getHeading().isBlank()) {
            shotLabel += ": " + segment.getHeading();
        }
        header.setText(shotLabel);
        header.setBold(true);
        header.setFontFamily("Arial");
        header.setFontSize(12);
        header.setColor("000000");

        List<String[]> fields = new ArrayList<>();
        fields.add(new String[] {"shot_id", sceneNumber + "." + segment.getSegmentNumber()});
        fields.add(new String[] {"narration", safe(segment.getSentence())});
        fields.add(new String[] {"duration", segment.getRecommendedClipSeconds() + " sec"});
        fields.add(new String[] {"visual_type", safe(segment.getVisualType())});
        fields.add(new String[] {"media_type", safe(segment.getMediaType())});
        fields.add(new String[] {"image_requirement", safe(segment.getVisualSubject())});
        fields.add(new String[] {"image_prompt", safe(segment.getComfyPrompt())});
        fields.add(new String[] {"labels", formatList(segment.getLabels())});
        fields.add(new String[] {"label_placement", formatList(segment.getLabelPlacements())});
        fields.add(new String[] {"label_style", safe(segment.getLabelStyle())});
        fields.add(new String[] {"motion", safe(segment.getMotion())});
        fields.add(new String[] {"subtitle", safe(segment.getSubtitle())});
        fields.add(new String[] {"subtitle_style", safe(segment.getSubtitleStyle())});
        fields.add(new String[] {"asset_path", safe(segment.getAssetPath())});
        fields.add(new String[] {"wan_video_prompt", shotPrompt(segment.getShot())});
        fields.add(new String[] {"ltx_video_prompt", shotPrompt(segment.getLtxShot())});
        fields.add(new String[] {"review_notes", joinNonBlank(
            safe(segment.getCoverageNotes()), safe(segment.getAssetQualityNotes()))});

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
