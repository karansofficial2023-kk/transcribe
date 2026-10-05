package com.video.transcribe.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class ClaimGroundingTest {
    private static StoryboardDocument document(String... sentences) {
        Scene scene = new Scene();
        scene.setSceneNumber(1);
        java.util.List<SceneSegment> segments = new java.util.ArrayList<>();
        for (int i = 0; i < sentences.length; i++) {
            SceneSegment segment = new SceneSegment();
            segment.setSegmentNumber(i + 1);
            segment.setSentence(sentences[i]);
            segment.setVisualType("realistic_image");
            segments.add(segment);
        }
        scene.setSegments(segments);
        StoryboardDocument document = new StoryboardDocument();
        document.setScenes(List.of(scene));
        return document;
    }

    private static final String SOURCE = "In electroplating a silver rod is the anode and the keychain is the cathode. "
        + "The silver ions are attracted to the negative electrode and a current of 6 volts is applied for ten minutes.";

    @Test
    void sentencesBuiltFromTheSourceAreSupported() {
        ClaimGrounding.Report report = ClaimGrounding.check(document(
            "The silver rod acts as the anode while the keychain is the cathode.",
            "Silver ions are attracted to the negative electrode."), SOURCE);
        assertEquals(2, report.count(ClaimGrounding.Status.SUPPORTED));
    }

    @Test
    void inventedNumbersAreFlaggedEvenWhenTheWordsMatch() {
        ClaimGrounding.Report report = ClaimGrounding.check(document(
            "A current of 12 volts is applied to the silver rod for ten minutes."), SOURCE);
        ClaimGrounding.Finding finding = report.findings().get(0);
        assertEquals(ClaimGrounding.Status.UNSUPPORTED, finding.status());
        assertEquals(List.of("12"), finding.missingNumbers());
    }

    @Test
    void numberWordsAndDigitsAreInterchangeable() {
        ClaimGrounding.Report report = ClaimGrounding.check(document(
            "The current of six volts is applied for 10 minutes to the silver rod."), SOURCE);
        assertEquals(ClaimGrounding.Status.SUPPORTED, report.findings().get(0).status());
    }

    @Test
    void claimsAbsentFromEverySourceAreUnsupported() {
        ClaimGrounding.Report report = ClaimGrounding.check(document(
            "Chlorophyll absorbs sunlight inside the chloroplasts of every green leaf."), SOURCE);
        assertEquals(ClaimGrounding.Status.UNSUPPORTED, report.findings().get(0).status());
    }

    @Test
    void shortLinesAreNotChecked() {
        ClaimGrounding.Report report = ClaimGrounding.check(document("Thank you."), SOURCE);
        assertEquals(ClaimGrounding.Status.NOT_CHECKED, report.findings().get(0).status());
    }

    @Test
    void translatedNarrationIsCheckedForNumbersOnly() {
        ClaimGrounding.Report ok = ClaimGrounding.check(document("வெள்ளி கம்பி நேர்மின்வாய் ஆகும், 6 வோல்ட் மின்னோட்டம் பயன்படுகிறது."), SOURCE);
        assertEquals(ClaimGrounding.Status.SUPPORTED, ok.findings().get(0).status());
        assertTrue(ok.findings().get(0).crossLanguage());
        ClaimGrounding.Report bad = ClaimGrounding.check(document("வெள்ளி கம்பி நேர்மின்வாய் ஆகும், 9 வோல்ட் மின்னோட்டம் பயன்படுகிறது."), SOURCE);
        assertEquals(ClaimGrounding.Status.UNSUPPORTED, bad.findings().get(0).status());
    }

    @Test
    void groupedThousandsAndDecimalCommasAreRead() {
        assertEquals(java.util.Set.of("100000"), ClaimGrounding.digitNumbers("1,00,000"));     // Indian grouping
        assertEquals(java.util.Set.of("1000"), ClaimGrounding.digitNumbers("1,000"));
        assertEquals(java.util.Set.of("2500.5"), ClaimGrounding.digitNumbers("2,500.5"));
        assertEquals(java.util.Set.of("0.5"), ClaimGrounding.digitNumbers("0,5"));              // decimal comma
        assertEquals(java.util.Set.of("6", "10"), ClaimGrounding.digitNumbers("6 and 10"));
    }

    @Test
    void aTranslationIsComparedByNumbersEvenWhenBothLanguagesShareAScript() {
        String hindiSource = "पौधे सूर्य के प्रकाश से भोजन बनाते हैं और यह प्रक्रिया 6 घंटे चलती है";
        StoryboardDocument marathi = document("झाडे सूर्यप्रकाशातून अन्न तयार करतात आणि ही प्रक्रिया 6 तास चालते");
        assertEquals(ClaimGrounding.Status.UNSUPPORTED, ClaimGrounding.check(marathi, hindiSource).findings().get(0).status(),
            "compared word by word, a correct Marathi sentence looks unsupported by its Hindi source");
        ClaimGrounding.Report translated = ClaimGrounding.check(marathi, hindiSource, true);
        assertEquals(ClaimGrounding.Status.SUPPORTED, translated.findings().get(0).status());
        StoryboardDocument wrongNumber = document("झाडे सूर्यप्रकाशातून अन्न तयार करतात आणि ही प्रक्रिया 9 तास चालते");
        assertEquals(ClaimGrounding.Status.UNSUPPORTED, ClaimGrounding.check(wrongNumber, hindiSource, true).findings().get(0).status());
    }

    @Test
    void indicDigitsAreNormalised() {
        assertEquals(java.util.Set.of("6", "10"), ClaimGrounding.numbers("௬ and 10"));       // Tamil digit six
        assertEquals(java.util.Set.of("12"), ClaimGrounding.numbers("१२"));                   // Devanagari digits
    }

    @Test
    void annotateAddsAReviewNoteOnlyToFlaggedShots() {
        StoryboardDocument doc = document("Silver ions are attracted to the negative electrode.",
            "A current of 12 volts is applied to the silver rod.");
        ClaimGrounding.annotate(doc, ClaimGrounding.check(doc, SOURCE));
        SceneSegment first = doc.getScenes().get(0).getSegments().get(0);
        SceneSegment second = doc.getScenes().get(0).getSegments().get(1);
        assertTrue(first.getCoverageNotes() == null || !first.getCoverageNotes().contains("FACT CHECK"));
        assertTrue(second.getCoverageNotes().contains("FACT CHECK") && second.getCoverageNotes().contains("12"));
    }
}
