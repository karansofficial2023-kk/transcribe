package com.video.transcribe.scene;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.video.transcribe.llm.OllamaClient;

/**
 * Editorial visual-director pass. For each scene one schema-constrained LLM request proposes, per shot, a
 * specific text-free image prompt and - only when the narration itself supports them - visible-structure labels
 * or an ordered step list. Every proposal is validated deterministically against the approved narration; a
 * rejected proposal leaves the existing shot untouched. There is no topic-specific vocabulary here.
 */
public final class VisualDirector {
    private static final Logger logger = LoggerFactory.getLogger(VisualDirector.class);
    static final String NO_TEXT = "No generated text. No embedded text. No generated labels. No generated arrows. No captions.";
    static final String LABEL_STYLE = "high_contrast_box; dark_text; light_background; thin_colored_border; "
        + "colored_target_dot; 3px_leader_line; sans_serif; 28px_minimum_font; avoid_subject; avoid_title_area; "
        + "avoid_subtitle_area; avoid_logo_area";
    private static final Set<String> STOP = Set.of("the", "and", "for", "with", "that", "this", "from", "into", "are", "was",
        "were", "has", "have", "their", "there", "these", "those", "which", "while", "such", "also", "can", "its", "than");
    private static final int MAX_LABELS = 5;
    private static final String PENDING = "COORDINATES_PENDING_APPROVED_IMAGE";
    private static final String BLOCK = "BLOCK_FINAL_RENDER_UNTIL_LABEL_COORDINATES_ARE_VERIFIED";

    private static final JsonObject SCHEMA = JsonParser.parseString("""
        {"type":"object","properties":{"shots":{"type":"array","items":{"type":"object","properties":{
          "shot_id":{"type":"string"},
          "visual_type":{"type":"string","enum":["realistic_image","realistic_labeled_image","process_steps","split_screen","formula","graph","circuit"]},
          "image_prompt":{"type":"string"},
          "image_requirement":{"type":"string"},
          "labels":{"type":"array","items":{"type":"string"}},
          "label_placement":{"type":"array","items":{"type":"string"}},
          "steps":{"type":"array","items":{"type":"string"}},
          "panels":{"type":"array","items":{"type":"string"}},
          "narration_en":{"type":"string"},
          "formula_lines":{"type":"array","items":{"type":"string"}},
          "explain_steps":{"type":"array","items":{"type":"string"}},
          "graph_rows":{"type":"array","items":{"type":"string"}},
          "graph_note":{"type":"string"},
          "graph_function":{"type":"string"},
          "graph_range":{"type":"string"},
          "circuit_rows":{"type":"array","items":{"type":"string"}},
          "heading":{"type":"string"}
        },"required":["shot_id","visual_type","image_prompt","image_requirement","labels","label_placement","steps","panels","narration_en","formula_lines","explain_steps","graph_rows","graph_note","graph_function","graph_range","circuit_rows","heading"]}}},
        "required":["shots"]}
        """).getAsJsonObject();

    private final OllamaClient ollama;
    private final java.util.function.UnaryOperator<String> promptFinisher;
    private final java.util.function.Consumer<SceneSegment> formulaRouter;

    public VisualDirector(OllamaClient ollama) {
        this(ollama, prompt -> prompt.trim().replaceAll("[.\\s]+$", "") + ". " + NO_TEXT, segment -> { });
    }

    public VisualDirector(OllamaClient ollama, java.util.function.UnaryOperator<String> promptFinisher) {
        this(ollama, promptFinisher, segment -> { });
    }

    /**
     * @param promptFinisher completes a prompt with the production still contract (see SceneStoryboardGenerator)
     * @param formulaRouter  applies the deterministic formula-shot contract (template/tool/media fields) to a segment
     */
    public VisualDirector(OllamaClient ollama, java.util.function.UnaryOperator<String> promptFinisher,
                          java.util.function.Consumer<SceneSegment> formulaRouter) {
        this.ollama = ollama;
        this.promptFinisher = promptFinisher;
        this.formulaRouter = formulaRouter;
    }

    /** Returns the number of shots that were re-planned. */
    public int direct(StoryboardDocument storyboard) {
        if (storyboard == null || storyboard.getScenes() == null) return 0;
        int changed = 0;
        for (Scene scene : storyboard.getScenes()) {
            if (scene.getSegments() == null || scene.getSegments().isEmpty()) continue;
            try {
                changed += directScene(storyboard, scene);
                changed += directElectrolysisCell(storyboard, scene);
            } catch (Exception error) {
                logger.warn("Visual director skipped scene {}: {}", scene.getSceneNumber(), error.getMessage());
            }
        }
        return changed;
    }

    private static final int BATCH = 3;

    private int directScene(StoryboardDocument storyboard, Scene scene) {
        List<SceneSegment> targets = new ArrayList<>();
        for (SceneSegment segment : scene.getSegments()) {
            if (!"title_card".equals(segment.getVisualType())) targets.add(segment);
        }
        int changed = 0;
        for (int from = 0; from < targets.size(); from += BATCH) {
            List<SceneSegment> batch = targets.subList(from, Math.min(targets.size(), from + BATCH));
            try {
                changed += directBatch(storyboard, scene, batch);
            } catch (Exception error) {
                logger.warn("Visual director batch failed in scene {} ({}); retrying shot by shot", scene.getSceneNumber(), error.getMessage());
                for (SceneSegment single : batch) {
                    try {
                        changed += directBatch(storyboard, scene, List.of(single));
                    } catch (Exception again) {
                        logger.warn("Visual director skipped shot {}.{}: {}", scene.getSceneNumber(), single.getSegmentNumber(), again.getMessage());
                    }
                }
            }
        }
        return changed;
    }

    private int directBatch(StoryboardDocument storyboard, Scene scene, List<SceneSegment> batch) throws Exception {
        Map<String, SceneSegment> byId = new LinkedHashMap<>();
        JsonArray shots = new JsonArray();
        for (SceneSegment segment : batch) {
            String id = scene.getSceneNumber() + "." + segment.getSegmentNumber();
            byId.put(id, segment);
            JsonObject shot = new JsonObject();
            shot.addProperty("shot_id", id);
            shot.addProperty("narration", segment.getSentence());
            shots.add(shot);
        }
        JsonObject request = new JsonObject();
        request.addProperty("lesson", storyboard.getTitle());
        request.addProperty("subject", storyboard.getSubject());
        request.addProperty("scene", scene.getSceneTitle());
        request.add("shots", shots);
        String response = ollama.generateStructured(
            systemPrompt() + SubjectProfiles.guidance(storyboard.getSubject()), request.toString(), SCHEMA, 1400 * batch.size() + 600);
        JsonArray planned = JsonParser.parseString(response).getAsJsonObject().getAsJsonArray("shots");
        int changed = 0;
        for (JsonElement element : planned) {
            JsonObject plan = element.getAsJsonObject();
            SceneSegment segment = byId.get(text(plan, "shot_id"));
            if (segment != null && apply(segment, scene, plan)) changed++;
        }
        return changed;
    }

    private static String systemPrompt() {
        return """
            You are the visual director of a photorealistic educational video. For every shot decide how it is shown.
            Use ONLY facts stated in that shot's narration (the scene title may give context). Rules:
            - image_prompt: 40-70 words. Name the concrete subject, its visible action, spatial layout, camera distance and
              angle, natural lighting and colours. Photorealistic. Never request text, letters, labels, arrows, diagrams,
              collages, split screens or watermarks. Never add species, places or objects the narration does not mention.
            - image_requirement: one sentence stating what the viewer must be able to see to understand the narration.
            - visual_type realistic_labeled_image ONLY when the narration names 2-5 distinct, visible physical parts of one
              subject that will be visible in the image; then give labels (the exact names used in the narration) and
              label_placement entries formatted "<label> - <where it is and what it looks like>".
            - visual_type process_steps when (a) the narration describes an ordered mechanism or sequence, OR (b) it states an
              abstract idea, measurement, rate, relationship or shared property that no single photograph can show. Give 2-4
              short key ideas or steps (each at most 6 words) that reuse words from the narration; do not invent content.
              Prefer process_steps over an unfaithful photograph whenever a real photograph of the subject is impossible.
              Narration about operating software (dragging, clicking, tapping, sliders, buttons, quizzes, "in this simulation",
              reading values off a screen) can never be photographed: use process_steps with the key ideas.
            - visual_type split_screen ONLY when the narration compares 2-3 named subjects that must be seen side by side
              (for example different species, structures or apparatus). Give one entry per subject in panels, formatted
              "<caption> | <photorealistic single-subject prompt of 12-40 words>"; every caption must be a subject named in the
              narration. Leave labels, label_placement and steps empty.
            - visual_type formula ONLY when the narration states an equation, law, chemical reaction or a derivation step
              (in symbols or in spoken words such as "V equals I times R"). Put the exact mathematics in formula_lines, one
              line per equation or derivation step in the narration's order, using plain math syntax (x^2, a/b, sqrt(x),
              Cu^{2+} + 2e^- -> Cu(s), V = I R). A formula line contains NO prose. In explain_steps give one short plain-language
              sentence (at most 12 words) per formula line. Use only quantities and numbers the narration states.
            - visual_type graph ONLY when the narration states at least two numeric values to compare. Give graph_rows as
              "<label>: <number><unit>" using exactly the narration's values, and graph_note naming the quantity and unit.
            - visual_type graph may instead plot a function when the narration describes a graph, curve, parabola or line of
              an equation: put the right-hand-side expression in graph_function as "y = <expression in x>" (plain math such as
              x^2 - 5x + 6) and the plotted interval in graph_range as "x range: <low> to <high>". Use only coefficients the
              narration states.
            - visual_type circuit ONLY when the narration describes an electric circuit with a battery and 1-4 loads connected in
              series or in parallel. Give circuit_rows, one per circuit, formatted "<caption> | series: battery 12 V; resistor R1;
              resistor R2" or "<caption> | parallel: ...". Allowed components: battery, resistor, lamp, capacitor, inductor,
              switch, ammeter, voltmeter. A battery value must be one the narration states.
            - visual_type circuit also for an electrolysis or electroplating cell (a battery wired to two electrodes in an
              electrolyte). Give one row "<caption> | electrolysis: battery 6 V; anode <material>; cathode <object>; electrolyte
              <solution>" using exactly those four parts, with every material and solution named in the narration.
            - heading: a short on-screen title for the shot - a noun phrase of 2 to 7 words naming the shot's topic (for example
              "Oxygen released by leaves"), written in the same language as the narration. Never copy the start of the sentence, never
              end on a connecting word, no punctuation at the end.
            - narration_en: if the narration is not English, give an English translation (used only to check your prompts);
              otherwise repeat the narration. image_prompt is always written in English.
            - Otherwise (a concrete physical subject or scene can be photographed) visual_type realistic_image with empty labels, label_placement, steps and panels.
            Return JSON matching the schema, one entry per shot_id.
            """;
    }

    /** Validate one proposal against the narration; returns true when the segment was updated. */
    /** Accepts the director's heading only when it is a short topic title in the narration's own language and not a copied sentence start. */
    static boolean validHeading(String heading, String narration) {
        if (heading == null) return false;
        String h = heading.strip();
        if (h.isEmpty() || h.length() > 60) return false;
        String[] words = h.split("\\s+");
        if (words.length < 1 || words.length > 9) return false;
        if (h.matches(".*[:;,\\-\\u2013\\u2014]$")) return false;
        com.video.transcribe.LanguageSupport.Language language = com.video.transcribe.LanguageSupport.detect(narration);
        if (com.video.transcribe.LanguageSupport.scriptShare(h, language) < 0.6) return false;
        String key = h.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{M}\\p{N}]+", " ").strip();
        String start = narration.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{M}\\p{N}]+", " ").strip();
        return words.length < 4 || !start.startsWith(key);                  // 4+ words equal to the sentence's opening is a copy
    }

    boolean apply(SceneSegment segment, Scene scene, JsonObject plan) {
        String narration = segment.getSentence() == null ? "" : segment.getSentence();
        String proposedHeading = text(plan, "heading");
        if (validHeading(proposedHeading, narration)) {
            segment.setHeading(proposedHeading.strip());
            segment.setHeadingFromDirector(true);
        }
        String prompt = text(plan, "image_prompt");
        String gloss = text(plan, "narration_en");
        String type = text(plan, "visual_type");
        boolean mathType = "formula".equals(type) || "graph".equals(type) || "circuit".equals(type);
        if (!mathType && !validPrompt(prompt, narration + " " + gloss + " " + scene.getSceneTitle())) return false;
        if ("formula".equals(type) && applyFormula(segment, plan, narration + " " + gloss)) return true;
        if ("graph".equals(type) && (applyFunctionGraph(segment, plan, narration + " " + gloss)
                || applyGraph(segment, plan, narration + " " + gloss))) return true;
        if ("circuit".equals(type)) {
            // an electrolysis cell is usually described over several sentences of one scene, so its part names may come from the whole scene
            boolean cell = strings(plan, "circuit_rows").stream().anyMatch(row -> row.toLowerCase(Locale.ROOT).contains("electrolysis"));
            String context = narration + " " + gloss + (cell && scene.getNarration() != null ? " " + scene.getNarration() : "");
            if (applyCircuit(segment, plan, context)) return true;
            logger.info("Circuit proposal rejected for shot {}: {}", segment.getSegmentNumber(), strings(plan, "circuit_rows"));
        }
        if (!validPrompt(prompt, narration + " " + gloss + " " + scene.getSceneTitle())) return false;
        List<String> labels = strings(plan, "labels");
        List<String> placements = strings(plan, "label_placement");
        List<String> steps = strings(plan, "steps");

        List<String> panels = strings(plan, "panels");
        boolean split = "split_screen".equals(type) && validPanels(panels, narration);
        boolean labeled = !split && "realistic_labeled_image".equals(type) && validLabels(labels, placements, narration);
        boolean process = "process_steps".equals(type) && validSteps(steps, narration);

        String requirement = text(plan, "image_requirement");
        segment.setVisualSubject(requirement.length() >= 48 ? requirement : prompt);
        segment.setComfyPrompt(promptFinisher.apply(prompt));
        if (split) {
            segment.setTemplate("split_screen");
            segment.setVisualType("split_screen");
            segment.setColumns(new ArrayList<>(panels));
            segment.setLabels(List.of());
            segment.setLabelPlacements(List.of());
            segment.setSteps(List.of());
            segment.setMotion("camera: subtle_independent_zoom; transition_in: fade; transition_out: crossfade");
        } else if (labeled) {
            segment.setTemplate("labeled_image");
            segment.setVisualType("realistic_labeled_image");
            segment.setLabels(new ArrayList<>(labels));
            List<String> formatted = new ArrayList<>();
            for (int i = 0; i < labels.size(); i++) {
                formatted.add(labels.get(i) + " | " + description(labels.get(i), placements.get(i)) + " | " + PENDING);
            }
            segment.setLabelPlacements(formatted);
            String notes = segment.getAssetQualityNotes() == null ? "" : segment.getAssetQualityNotes();
            if (!notes.contains(BLOCK)) segment.setAssetQualityNotes((notes + " " + BLOCK).trim());
            segment.setLabelStyle(LABEL_STYLE);
            segment.setSteps(List.of());
            segment.setMotion("camera: static; overlay_sequence: arrow_draw_then_label_fade; label_reveal: one_by_one; transition_in: fade; transition_out: crossfade");
        } else if (process) {
            segment.setTemplate("process");
            segment.setVisualType("process_steps");
            segment.setSteps(new ArrayList<>(steps));
            segment.setLabels(List.of());
            segment.setLabelPlacements(List.of());
            segment.setMotion("step_reveal: one_by_one; camera: static; transition_in: fade; transition_out: crossfade");
        } else {
            segment.setTemplate("photo");
            segment.setVisualType("realistic_image");
            segment.setLabels(List.of());
            segment.setLabelPlacements(List.of());
            segment.setSteps(List.of());
            segment.setMotion("camera: slow_zoom_in; transition_in: fade; transition_out: crossfade");
        }
        return true;
    }

    private static final Pattern MATH_SIGNAL = Pattern.compile(
        "(?i)(=|\\u2192|\\u21cc|->|\\b(equals?|equal to|plus|minus|times|multiplied|divided|over|squared|cubed|root|"
            + "proportional|reciprocal|sum of|product of|ratio|formula|equation|law|reaction|yields?|gives?|becomes?|"
            + "discriminant|derivative|integral|logarithm)\\b)");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");
    private static final Map<String, String> NUMBER_WORDS = Map.ofEntries(
        Map.entry("zero", "0"), Map.entry("one", "1"), Map.entry("two", "2"), Map.entry("three", "3"), Map.entry("four", "4"),
        Map.entry("five", "5"), Map.entry("six", "6"), Map.entry("seven", "7"), Map.entry("eight", "8"), Map.entry("nine", "9"),
        Map.entry("ten", "10"), Map.entry("eleven", "11"), Map.entry("twelve", "12"), Map.entry("twenty", "20"),
        Map.entry("hundred", "100"), Map.entry("half", "0.5"));

    /** Numbers the narration states, as numerals or English number words. */
    static Set<String> statedNumbers(String text) {
        Set<String> result = new HashSet<>();
        Matcher digits = NUMBER.matcher(text == null ? "" : text);
        while (digits.find()) result.add(digits.group());
        for (String token : (text == null ? "" : text).toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            if (NUMBER_WORDS.containsKey(token)) result.add(NUMBER_WORDS.get(token));
        }
        return result;
    }

    static boolean validFormulas(List<String> lines, List<String> explain, String narrationContext) {
        if (lines.isEmpty() || lines.size() > 6 || explain.size() > lines.size()) return false;
        if (!MATH_SIGNAL.matcher(narrationContext).find()) return false;          // narration must actually state mathematics
        Set<String> allowed = statedNumbers(narrationContext);
        for (String line : lines) {
            if (line.length() > 120 || StoryboardContractAudit.isProse(line) || StoryboardContractAudit.syntaxProblem(line) != null) return false;
            if (!line.matches("(?s).*[=<>+\\-*/^_\\u2192\\u21cc\\u2248].*|.*->.*")) return false;       // must contain an operator
            Matcher numbers = NUMBER.matcher(line.replaceAll("\\^\\{?\\d+[+-]?}?|_\\{?\\d+}?|[A-Z][a-z]?\\d+", ""));
            while (numbers.find()) {
                if (!allowed.contains(numbers.group()) && !"1".equals(numbers.group()) && !"2".equals(numbers.group())) return false;
            }
        }
        return true;
    }

    private boolean applyFormula(SceneSegment segment, JsonObject plan, String narrationContext) {
        List<String> lines = strings(plan, "formula_lines");
        List<String> explain = strings(plan, "explain_steps");
        if (!validFormulas(lines, explain, narrationContext)) return false;
        segment.setFormulaLines(new ArrayList<>(lines));
        segment.setExplainSteps(new ArrayList<>(explain));
        segment.setSteps(List.of());
        formulaRouter.accept(segment);
        return true;
    }

    static boolean validGraphRows(List<String> rows, String narrationContext) {
        if (rows.size() < 2 || rows.size() > 6) return false;
        Set<String> allowed = statedNumbers(narrationContext);
        for (String row : rows) {
            Matcher matcher = Pattern.compile("^\\s*(.{1,40}?)\\s*[:=]\\s*(-?\\d+(?:\\.\\d+)?)\\s*(%|[A-Za-z/\\u00b0]*)\\s*$").matcher(row);
            if (!matcher.matches() || !allowed.contains(matcher.group(2).replaceFirst("^-", ""))) return false;
        }
        return true;
    }

    private boolean applyGraph(SceneSegment segment, JsonObject plan, String narrationContext) {
        List<String> rows = strings(plan, "graph_rows");
        if (!validGraphRows(rows, narrationContext)) return false;
        segment.setTemplate("graph");
        segment.setVisualType("graph");
        segment.setMediaType("animation");
        segment.setMotionType("local_animation");
        segment.setTool("pillow_opencv");
        segment.setColumns(new ArrayList<>(rows));
        String note = text(plan, "graph_note");
        segment.setExplainSteps(note.isBlank() ? List.of() : List.of(note));
        segment.setLabels(List.of());
        segment.setLabelPlacements(List.of());
        segment.setSteps(List.of());
        segment.setFormulaLines(List.of());
        segment.setMotion("bar_reveal: one_by_one; camera: static; transition_in: fade; transition_out: crossfade");
        segment.setCoverageNotes(appendNote(segment.getCoverageNotes(), "Source: values stated in the approved narration."));
        segment.setComfyPrompt("");
        return true;
    }

    private static final Pattern GRAPH_WORDS = Pattern.compile("(?i)\\b(graph|plot|plotted|curve|parabola|straight line|slope|function)\\b");
    private static final Pattern SAFE_EXPRESSION = Pattern.compile("^[0-9xX+\\-*/^().\\s]+(?:(?:sqrt|sin|cos|tan|ln|log|exp)\\([0-9xX+\\-*/^().\\s]+\\)[0-9xX+\\-*/^().\\s]*)*$");

    static boolean validFunction(String function, String range, String narrationContext) {
        if (function == null || !function.toLowerCase(Locale.ROOT).startsWith("y")) return false;
        int eq = function.indexOf('=');
        if (eq < 0) return false;
        String rhs = function.substring(eq + 1).trim();
        if (rhs.isEmpty() || rhs.length() > 60 || !SAFE_EXPRESSION.matcher(rhs).matches() || !rhs.toLowerCase(Locale.ROOT).contains("x")) return false;
        if (!GRAPH_WORDS.matcher(narrationContext).find() || !MATH_SIGNAL.matcher(narrationContext).find()) return false;
        Set<String> allowed = statedNumbers(narrationContext);
        Matcher numbers = NUMBER.matcher(rhs.replaceAll("\\^\\d+", ""));
        while (numbers.find()) {
            if (!allowed.contains(numbers.group()) && !"1".equals(numbers.group()) && !"2".equals(numbers.group())) return false;
        }
        return range != null && range.matches("(?i)^\\s*x\\s*(range)?\\s*[:=]\\s*-?\\d+(\\.\\d+)?\\s*(to|\\.\\.|,)\\s*-?\\d+(\\.\\d+)?\\s*$");
    }

    private boolean applyFunctionGraph(SceneSegment segment, JsonObject plan, String narrationContext) {
        String function = text(plan, "graph_function");
        String range = text(plan, "graph_range");
        if (!validFunction(function, range, narrationContext)) return false;
        segment.setTemplate("graph");
        segment.setVisualType("graph");
        segment.setMediaType("animation");
        segment.setMotionType("local_animation");
        segment.setTool("pillow_opencv");
        segment.setColumns(new ArrayList<>(List.of(function, range)));
        segment.setExplainSteps(List.of());
        segment.setLabels(List.of());
        segment.setLabelPlacements(List.of());
        segment.setSteps(List.of());
        segment.setFormulaLines(List.of());
        segment.setMotion("curve_draw: left_to_right; camera: static; transition_in: fade; transition_out: crossfade");
        segment.setComfyPrompt("");
        return true;
    }

    private static final Set<String> CIRCUIT_PARTS = Set.of("battery", "cell", "resistor", "lamp", "bulb", "capacitor", "inductor",
        "switch", "ammeter", "voltmeter", "diode", "led");

    static boolean validCircuitRows(List<String> rows, String narrationContext) {
        if (rows.isEmpty() || rows.size() > 3) return false;
        String lower = narrationContext.toLowerCase(Locale.ROOT);
        Set<String> allowed = statedNumbers(narrationContext);
        for (String row : rows) {
            Matcher match = Pattern.compile("(?i)^(.{1,60}?)\\s*\\|\\s*(series|parallel|electrolysis)\\s*:\\s*(.+)$").matcher(row.trim());
            if (!match.matches()) return false;
            if (match.group(2).equalsIgnoreCase("electrolysis")) {
                if (!validElectrolysisRow(match.group(3), lower, allowed)) return false;
                continue;
            }
            if (!lower.contains(match.group(2).toLowerCase(Locale.ROOT))) return false;
            int loads = 0;
            boolean battery = false;
            for (String item : match.group(3).split("[;,]")) {
                String trimmed = item.trim();
                if (trimmed.isEmpty()) continue;
                String[] parts = trimmed.split("\\s+", 2);
                String kind = parts[0].toLowerCase(Locale.ROOT);
                if (!CIRCUIT_PARTS.contains(kind)) return false;
                if (kind.equals("battery") || kind.equals("cell")) {
                    battery = true;
                    Matcher value = NUMBER.matcher(parts.length > 1 ? parts[1] : "");
                    while (value.find()) {
                        if (!allowed.contains(value.group())) return false;      // a battery value must be stated
                    }
                } else {
                    loads++;
                }
            }
            if (!battery || loads < 1 || loads > 4) return false;
        }
        return true;
    }

    /** "battery 6 V; anode silver rod; cathode keychain; electrolyte silver nitrate solution": every part present, every name from the narration. */
    private static boolean validElectrolysisRow(String spec, String narrationLower, Set<String> allowedNumbers) {
        Set<String> seen = new java.util.HashSet<>();
        for (String item : spec.split("[;,]")) {
            String trimmed = item.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split("\\s+", 2);
            String kind = parts[0].toLowerCase(Locale.ROOT);
            if (!Set.of("battery", "anode", "cathode", "electrolyte").contains(kind) || !seen.add(kind)) return false;
            String name = parts.length > 1 ? parts[1].trim() : "";
            if (kind.equals("battery")) {
                Matcher value = NUMBER.matcher(name);
                while (value.find()) {
                    if (!allowedNumbers.contains(value.group())) return false;      // a battery value must be stated
                }
            } else {
                if (name.isEmpty()) return false;
                for (String word : name.toLowerCase(Locale.ROOT).split("\\s+")) {
                    if (word.length() > 3 && !narrationLower.contains(word)) return false;   // names come from the narration
                }
            }
        }
        return seen.size() == 4;
    }

    private static final Pattern NOT_A_MATERIAL = Pattern.compile("(?i)\\b(terminal|connected|connects|positive|negative|wire)\\b");

    private static final JsonObject CELL_SCHEMA = JsonParser.parseString("""
        {"type":"object","properties":{"anode":{"type":"string"},"cathode":{"type":"string"},"electrolyte":{"type":"string"}},
         "required":["anode","cathode","electrolyte"]}""").getAsJsonObject();

    /**
     * An electrolysis / electroplating cell is described over several sentences, so the model rarely proposes it shot by shot. When a scene
     * names a battery, an anode, a cathode and an electrolyte, the part names are extracted once (and must be words of the narration)
     * and the shot that mentions most of them becomes the deterministic cell diagram.
     */
    int directElectrolysisCell(StoryboardDocument storyboard, Scene scene) {
        if (scene.getSegments() == null) return 0;
        StringBuilder text = new StringBuilder();
        for (SceneSegment segment : scene.getSegments()) {
            if ("circuit".equals(segment.getVisualType())) return 0;
            if (segment.getSentence() != null) text.append(segment.getSentence()).append(' ');
        }
        String sceneText = text.toString();
        String lower = sceneText.toLowerCase(Locale.ROOT);
        if (!(lower.contains("battery") || lower.contains("power supply")) || !lower.contains("anode")
                || !lower.contains("cathode") || !lower.contains("electrolyte")) return 0;
        SceneSegment best = null;
        int bestHits = 1;
        for (SceneSegment segment : scene.getSegments()) {
            String type = segment.getVisualType();
            if (!("realistic_image".equals(type) || "realistic_labeled_image".equals(type) || "short_motion_clip".equals(type))) continue;
            String sentence = segment.getSentence() == null ? "" : segment.getSentence().toLowerCase(Locale.ROOT);
            int hits = 0;
            for (String term : List.of("battery", "anode", "cathode", "electrolyte", "electrode", "circuit")) {
                if (sentence.contains(term)) hits++;
            }
            if (hits > bestHits) {
                best = segment;
                bestHits = hits;
            }
        }
        if (best == null) return 0;
        StringBuilder lesson = new StringBuilder();
        for (Scene other : storyboard.getScenes()) {
            if (other.getSegments() == null) continue;
            for (SceneSegment segment : other.getSegments()) {
                if (segment.getSentence() != null) lesson.append(segment.getSentence()).append(' ');
            }
        }
        String lessonText = lesson.toString();
        try {
            JsonObject parts = JsonParser.parseString(ollama.generateStructured(
                "Name the parts of the electrolysis or electroplating cell described in the lesson. Copy words from the lesson only: "
                + "anode = the MATERIAL of the anode (for example a metal rod), cathode = the OBJECT or material at the cathode, "
                + "electrolyte = the SOLUTION. Each is a short noun phrase of 1-4 words naming a material or object, never a description "
                + "of terminals, wires or connections.", lessonText, CELL_SCHEMA, 300))
                .getAsJsonObject();
            String spec = "battery; anode " + text(parts, "anode").trim() + "; cathode " + text(parts, "cathode").trim()
                + "; electrolyte " + text(parts, "electrolyte").trim();
            String title = scene.getSceneTitle() == null || scene.getSceneTitle().length() > 50 ? "Electrolysis cell" : scene.getSceneTitle();
            List<String> rows = List.of(title + " | electrolysis: " + spec);
            if (!validCircuitRows(rows, lessonText) || NOT_A_MATERIAL.matcher(spec).find()) {
                logger.info("Electrolysis cell for scene {} rejected: {}", scene.getSceneNumber(), rows);
                return 0;
            }
            applyCircuitRows(best, rows);
            logger.info("Scene {}: shot {} drawn as an electrolysis cell: {}", scene.getSceneNumber(), best.getSegmentNumber(), rows.get(0));
            return 1;
        } catch (Exception error) {
            logger.warn("Electrolysis cell extraction failed in scene {}: {}", scene.getSceneNumber(), error.getMessage());
            return 0;
        }
    }

    private boolean applyCircuit(SceneSegment segment, JsonObject plan, String narrationContext) {
        List<String> rows = strings(plan, "circuit_rows");
        if (!validCircuitRows(rows, narrationContext)) return false;
        applyCircuitRows(segment, rows);
        return true;
    }

    private void applyCircuitRows(SceneSegment segment, List<String> rows) {
        segment.setTemplate("circuit");
        segment.setVisualType("circuit");
        segment.setMediaType("animation");
        segment.setMotionType("local_animation");
        segment.setTool("pillow_opencv");
        segment.setColumns(new ArrayList<>(rows));
        segment.setLabels(List.of());
        segment.setLabelPlacements(List.of());
        segment.setSteps(List.of());
        segment.setFormulaLines(List.of());
        segment.setExplainSteps(List.of());
        segment.setMotion("panel_reveal: left_to_right; camera: static; transition_in: fade; transition_out: crossfade");
        segment.setComfyPrompt("");
        segment.setAssetQualityNotes("Deterministic schematic drawn from the narration; no photograph is generated.");
        segment.setCoverageNotes("Source: transcript/paraphrase; deterministic circuit diagram built from terms stated in the narration.");
    }

    private static String appendNote(String existing, String note) {
        return existing == null || existing.isBlank() ? note : existing + " " + note;
    }

    static String description(String label, String placement) {
        String value = placement.trim();
        if (value.toLowerCase(Locale.ROOT).startsWith(label.toLowerCase(Locale.ROOT))) {
            value = value.substring(label.length()).replaceFirst("^\\s*[-:|]\\s*", "");
        }
        return value.replaceAll("\\s*\\|.*$", "").trim();
    }

    static boolean validPrompt(String prompt, String narrationContext) {
        if (prompt == null) return false;
        String lower = prompt.toLowerCase(Locale.ROOT);
        if (prompt.trim().split("\\s+").length < 14) return false;
        if (lower.matches("(?s).*\\b(label|labels|labeled|caption|arrow|arrows|diagram|infographic|collage|watermark|typography)\\b.*")
            && !lower.contains("no ")) return false;
        return overlap(prompt, narrationContext) >= 1;
    }

    static boolean validLabels(List<String> labels, List<String> placements, String narration) {
        if (labels.size() < 2 || labels.size() > MAX_LABELS || placements.size() != labels.size()) return false;
        String lowerNarration = narration.toLowerCase(Locale.ROOT);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < labels.size(); i++) {
            String label = labels.get(i).trim();
            if (label.isEmpty() || label.length() > 40 || !seen.add(label.toLowerCase(Locale.ROOT))) return false;
            if (StoryboardContractAudit.isAbstractLabel(label)) return false;      // forces, processes, time, ... are not visible targets
            if (!lowerNarration.contains(label.toLowerCase(Locale.ROOT))
                && overlap(label, narration) == 0) return false;          // label must come from the narration
            String placement = placements.get(i).trim();
            if (!placement.toLowerCase(Locale.ROOT).startsWith(label.toLowerCase(Locale.ROOT))) return false;
            if (!placement.matches("(?s)^.{1,80}?\\s*[-:|]\\s*\\S.*")) return false;
        }
        return true;
    }

    static boolean validPanels(List<String> panels, String narration) {
        if (panels.size() < 2 || panels.size() > 3) return false;
        for (String panel : panels) {
            int bar = panel.indexOf('|');
            if (bar < 1) return false;
            String caption = panel.substring(0, bar).trim();
            String prompt = panel.substring(bar + 1).trim();
            if (caption.isEmpty() || caption.length() > 40 || overlap(caption, narration) == 0) return false;
            if (prompt.split("\\s+").length < 8) return false;
            if (prompt.toLowerCase(Locale.ROOT).matches("(?s).*\\b(label|labels|caption|arrow|arrows|diagram|infographic|collage|watermark)\\b.*")) return false;
        }
        return true;
    }

    static boolean validSteps(List<String> steps, String narration) {
        if (steps.size() < 2 || steps.size() > 4) return false;
        for (String step : steps) {
            if (step.isBlank() || step.split("\\s+").length > 8 || overlap(step, narration) == 0) return false;
        }
        return true;
    }

    private static int overlap(String a, String b) {
        Set<String> left = words(a);
        left.retainAll(words(b));
        return left.size();
    }

    private static Set<String> words(String value) {
        Set<String> result = new HashSet<>();
        for (String token : (value == null ? "" : value).toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{M}\\p{N}]+")) {
            String stem = token.length() > 5 ? token.substring(0, 5) : token;
            if (token.length() >= 4 && !STOP.contains(token)) result.add(stem);
        }
        return result;
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString().trim();
    }

    private static List<String> strings(JsonObject object, String key) {
        List<String> result = new ArrayList<>();
        JsonElement value = object.get(key);
        if (value != null && value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) {
                if (!item.isJsonNull() && !item.getAsString().isBlank()) result.add(item.getAsString().trim());
            }
        }
        return result;
    }
}
