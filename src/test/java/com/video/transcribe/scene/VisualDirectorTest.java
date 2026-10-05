package com.video.transcribe.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class VisualDirectorTest {
    private static final String NARRATION = "The heart has a left ventricle and an aorta that carries blood to the body.";

    private static SceneSegment segment() {
        SceneSegment segment = new SceneSegment();
        segment.setSegmentNumber(1);
        segment.setSentence(NARRATION);
        segment.setTemplate("photo");
        segment.setVisualType("realistic_image");
        return segment;
    }

    private static Scene scene() {
        Scene scene = new Scene();
        scene.setSceneNumber(1);
        scene.setSceneTitle("The heart");
        return scene;
    }

    private static JsonObject plan(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void acceptsLabelsSupportedByNarrationAndFormatsPendingCoordinates() {
        SceneSegment segment = segment();
        boolean changed = new VisualDirector(null).apply(segment, scene(), plan("""
            {"visual_type":"realistic_labeled_image",
             "image_prompt":"Photorealistic close view of a human heart on a clean surface with the left ventricle wall and the aorta rising from the top, soft natural side lighting, shallow depth of field",
             "image_requirement":"Heart with visible left ventricle and aorta",
             "labels":["Left ventricle","Aorta"],
             "label_placement":["Left ventricle - thick muscular lower left chamber","Aorta - large vessel rising from the top"],
             "steps":[]}"""));
        assertTrue(changed);
        assertEquals("realistic_labeled_image", segment.getVisualType());
        assertEquals("Left ventricle | thick muscular lower left chamber | COORDINATES_PENDING_APPROVED_IMAGE",
            segment.getLabelPlacements().get(0));
        assertTrue(segment.getAssetQualityNotes().contains("BLOCK_FINAL_RENDER_UNTIL_LABEL_COORDINATES_ARE_VERIFIED"));
        assertTrue(segment.getComfyPrompt().endsWith("No captions."));
    }

    @Test
    void rejectsInventedLabelsButKeepsSpecificPromptAsPlainImage() {
        SceneSegment segment = segment();
        new VisualDirector(null).apply(segment, scene(), plan("""
            {"visual_type":"realistic_labeled_image",
             "image_prompt":"Photorealistic close view of a human heart on a clean surface with the left ventricle wall and the aorta rising from the top, soft natural side lighting",
             "image_requirement":"Heart","labels":["Pancreas","Femur"],
             "label_placement":["Pancreas - organ","Femur - bone"],"steps":[]}"""));
        assertEquals("realistic_image", segment.getVisualType());
        assertTrue(segment.getLabels().isEmpty());
    }

    @Test
    void rejectsPromptUnrelatedToNarrationAndLeavesShotUntouched() {
        SceneSegment segment = segment();
        boolean changed = new VisualDirector(null).apply(segment, scene(), plan("""
            {"visual_type":"realistic_image",
             "image_prompt":"A quiet mountain lake at sunrise with pine trees along the shore and a small wooden boat resting on calm water under pale pink clouds",
             "image_requirement":"Lake","labels":[],"label_placement":[],"steps":[]}"""));
        assertFalse(changed);
        assertEquals("photo", segment.getTemplate());
    }

    @Test
    void stepsMustComeFromNarration() {
        assertTrue(VisualDirector.validSteps(List.of("Heart pumps blood", "Aorta carries blood"), NARRATION));
        assertFalse(VisualDirector.validSteps(List.of("Bake the cake", "Ice the cake"), NARRATION));
        assertFalse(VisualDirector.validSteps(List.of("Only one heart step"), NARRATION));
    }

    @Test
    void splitScreenNeedsCaptionsNamedInNarration() {
        String narration = "The forelimbs of humans, whales and bats share a similar bone structure.";
        assertTrue(VisualDirector.validPanels(List.of(
            "Human arm | photorealistic human arm skeleton in a museum display case under soft light",
            "Whale flipper | photorealistic whale flipper skeleton in a museum display case under soft light"), narration));
        assertFalse(VisualDirector.validPanels(List.of(
            "Volcano | photorealistic volcano erupting at night with glowing lava streams",
            "Glacier | photorealistic glacier meeting the sea under a clear blue sky"), narration));
        assertFalse(VisualDirector.validPanels(List.of("Human arm | short prompt"), narration));
    }

    @Test
    void formulaLinesNeedMathInTheNarrationAndStatedNumbers() {
        String spoken = "The current equals the voltage divided by the resistance, so twelve divided by four is three.";
        assertTrue(VisualDirector.validFormulas(List.of("I = V / R", "I = 12 / 4 = 3"), List.of(), spoken));
        assertFalse(VisualDirector.validFormulas(List.of("I = V / R"), List.of(), "Bees visit many different flowers each day."));
        assertFalse(VisualDirector.validFormulas(List.of("I = 99 / 4"), List.of(), spoken));
        assertFalse(VisualDirector.validFormulas(List.of("The current is the voltage divided by the resistance"), List.of(), spoken));
    }

    @Test
    void graphRowsMustUseNumbersTheNarrationStates() {
        String narration = "Chimpanzees share 98.8 percent and mice share 85 percent of human DNA.";
        assertTrue(VisualDirector.validGraphRows(List.of("Chimpanzee: 98.8%", "Mouse: 85%"), narration));
        assertFalse(VisualDirector.validGraphRows(List.of("Chimpanzee: 98.8%", "Mouse: 70%"), narration));
    }

    @Test
    void functionGraphsAndCircuitsAreValidatedAgainstTheNarration() {
        String math = "The graph of x squared minus five x plus six equals zero crosses the axis at two and three.";
        assertTrue(VisualDirector.validFunction("y = x^2 - 5x + 6", "x range: -1 to 6", math));
        assertFalse(VisualDirector.validFunction("y = x^2 - 5x + 6", "x range: -1 to 6", "A short story about bees."));
        assertFalse(VisualDirector.validFunction("y = System.exit(0)", "x range: -1 to 6", math));
        String circuit = "A twelve volt battery is connected to three resistors in series.";
        assertTrue(VisualDirector.validCircuitRows(List.of("Series circuit | series: battery 12 V; resistor R1; resistor R2; resistor R3"), circuit));
        assertFalse(VisualDirector.validCircuitRows(List.of("Parallel circuit | parallel: battery 12 V; resistor R1"), circuit));
        assertFalse(VisualDirector.validCircuitRows(List.of("Series circuit | series: battery 9 V; resistor R1"), circuit));
        String plating = "To plate a keychain with silver, connect it to the negative terminal of a 6 V battery as the cathode, "
            + "use a silver rod as the anode and dip both in silver nitrate solution.";
        assertTrue(VisualDirector.validCircuitRows(List.of(
            "Silver plating | electrolysis: battery 6 V; anode silver rod; cathode keychain; electrolyte silver nitrate solution"), plating));
        assertFalse(VisualDirector.validCircuitRows(List.of(
            "Silver plating | electrolysis: battery 9 V; anode silver rod; cathode keychain; electrolyte silver nitrate solution"), plating));
        assertFalse(VisualDirector.validCircuitRows(List.of(
            "Gold plating | electrolysis: battery 6 V; anode gold rod; cathode keychain; electrolyte silver nitrate solution"), plating));
        assertFalse(VisualDirector.validCircuitRows(List.of(
            "Silver plating | electrolysis: battery 6 V; anode silver rod; cathode keychain"), plating));
    }
}
