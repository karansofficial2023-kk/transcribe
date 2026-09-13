package com.video.transcribe;

import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
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

import com.video.transcribe.scene.Scene;
import com.video.transcribe.scene.SceneSegment;
import com.video.transcribe.scene.StoryboardDocument;

/**
 * Exports StoryboardDocument to formatted Word document (.docx)
 * Matches the format of your sample "Types of Pollination.docx"
 */
public class StoryboardDocxExporter {
    
    public void export(StoryboardDocument storyboard, String outputPath) throws IOException {
        try (XWPFDocument document = new XWPFDocument()) {
            
            // Set document margins
            setDocumentMargins(document);
            
            // Title
            addTitle(document, "Storyboard: " + storyboard.getTitle());
            addEmptyLine(document);
            
            // Process each scene
            for (Scene scene : storyboard.getScenes()) {
                addScene(document, scene);
                addEmptyLine(document);
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
    }
    
    private void addTitle(XWPFDocument doc, String text) {
        XWPFParagraph title = doc.createParagraph();
        title.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun run = title.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontSize(18);
        run.setFontFamily("Arial");
        run.setColor("2E5090");
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
        headerRun.setColor("1F4788");
        
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
        
        // Add table for segments
        if (scene.getSegments() != null && !scene.getSegments().isEmpty()) {
            addSegmentTable(doc, scene.getSegments());
        }
    }
    
    private void addSegmentTable(XWPFDocument doc, List<SceneSegment> segments) {
        // Table header row
        XWPFTable table = doc.createTable();
        table.setWidth("100%");
        
        // Set table width to 100%
        CTTblWidth tblWidth = table.getCTTbl().getTblPr().addNewTblW();
        tblWidth.setType(STTblWidth.PCT);
        tblWidth.setW(BigInteger.valueOf(5000)); // 100% in fiftieths of a percent
        
        // Header row
        XWPFTableRow headerRow = table.getRow(0);
        headerRow.setRepeatHeader(true);
        
        // Style header cells
        String[] headers = {"S.no", "Splitting the Narration (Sentence wise)", "Media Type", "Timing", "Motion Plan", "Visual / Animation", "Local Teaching Animation", "Labels", "Image Recommendation", "ComfyUI Prompt", "Coverage / Fact Guard", "Wan Video Shot", "LTX Video Shot"};
        
        for (int i = 0; i < headers.length; i++) {
            XWPFTableCell cell = headerRow.getCell(i) != null ? headerRow.getCell(i) : headerRow.addNewTableCell();
            cell.setText(headers[i]);
            cell.setColor("2E5090");
            
            XWPFParagraph para = cell.getParagraphs().get(0);
            para.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun run = para.getRuns().get(0);
            run.setBold(true);
            run.setColor("FFFFFF");
            run.setFontSize(10);
            run.setFontFamily("Arial");
        }
        
        // Data rows
        for (SceneSegment segment : segments) {
            XWPFTableRow row = table.createRow();
            
            // S.no
            XWPFTableCell cell1 = row.getCell(0);
            cell1.setText(String.valueOf(segment.getSegmentNumber()));
            styleCell(cell1, ParagraphAlignment.CENTER);
            
            // Sentence
            XWPFTableCell cell2 = row.getCell(1);
            cell2.setText("\"" + segment.getSentence() + "\"");
            styleCell(cell2, ParagraphAlignment.LEFT);
            
            // Media Type
            XWPFTableCell cell3 = row.getCell(2);
            cell3.setText(formatMediaType(segment.getMediaType()));
            styleCell(cell3, ParagraphAlignment.LEFT);

            // Timing
            XWPFTableCell cell4 = row.getCell(3);
            cell4.setText(formatTiming(segment));
            styleCell(cell4, ParagraphAlignment.LEFT);

            // Motion Plan
            XWPFTableCell cell5 = row.getCell(4);
            cell5.setText(formatMotionType(segment.getMotionType()));
            styleCell(cell5, ParagraphAlignment.LEFT);

            // Visual/Animation
            XWPFTableCell cell6 = row.getCell(5);
            cell6.setText(segment.getVisualAnimation());
            styleCell(cell6, ParagraphAlignment.LEFT);

            // Local Teaching Animation
            XWPFTableCell cell7 = row.getCell(6);
            cell7.setText(segment.getLocalAnimation() != null ? segment.getLocalAnimation() : "");
            styleCell(cell7, ParagraphAlignment.LEFT);

            // Labels
            XWPFTableCell cell8 = row.getCell(7);
            cell8.setText(formatList(segment.getLabels()));
            styleCell(cell8, ParagraphAlignment.LEFT);

            // Image Recommendations
            XWPFTableCell cell9 = row.getCell(8);
            cell9.setText(formatList(segment.getImageRecommendations()));
            styleCell(cell9, ParagraphAlignment.LEFT);

            // ComfyUI Prompt
            XWPFTableCell cell10 = row.getCell(9);
            cell10.setText(segment.getComfyPrompt() != null ? segment.getComfyPrompt() : "");
            styleCell(cell10, ParagraphAlignment.LEFT);

            // Coverage / Fact Guard
            XWPFTableCell cell11 = row.getCell(10);
            cell11.setText(segment.getCoverageNotes() != null ? segment.getCoverageNotes() : "");
            styleCell(cell11, ParagraphAlignment.LEFT);

            // Wan Video Shot
            XWPFTableCell cell12 = row.getCell(11);
            cell12.setText(formatShot(segment.getShot()));
            styleCell(cell12, ParagraphAlignment.LEFT);

            // LTX Video Shot
            XWPFTableCell cell13 = row.getCell(12);
            cell13.setText(formatShot(segment.getLtxShot()));
            styleCell(cell13, ParagraphAlignment.LEFT);
        }
        
        // Add spacing after table
        addEmptyLine(doc);
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
            + "\nprompt: " + prompt + "\nnegative: " + negativePrompt;
    }

    private String formatTiming(SceneSegment segment) {
        return "narration: " + segment.getEstimatedNarrationSeconds() + " sec"
            + "\nvisual: " + segment.getRecommendedClipSeconds() + " sec"
            + "\n" + (segment.getTimingNotes() != null ? segment.getTimingNotes() : "");
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
            case "wan_video" -> "Wan video";
            default -> mediaType;
        };
    }

    private String formatMotionType(String motionType) {
        if (motionType == null || motionType.isBlank()) {
            return "";
        }
        return switch (motionType) {
            case "wan_video" -> "Wan video";
            case "local_animation" -> "Local animation";
            case "static_image" -> "Static image";
            default -> motionType;
        };
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
        XWPFRun run = para.getRuns().get(0);
        run.setFontSize(9);
        run.setFontFamily("Arial");
        
        // Add borders
        cell.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER);
    }
    
    private void addEmptyLine(XWPFDocument doc) {
        XWPFParagraph empty = doc.createParagraph();
        empty.setSpacingAfter(100);
    }
}
