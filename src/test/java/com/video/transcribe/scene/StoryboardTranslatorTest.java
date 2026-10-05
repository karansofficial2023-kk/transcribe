package com.video.transcribe.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.video.transcribe.LanguageSupport;

class StoryboardTranslatorTest {
    private static final LanguageSupport.Language EN = LanguageSupport.byCode("en").orElseThrow();
    private static final LanguageSupport.Language TA = LanguageSupport.byCode("ta").orElseThrow();

    private static final String S1 = "The anode is a silver rod and the current is 6 volts.";
    private static final String T1 = "நேர்மின்வாய் ஒரு வெள்ளி கம்பி, மின்னோட்டம் 6 வோல்ட்.";
    private static final String S2 = "Silver ions gain electrons: Ag+ + e- gives Ag.";

    private static final Map<String, String> TAMIL = Map.ofEntries(
        Map.entry("Electroplating", "மின்முலாம் பூசுதல்"),
        Map.entry(S1, T1),
        Map.entry("Anode", "நேர்மின்வாய்"),
        Map.entry("Cathode", "எதிர்மின்வாய்"),
        Map.entry(S2, "வெள்ளி அயனிகள் எலக்ட்ரான்களைப் பெறுகின்றன: Ag+ + e- → Ag."),
        Map.entry("Setup", "அமைப்பு"),
        Map.entry("Silver plating", "வெள்ளி முலாம்"));

    private static SceneSegment shot(int number, String sentence, String template) {
        SceneSegment segment = new SceneSegment();
        segment.setSegmentNumber(number);
        segment.setSentence(sentence);
        segment.setHeading("Setup");
        segment.setSubtitle(sentence);
        segment.setTemplate(template);
        segment.setVisualType("realistic_image");
        segment.setComfyPrompt("A clear glass beaker, silver rod, copper keychain, no text");
        segment.setFormulaLines(List.of("Ag+ + e- -> Ag"));
        return segment;
    }

    private static StoryboardDocument document() {
        SceneSegment first = shot(1, S1, "labeled_image");
        first.setLabels(List.of("Anode", "Cathode"));
        first.setLabelPlacements(List.of("Anode: box=left; target=(0.3,0.4); target_description=silver rod",
            "Cathode: box=right; target=(0.7,0.4); target_description=keychain"));
        SceneSegment second = shot(2, S2, "circuit");
        second.setColumns(List.of("Silver plating | electrolysis: battery; anode silver rod; cathode keychain; electrolyte silver nitrate solution"));
        Scene scene = new Scene();
        scene.setSceneNumber(1);
        scene.setSceneTitle("Electroplating");
        scene.setSegments(List.of(first, second));
        StoryboardDocument document = new StoryboardDocument();
        document.setTitle("Electroplating");
        document.setScenes(List.of(scene));
        return document;
    }

    private static StoryboardTranslator.TextTranslator dictionary() {
        return (texts, from, to) -> {
            List<String> out = new ArrayList<>();
            for (String text : texts) out.add(TAMIL.getOrDefault(text, text));
            return out;
        };
    }

    @Test
    void translatesTextAndLeavesStructureExactlyAsPlanned() throws Exception {
        StoryboardTranslator.Result result = new StoryboardTranslator(dictionary()).translate(document(), EN, TA);
        SceneSegment first = result.storyboard().getScenes().get(0).getSegments().get(0);
        SceneSegment second = result.storyboard().getScenes().get(0).getSegments().get(1);

        assertEquals("மின்முலாம் பூசுதல்", result.storyboard().getTitle());
        assertEquals(T1, first.getSentence());
        assertEquals("அமைப்பு", first.getHeading());
        assertEquals(first.getSentence(), first.getSubtitle());
        assertEquals(List.of("நேர்மின்வாய்", "எதிர்மின்வாய்"), first.getLabels());
        assertEquals("A clear glass beaker, silver rod, copper keychain, no text", first.getComfyPrompt());
        assertEquals(List.of("Ag+ + e- -> Ag"), second.getFormulaLines());
        assertEquals("labeled_image", first.getTemplate());
        assertEquals("circuit", second.getTemplate());
        assertEquals(0, result.flagged());
    }

    @Test
    void labelPlacementsStayKeyedToTheTranslatedLabel() throws Exception {
        SceneSegment first = new StoryboardTranslator(dictionary()).translate(document(), EN, TA).storyboard()
            .getScenes().get(0).getSegments().get(0);
        assertTrue(first.getLabelPlacements().get(0).startsWith("நேர்மின்வாய்: box=left; target=(0.3,0.4)"));
        assertTrue(first.getLabelPlacements().get(1).startsWith("எதிர்மின்வாய்: box=right"));
    }

    @Test
    void onlyTheCaptionOfACircuitRowIsTranslated() throws Exception {
        SceneSegment second = new StoryboardTranslator(dictionary()).translate(document(), EN, TA).storyboard()
            .getScenes().get(0).getSegments().get(1);
        assertEquals("வெள்ளி முலாம் | electrolysis: battery; anode silver rod; cathode keychain; electrolyte silver nitrate solution",
            second.getColumns().get(0));
    }

    @Test
    void sceneNarrationIsRebuiltFromTheTranslatedShots() throws Exception {
        Scene scene = new StoryboardTranslator(dictionary()).translate(document(), EN, TA).storyboard().getScenes().get(0);
        assertTrue(scene.getNarration().startsWith("நேர்மின்வாய் ஒரு வெள்ளி கம்பி"));
        assertTrue(scene.getNarration().contains("வெள்ளி அயனிகள்"));
    }

    @Test
    void theOriginalStoryboardIsNeverModified() throws Exception {
        StoryboardDocument original = document();
        new StoryboardTranslator(dictionary()).translate(original, EN, TA);
        assertEquals("Electroplating", original.getTitle());
        assertEquals(S1, original.getScenes().get(0).getSegments().get(0).getSentence());
    }

    @Test
    void aDroppedNumberIsRetriedAndThenFlagged() throws Exception {
        StoryboardTranslator.TextTranslator forgetful = (texts, from, to) -> {
            List<String> out = new ArrayList<>();
            for (String text : texts) {
                out.add(text.equals(S1) ? "நேர்மின்வாய் ஒரு வெள்ளி கம்பி, மின்னோட்டம் ஆறு வோல்ட்." : TAMIL.getOrDefault(text, text));
            }
            return out;
        };
        StoryboardTranslator.Result result = new StoryboardTranslator(forgetful).translate(document(), EN, TA);
        SceneSegment first = result.storyboard().getScenes().get(0).getSegments().get(0);
        assertTrue(result.retried() >= 1);
        assertTrue(result.flagged() >= 1);
        assertTrue(first.getCoverageNotes().contains("TRANSLATION CHECK") && first.getCoverageNotes().contains("number 6"));
    }

    @Test
    void validationRules() {
        assertNull(StoryboardTranslator.problem("The anode is positive.", "நேர்மின்வாய் நேர்மறை முனையாகும்.", TA));
        assertNotNull(StoryboardTranslator.problem("The anode is positive.", "The anode is positive.", TA));
        assertNotNull(StoryboardTranslator.problem("The anode is positive.", "", TA));
        assertNotNull(StoryboardTranslator.problem("Water is H2O in this lesson.", "இந்த பாடத்தில் நீர் ஹைட்ரஜன் ஆக்சைடு.", TA));
        assertNull(StoryboardTranslator.problem("Water is H2O in this lesson.", "இந்த பாடத்தில் நீர் H2O ஆகும்.", TA));
        assertNull(StoryboardTranslator.problem("Anode", "நேர்மின்வாய்", TA));
        assertNotNull(StoryboardTranslator.problem("Anode", "Anode", TA));                       // a short label left in English is caught
        assertNotNull(StoryboardTranslator.problem("Electrolyte", "electrolyte", TA));
        assertNull(StoryboardTranslator.problem("Ag", "Ag", TA));                                // symbols are legitimately unchanged
    }

    @Test
    void cleanupDropsEnglishGlossesAndSeparatesGluedNumbers() {
        assertEquals("மின்னாற்பகுளி என்று அழைக்கப்படுகிறது.",
            StoryboardTranslator.cleanup("It is called the electrolyte.", "மின்னாற்பகுளி (electrolyte) என்று அழைக்கப்படுகிறது."));
        // a gloss that the source itself had is kept
        assertEquals("வெள்ளி (Ag) தண்டு", StoryboardTranslator.cleanup("silver (Ag) rod", "வெள்ளி (Ag) தண்டு"));
        assertEquals("ஒரு 6 வோல்ட் மின்சாரம்", StoryboardTranslator.cleanup("A 6 volt current", "ஒரு6வோல்ட் மின்சாரம்"));
        // formulas next to Latin letters are never split
        assertEquals("நீர் H2O ஆகும்", StoryboardTranslator.cleanup("Water is H2O", "நீர் H2O ஆகும்"));
    }

    @Test
    void backTranslationFlagsMeaningThatDrifted() throws Exception {
        String original = "The solution inside the electrolytic cell is referred to as the electrolyte.";
        String good = "எலக்ட்ரோலைட்டிக் கலத்தின் உள்ளே உள்ள கரைசல் எலக்ட்ரோலைட் எனப்படும்.";
        String drifted = "கதிரியக்கக் குறைப்பு கதிரியக்கக் குறைப்பு ஆகியவற்றில் நிகழ்வதால் வினை ஆகும்.";
        StoryboardTranslator.TextTranslator forward = (texts, from, to) -> {
            List<String> out = new ArrayList<>();
            for (String text : texts) {
                if (to.code().equals("ta")) out.add(text.equals(original) ? drifted : text.equals("Anode") ? "நேர்மின்வாய்" : good);
                else out.add(text.equals(drifted) ? "Radioactive reduction occurs during the reaction." : original);
            }
            return out;
        };
        SceneSegment shot = shot(1, original, "photo");
        Scene scene = new Scene();
        scene.setSceneNumber(1);
        scene.setSegments(List.of(shot));
        StoryboardDocument doc = new StoryboardDocument();
        doc.setScenes(List.of(scene));
        StoryboardTranslator.Result result = new StoryboardTranslator(forward, 10, true).translate(doc, EN, TA);
        assertTrue(result.drifted() >= 1);
        assertTrue(result.storyboard().getScenes().get(0).getSegments().get(0).getCoverageNotes().contains("meaning may have drifted"));
        assertTrue(StoryboardTranslator.overlap(original, original) == 1.0);
        assertTrue(StoryboardTranslator.overlap(original, "Radioactive reduction occurs during the reaction.") < StoryboardTranslator.DRIFT_BELOW);
    }

    @Test
    void sameLanguageIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new StoryboardTranslator(dictionary()).translate(document(), EN, EN));
    }
}
