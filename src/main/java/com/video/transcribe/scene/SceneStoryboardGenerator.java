package com.video.transcribe.scene;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.video.transcribe.llm.OllamaClient;

/**
 * Generates structured scene storyboards from paraphrased text.
 * Creates scenes with narration, visual animations, and image recommendations.
 */
public class SceneStoryboardGenerator {
    
    private static final Logger logger = LoggerFactory.getLogger(SceneStoryboardGenerator.class);
    private final OllamaClient ollama;
    private final boolean animationEnabled;
    private final Gson gson = new Gson();
    private static final JsonObject SCENES_SCHEMA = JsonParser.parseString("""
        {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "sceneNumber": {
                "type": "integer"
              },
              "sceneTitle": {
                "type": "string"
              },
              "narration": {
                "type": "string"
              }
            },
            "required": [
              "sceneNumber",
              "sceneTitle",
              "narration"
            ],
            "additionalProperties": false
          }
        }
        """).getAsJsonObject();

    private static final JsonObject SEGMENTS_SCHEMA = JsonParser.parseString("""
        {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "segmentNumber": {
                "type": "integer"
              },
              "sentence": {
                "type": "string"
              },
              "mediaType": {
                "type": "string",
                "enum": [
                  "photo",
                  "diagram",
                  "animation",
                  "photo_with_labels",
                  "animation_with_labels",
                  "wan_video"
                ]
              },
              "motionType": {
                "type": "string",
                "enum": [
                  "wan_video",
                  "local_animation",
                  "static_image"
                ]
              },
              "estimatedNarrationSeconds": {
                "type": "number"
              },
              "recommendedClipSeconds": {
                "type": "number"
              },
              "timingNotes": {
                "type": "string"
              },
              "visualAnimation": {
                "type": "string"
              },
              "localAnimation": {
                "type": "string"
              },
              "labels": {
                "type": "array",
                "items": {
                  "type": "string"
                }
              },
              "imageRecommendations": {
                "type": "array",
                "items": {
                  "type": "string"
                }
              },
              "comfyPrompt": {
                "type": "string"
              },
              "coverageNotes": {
                "type": "string"
              },
              "shot": {
                "type": "object",
                "properties": {
                  "template": {
                    "type": "string",
                    "enum": [
                      "video"
                    ]
                  },
                  "heading": {
                    "type": "string"
                  },
                  "prompt": {
                    "type": "string"
                  },
                  "negativePrompt": {
                    "type": "string"
                  },
                  "durationSeconds": {
                    "type": "number"
                  }
                },
                "required": [
                  "template",
                  "heading",
                  "prompt",
                  "negativePrompt",
                  "durationSeconds"
                ],
                "additionalProperties": false
              },
              "ltxShot": {
                "type": "object",
                "properties": {
                  "template": {
                    "type": "string",
                    "enum": [
                      "video"
                    ]
                  },
                  "heading": {
                    "type": "string"
                  },
                  "prompt": {
                    "type": "string"
                  },
                  "negativePrompt": {
                    "type": "string"
                  },
                  "durationSeconds": {
                    "type": "number"
                  }
                },
                "required": [
                  "template",
                  "heading",
                  "prompt",
                  "negativePrompt",
                  "durationSeconds"
                ],
                "additionalProperties": false
              }
            },
            "required": [
              "segmentNumber",
              "sentence",
              "mediaType",
              "motionType",
              "estimatedNarrationSeconds",
              "recommendedClipSeconds",
              "timingNotes",
              "visualAnimation",
              "localAnimation",
              "labels",
              "imageRecommendations",
              "comfyPrompt",
              "coverageNotes"
            ],
            "additionalProperties": false
          }
        }
        """).getAsJsonObject();


    
    public SceneStoryboardGenerator(OllamaClient ollama) {
        this(ollama, true);
    }

    public SceneStoryboardGenerator(OllamaClient ollama, boolean animationEnabled) {
        this.ollama = ollama;
        this.animationEnabled = animationEnabled;
    }
    
    /**
     * Generate complete storyboard from paraphrased text
     */
    public StoryboardDocument generateStoryboard(String paraphrasedText) throws IOException {
        logger.info("Generating scene storyboard from text ({} chars)...", paraphrasedText.length());
        String languageInstruction = buildLanguageInstruction(paraphrasedText);
        
        // Step 1: Split into logical scenes
        List<Scene> scenes = splitIntoScenes(paraphrasedText, languageInstruction);
        
        // Step 2: For each scene, generate segments with visuals and images
        for (Scene scene : scenes) {
            enrichSceneWithSegments(scene, languageInstruction);
        }
        
        StoryboardDocument doc = new StoryboardDocument();
        doc.setTitle("Storyboard: " + extractTitle(paraphrasedText));
        doc.setSourceText(paraphrasedText);
        doc.setScenes(scenes);
        doc.setGeneratedAt(java.time.Instant.now().toString());
        
        logger.info("Storyboard generated with {} scenes", scenes.size());
        return doc;
    }
    
    /**
     * Split text into logical scenes using LLM
     */
    private List<Scene> splitIntoScenes(String text, String languageInstruction) throws IOException {
        String prompt = """
            Split the following educational video transcript into logical scenes.
            Think like a subject-matter expert and curriculum reviewer for this
            specific topic and subject. Keep explanations curriculum-safe and
            suitable for educational video production.
            Each scene should cover a distinct topic or sub-topic.
            Create 3-8 scenes depending on content length.
            %s
            
            TEXT:
            %s
            
            Respond ONLY with a JSON array matching the provided schema.
            Do not include markdown, explanations, examples, or fields outside the schema.
            
            Rules:
            - Each scene should have complete, flowing narration
            - Preserve ALL facts and details from original
            - Scene titles should be descriptive
            - Do not introduce words from any language that is not present in the transcript
            - Do not merge unrelated topics
            """.formatted(languageInstruction, text);
        
        String response = ollama.generateStructured(
            "You are an expert educational video script writer and storyboard creator.",
            prompt, SCENES_SCHEMA
        );
        
        return parseScenes(response);
    }
    
    /**
     * Enrich scene with sentence-level segments, visuals, and image recommendations
     */
    private void enrichSceneWithSegments(Scene scene, String languageInstruction) throws IOException {
        String prompt = """
            Break down the following scene narration into sentence-level segments.
            Think like a subject-matter expert and curriculum reviewer for this
            specific topic and subject. Preserve the narration/script exactly;
            improve only storyboard planning, visual choices, labels, coverage
            notes, and ComfyUI Wan/LTX prompts.
            %s
            %s
            For each sentence, provide:
            1. The exact sentence text
            2. mediaType: "photo", "diagram", "animation", "photo_with_labels", "animation_with_labels", or "wan_video"
            3. motionType: "wan_video", "local_animation", or "static_image"
            4. estimatedNarrationSeconds based on sentence length at natural voiceover speed
            5. recommendedClipSeconds for the visual, matching or exceeding narration timing
            6. timingNotes explaining loop, hold, cutaway, or extension strategy
            7. Visual/Animation description (what should be shown on screen)
            8. Local animation instructions for teaching clarity, such as arrows, highlights, zooms, labels, diagrams, step reveals, or comparison panels
            9. Labels to place on images/diagrams when useful
            10. Image recommendations (2 specific image descriptions for stock photo/illustration search)
            11. ComfyUI prompt for the selected media type
            12. Coverage notes explaining which curriculum facts from the sentence are covered visually
            13. A shot object for Wan/ComfyUI video generation only when the scene needs natural cinematic motion
            14. An ltxShot object for LTX video generation when the scene needs generated video motion
            
            SCENE TITLE: %s
            NARRATION:
            %s
            
            Respond ONLY with a JSON array matching the provided schema.
            Do not include markdown, explanations, examples, or fields outside the schema.
            
            Rules:
            - Split by natural sentence boundaries
            - Do not change the narration/script while creating storyboard rows; the sentence must come from the narration
            - Think as the correct SME for the detected subject and topic
            - Use curriculum-safe explanations and standard subject terminology
            - Never introduce off-topic animals, plants, tools, reactions, locations, or examples from previous videos or prompt examples.
            - If a visual detail is uncertain, avoid inventing it; use a neutral diagram, label, or coverage note instead
            - Visuals should be specific and actionable for video editors
            - Images should be descriptive enough for stock photo searches
            - Use educational documentary style visuals
            - Estimate timing carefully: short sentences may be 3-5 seconds, medium sentences 6-8 seconds, long sentences 9-12 seconds.
            - recommendedClipSeconds must be at least estimatedNarrationSeconds.
            - If video generation normally outputs short clips, then for narration longer than the clip length specify continuation clips, seamless loop motion, slow camera movement, or cutaways in timingNotes.
            - Use generated video where natural motion improves learning: pollinators moving, wind/water motion, liquids flowing, machine/process movement, lab action, real-world cause-effect motion.
            - Do not use generated video for concepts better taught with clean diagrams, labels, equations, maps, grammar steps, comparisons, or anatomy/process charts.
            - Choose mediaType for learning value, not visual spectacle:
              photo = real-world context or object recognition
              diagram = anatomy, process structure, comparison, classification, or abstract ideas
              animation = step-by-step movement, sequence, timeline, flow, or transformation
              photo_with_labels = real-world photo plus a few labels for parts/objects
              animation_with_labels = process explanation with arrows, highlights, labels, or step reveals
              wan_video = natural cinematic motion only, such as pollinator movement, wind, water, animal behavior, or real-world motion
            - Use the selected language/script for sentence, visualAnimation, localAnimation, labels, and imageRecommendations
            - Do not use Tamil, Hindi, or any other Indic-language words unless the scene narration itself uses that language
            - Do not change or rewrite narration. The sentence field must remain faithful to the scene narration.
            - Do not add new curriculum facts, but do visually cover every curriculum fact already present in the sentence.
            - Include all named plants, processes, agents, plant parts, and comparisons from the sentence in the visualAnimation, localAnimation, labels, imageRecommendations, or coverageNotes.
            - Science accuracy guard: do not invent ions, reactions, cell types, forces, organ names, dates, units, or mechanisms that are not supported by the narration or standard curriculum.
            - If the topic is electroplating, use electrolytic cell terminology, not galvanic cell terminology.
            - If the topic mentions silver nitrate, represent it as Ag+ and NO3- in solution; do not add chloride ions unless the narration explicitly discusses chloride or silver chloride.
            - If explaining metal deposition in electroplating, use electron gain at the cathode and the appropriate half-equation. Do not use vague shell/empty-space explanations.
            - If comparing deposition of gold, silver, copper, or other metals, avoid saying one always deposits more. Refer to Faraday's law: deposited mass depends on current, time, molar mass, and electrons transferred.
            - If a sentence contains a likely anatomy-risk phrase such as "anther curls", preserve the sentence text, but keep the visual neutral: use a labeled diagram and pollen-transfer arrows instead of instructing physical curling/anther motion.
            - Use generated video shots only for natural/cinematic motion that is explicitly supported by the narration
            - Use local_animation for teaching clarity: arrows, labels, highlighted parts, cutaway diagrams, timelines, maps, math/grammar steps, charts, comparisons, or process diagrams
            - Use static_image for a still photo or illustration with optional labels
            - For diagram/photo/animation media, write comfyPrompt as an image prompt or animation design prompt; for wan_video, write comfyPrompt to match shot.prompt.
            - If motionType is not "wan_video", omit both shot and ltxShot fields.
            - Never add shot or ltxShot objects for static_image or local_animation rows.
            - For every wan_video row, shot.durationSeconds and ltxShot.durationSeconds must equal recommendedClipSeconds.
            - Write shot.prompt for Wan style: cinematic natural motion, stable subject anatomy, smooth camera, no text artifacts.
            - Write ltxShot.prompt for LTX style: one continuous realistic action, clear start-to-end motion, no abrupt final-frame freeze, simple camera path, enough visual detail for the full narration duration.
            - Labels should be short, screen-ready text. Return [] when labels are not useful.
            """.formatted(languageInstruction, buildAnimationModeInstruction(), scene.getSceneTitle(), scene.getNarration());
        
        String response = ollama.generateStructured(
            "You are a professional video storyboard artist and educational content designer.",
            prompt, SEGMENTS_SCHEMA
        );
        
        List<SceneSegment> segments = parseSegments(response);
        scene.setSegments(segments);
    }
    
    private List<Scene> parseScenes(String jsonResponse) {
        List<Scene> scenes = new ArrayList<>();
        try {
            JsonArray arr = JsonParser.parseString(jsonResponse).getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                JsonObject obj = arr.get(i).getAsJsonObject();
                Scene scene = new Scene();
                scene.setSceneNumber(obj.get("sceneNumber").getAsInt());
                scene.setSceneTitle(obj.get("sceneTitle").getAsString());
                scene.setNarration(obj.get("narration").getAsString());
                scenes.add(scene);
            }
        } catch (Exception e) {
            logger.error("Failed to parse scenes, creating fallback single scene", e);
            Scene fallback = new Scene();
            fallback.setSceneNumber(1);
            fallback.setSceneTitle("Main Content");
            fallback.setNarration(jsonResponse);
            scenes.add(fallback);
        }
        return scenes;
    }
    
    private List<SceneSegment> parseSegments(String jsonResponse) {
        List<SceneSegment> segments = new ArrayList<>();
        try {
            JsonArray arr = JsonParser.parseString(jsonResponse).getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                JsonObject obj = arr.get(i).getAsJsonObject();
                SceneSegment seg = new SceneSegment();
                seg.setSegmentNumber(obj.get("segmentNumber").getAsInt());
                seg.setSentence(obj.get("sentence").getAsString());
                seg.setMediaType(getStringOrDefault(obj, "mediaType", "animation_with_labels"));
                seg.setMotionType(getStringOrDefault(obj, "motionType", "local_animation"));
                seg.setEstimatedNarrationSeconds(getDoubleOrDefault(obj, "estimatedNarrationSeconds", estimateNarrationSeconds(seg.getSentence())));
                seg.setRecommendedClipSeconds(getDoubleOrDefault(obj, "recommendedClipSeconds", seg.getEstimatedNarrationSeconds()));
                seg.setTimingNotes(getStringOrDefault(obj, "timingNotes", ""));
                seg.setVisualAnimation(obj.get("visualAnimation").getAsString());
                seg.setLocalAnimation(getStringOrDefault(obj, "localAnimation", ""));

                List<String> labels = new ArrayList<>();
                JsonArray labelArr = obj.getAsJsonArray("labels");
                if (labelArr != null) {
                    labelArr.forEach(e -> labels.add(e.getAsString()));
                }
                seg.setLabels(labels);
                
                List<String> images = new ArrayList<>();
                JsonArray imgArr = obj.getAsJsonArray("imageRecommendations");
                if (imgArr != null) {
                    imgArr.forEach(e -> images.add(e.getAsString()));
                }
                seg.setImageRecommendations(images);
                seg.setComfyPrompt(getStringOrDefault(obj, "comfyPrompt", ""));
                seg.setCoverageNotes(getStringOrDefault(obj, "coverageNotes", ""));
                if (obj.has("shot") && obj.get("shot").isJsonObject()) {
                    seg.setShot(parseShot(obj.getAsJsonObject("shot"), seg));
                }
                if (obj.has("ltxShot") && obj.get("ltxShot").isJsonObject()) {
                    seg.setLtxShot(parseShot(obj.getAsJsonObject("ltxShot"), seg));
                }
                enforceStoryboardQuality(seg);
                segments.add(seg);
            }
        } catch (Exception e) {
            logger.error("Failed to parse segments", e);
            SceneSegment fallback = new SceneSegment();
            fallback.setSegmentNumber(1);
            fallback.setSentence("Full scene narration");
            fallback.setMediaType("animation_with_labels");
            fallback.setMotionType("local_animation");
            fallback.setEstimatedNarrationSeconds(estimateNarrationSeconds(fallback.getSentence()));
            fallback.setRecommendedClipSeconds(fallback.getEstimatedNarrationSeconds());
            fallback.setTimingNotes("Hold the educational diagram for the full narration duration with slow zoom and staged labels.");
            fallback.setVisualAnimation("Animation: Educational content display");
            fallback.setLocalAnimation("Use clear labels, highlights, and step-by-step reveals to explain the concept");
            fallback.setLabels(List.of());
            fallback.setImageRecommendations(List.of("Image 1: Educational illustration", "Image 2: Related concept diagram"));
            fallback.setComfyPrompt("Clean educational diagram with labeled parts, accurate subject matter, simple background, high readability");
            fallback.setCoverageNotes("Fallback row covers the full scene narration with local teaching animation.");
            enforceStoryboardQuality(fallback);
            segments.add(fallback);
        }
        return segments;
    }

    private Shot parseShot(JsonObject shotObj, SceneSegment segment) {
        Shot shot = new Shot();
        shot.setTemplate(getStringOrDefault(shotObj, "template", "video"));
        shot.setHeading(getStringOrDefault(shotObj, "heading", segment.getVisualAnimation()));
        shot.setPrompt(getStringOrDefault(shotObj, "prompt", segment.getComfyPrompt()));
        shot.setNegativePrompt(getStringOrDefault(shotObj, "negativePrompt", defaultNegativePrompt()));
        shot.setDurationSeconds(getDoubleOrDefault(shotObj, "durationSeconds", segment.getRecommendedClipSeconds()));
        return shot;
    }

    private void enforceStoryboardQuality(SceneSegment segment) {
        String motionType = segment.getMotionType();
        if (motionType == null || motionType.isBlank()) {
            motionType = "local_animation";
            segment.setMotionType(motionType);
        }

        if (!"wan_video".equals(motionType)) {
            segment.setShot(null);
            segment.setLtxShot(null);
        }
        if (segment.getMediaType() == null || segment.getMediaType().isBlank()) {
            segment.setMediaType("wan_video".equals(motionType) ? "wan_video" : inferMediaType(segment));
        }
        if (!"wan_video".equals(segment.getMediaType())) {
            segment.setShot(null);
            segment.setLtxShot(null);
        }
        enforceAnimationMode(segment);
        enforceTiming(segment);

        if (containsIgnoreCase(segment.getSentence(), "anther curls")
                || containsIgnoreCase(segment.getVisualAnimation(), "anther curls")
                || containsIgnoreCase(segment.getLocalAnimation(), "anther curls")
                || containsIgnoreCase(segment.getLocalAnimation(), "movement of the anther")) {
            segment.setMotionType("local_animation");
            segment.setMediaType("animation_with_labels");
            segment.setShot(null);
            segment.setLtxShot(null);
            segment.setVisualAnimation("Labeled close-up diagram of the flower reproductive parts showing pollen, anther, and sticky stigma.");
            segment.setLocalAnimation("Preserve the narration sentence, but avoid animating the anther physically curling. Use arrows to show pollen transfer toward the stigma and labels for anther, pollen, and stigma.");
            segment.setLabels(mergeLabels(segment.getLabels(), List.of("Anther", "Pollen", "Stigma")));
            segment.setComfyPrompt("Accurate educational botanical diagram of flower reproductive parts, anther, pollen grains, sticky stigma, arrows showing pollen transfer, clean labels, white background, textbook style");
            segment.setCoverageNotes("Fact guard: narration is preserved, but the visual avoids depicting anther curling as literal motion; it covers anther, pollen, and stigma with a labeled pollen-transfer diagram.");
        }

        if (segment.getComfyPrompt() == null || segment.getComfyPrompt().isBlank()) {
            segment.setComfyPrompt(buildDefaultComfyPrompt(segment));
        }

        enforceTopicRelevance(segment);
        enforceElectroplatingScience(segment);

        if (segment.getCoverageNotes() == null || segment.getCoverageNotes().isBlank()) {
            segment.setCoverageNotes("Covers the narration sentence with matching visuals, labels, and image recommendations.");
        }
    }

    private void enforceTopicRelevance(SceneSegment segment) {
        String sentence = segment.getSentence() != null ? segment.getSentence() : "";
        String visualFields = joinForFactCheck(
            segment.getVisualAnimation(),
            segment.getLocalAnimation(),
            segment.getComfyPrompt(),
            segment.getCoverageNotes(),
            segment.getShot() != null ? segment.getShot().getHeading() : null,
            segment.getShot() != null ? segment.getShot().getPrompt() : null,
            segment.getLtxShot() != null ? segment.getLtxShot().getHeading() : null,
            segment.getLtxShot() != null ? segment.getLtxShot().getPrompt() : null
        );

        boolean narrationMentionsPollination = containsAnyIgnoreCase(sentence,
            "bee", "bees", "flower", "flowers", "pollen", "pollination", "pollinator", "orchid", "stigma", "anther");
        boolean visualMentionsPollination = containsAnyIgnoreCase(visualFields,
            "bee", "bees", "flower", "flowers", "pollen", "pollination", "pollinator", "orchid", "stigma", "anther");

        if (!narrationMentionsPollination && visualMentionsPollination) {
            segment.setMediaType(animationEnabled ? "animation_with_labels" : "photo_with_labels");
            segment.setMotionType(animationEnabled ? "local_animation" : "static_image");
            segment.setShot(null);
            segment.setLtxShot(null);
            segment.setVisualAnimation("Topic-specific educational visual directly matching the sentence: " + sentence);
            segment.setLocalAnimation(animationEnabled
                ? "Use labels, arrows, or highlights only for terms present in the sentence. Do not use bee, flower, pollen, or pollination imagery."
                : "Animation disabled: use a real HD topic-specific image with minimal labels from the sentence. Do not use bee, flower, pollen, or pollination imagery.");
            segment.setLabels(extractFallbackLabels(sentence));
            segment.setComfyPrompt("High-quality educational visual directly matching this sentence: " + sentence
                + ", accurate subject matter, no bee, no flower, no pollen, no pollination imagery, no unrelated biology content");
            segment.setCoverageNotes("Topic guard: removed off-topic bee/pollination imagery because it is not present in this narration sentence.");
        }
    }

    private boolean containsAnyIgnoreCase(String text, String... needles) {
        for (String needle : needles) {
            if (containsIgnoreCase(text, needle)) {
                return true;
            }
        }
        return false;
    }

    private List<String> extractFallbackLabels(String sentence) {
        List<String> labels = new ArrayList<>();
        if (sentence == null || sentence.isBlank()) {
            return labels;
        }
        String cleaned = sentence.replaceAll("[^A-Za-z0-9+\\- ]", " ");
        String[] words = cleaned.split("\\s+");
        for (String word : words) {
            if (word.length() >= 6 && labels.size() < 3 && !containsLabel(labels, word)) {
                labels.add(word);
            }
        }
        return labels;
    }

    private String buildAnimationModeInstruction() {
        if (animationEnabled) {
            return """
                Animation mode is ENABLED.
                You may use diagram, animation, photo_with_labels, animation_with_labels, or wan_video depending on educational clarity.
                Use local animation/diagram styles when they explain abstract ideas, labels, equations, sequence, or anatomy better than real footage.
                """;
        }
        return """
            Animation mode is DISABLED.
            Prefer real HD photo/photo_with_labels and wan_video outputs.
            Do not choose diagram, animation, or animation_with_labels unless the concept is impossible to show accurately with real imagery.
            Avoid blank educational card layouts, sequence cards, slide-style boxes, and synthetic diagram panels.
            If labels are needed, use photo_with_labels over animation_with_labels.
            Wan video is allowed more often when natural realistic motion helps the lesson.
            """;
    }

    private void enforceAnimationMode(SceneSegment segment) {
        if (animationEnabled) {
            return;
        }

        if ("diagram".equals(segment.getMediaType())
                || "animation".equals(segment.getMediaType())
                || "animation_with_labels".equals(segment.getMediaType())) {
            if (shouldUseWanWhenAnimationDisabled(segment)) {
                segment.setMediaType("wan_video");
                segment.setMotionType("wan_video");
                Shot shot = new Shot();
                shot.setTemplate("video");
                shot.setHeading(segment.getVisualAnimation() != null ? segment.getVisualAnimation() : segment.getSentence());
                shot.setPrompt(buildRealisticWanPrompt(segment));
                shot.setNegativePrompt(defaultNegativePrompt());
                shot.setDurationSeconds(segment.getRecommendedClipSeconds());
                segment.setShot(shot);
                segment.setLtxShot(buildLtxShot(segment));
            } else {
                segment.setMediaType((segment.getLabels() != null && !segment.getLabels().isEmpty()) ? "photo_with_labels" : "photo");
                segment.setMotionType("static_image");
                segment.setShot(null);
                segment.setLtxShot(null);
            }
        }

        segment.setVisualAnimation(toRealHdVisual(segment.getVisualAnimation(), segment.getSentence()));
        segment.setLocalAnimation("Animation disabled: use a real HD image/video frame. Add only minimal overlay labels if listed.");
        segment.setComfyPrompt(buildRealHdPrompt(segment));
        segment.setCoverageNotes(appendNote(segment.getCoverageNotes(),
            "Animation disabled: storyboard is biased toward real HD imagery/photo labels and Wan video instead of local card/diagram animation."));
    }

    private boolean shouldUseWanWhenAnimationDisabled(SceneSegment segment) {
        String text = joinForFactCheck(segment.getSentence(), segment.getVisualAnimation(), segment.getLocalAnimation());
        return containsIgnoreCase(text, "bee")
            || containsIgnoreCase(text, "bird")
            || containsIgnoreCase(text, "bat")
            || containsIgnoreCase(text, "wind")
            || containsIgnoreCase(text, "water")
            || containsIgnoreCase(text, "moving")
            || containsIgnoreCase(text, "visiting")
            || containsIgnoreCase(text, "flow")
            || containsIgnoreCase(text, "pour")
            || containsIgnoreCase(text, "reaction")
            || containsIgnoreCase(text, "machine")
            || containsIgnoreCase(text, "process");
    }

    private String toRealHdVisual(String current, String sentence) {
        String base = current != null && !current.isBlank() ? current : sentence;
        return "Real HD educational visual: " + base
            + ". Avoid blank cards, slide boxes, synthetic sequence panels, and diagram-only layouts.";
    }

    private String buildRealHdPrompt(SceneSegment segment) {
        String base = segment.getVisualAnimation() != null ? segment.getVisualAnimation() : segment.getSentence();
        return "Realistic HD educational visual, " + base
            + ", natural lighting, sharp detail, documentary style, accurate subject, no blank cards, no slide layout, no text-heavy graphic panels";
    }

    private String buildRealisticWanPrompt(SceneSegment segment) {
        String base = segment.getVisualAnimation() != null ? segment.getVisualAnimation() : segment.getSentence();
        return "Realistic HD educational Wan video, " + base
            + ", natural motion, documentary style, sharp detail, accurate subject, seamless continuation, no blank cards, no slide layout";
    }

    private Shot buildLtxShot(SceneSegment segment) {
        Shot ltxShot = new Shot();
        ltxShot.setTemplate("video");
        ltxShot.setHeading(segment.getVisualAnimation() != null ? segment.getVisualAnimation() : segment.getSentence());
        ltxShot.setPrompt(buildRealisticLtxPrompt(segment));
        ltxShot.setNegativePrompt(defaultNegativePrompt());
        ltxShot.setDurationSeconds(segment.getRecommendedClipSeconds());
        return ltxShot;
    }

    private String buildRealisticLtxPrompt(SceneSegment segment) {
        String base = segment.getVisualAnimation() != null ? segment.getVisualAnimation() : segment.getSentence();
        return "LTX video, realistic HD educational footage, " + base
            + ", one continuous clear action from beginning to end, simple camera movement, stable subject, accurate educational detail, no abrupt final-frame freeze, no text artifacts, no slide layout";
    }

    private void enforceTiming(SceneSegment segment) {
        if (segment.getEstimatedNarrationSeconds() <= 0) {
            segment.setEstimatedNarrationSeconds(estimateNarrationSeconds(segment.getSentence()));
        }
        if (segment.getRecommendedClipSeconds() < segment.getEstimatedNarrationSeconds()) {
            segment.setRecommendedClipSeconds(segment.getEstimatedNarrationSeconds());
        }

        if (segment.getTimingNotes() == null || segment.getTimingNotes().isBlank()) {
            if ("wan_video".equals(segment.getMediaType())) {
                segment.setTimingNotes(buildWanTimingNote(segment.getRecommendedClipSeconds()));
            } else {
                segment.setTimingNotes("Hold or animate this visual for the full narration duration with slow camera movement and staged labels if needed.");
            }
        }

        if (segment.getShot() != null) {
            segment.getShot().setDurationSeconds(segment.getRecommendedClipSeconds());
            if (segment.getShot().getPrompt() != null && segment.getRecommendedClipSeconds() > 4.5
                    && !containsIgnoreCase(segment.getShot().getPrompt(), "seamless")
                    && !containsIgnoreCase(segment.getShot().getPrompt(), "continuation")) {
                segment.getShot().setPrompt(segment.getShot().getPrompt()
                    + ", designed for " + roundOneDecimal(segment.getRecommendedClipSeconds())
                    + " seconds with seamless natural continuation, no abrupt ending");
            }
        }
        if ("wan_video".equals(segment.getMediaType()) && segment.getLtxShot() == null) {
            segment.setLtxShot(buildLtxShot(segment));
        }
        if (segment.getLtxShot() != null) {
            segment.getLtxShot().setDurationSeconds(segment.getRecommendedClipSeconds());
            if (segment.getLtxShot().getPrompt() != null && segment.getRecommendedClipSeconds() > 4.5
                    && !containsIgnoreCase(segment.getLtxShot().getPrompt(), "continuous")
                    && !containsIgnoreCase(segment.getLtxShot().getPrompt(), "no abrupt")) {
                segment.getLtxShot().setPrompt(segment.getLtxShot().getPrompt()
                    + ", continuous motion for " + roundOneDecimal(segment.getRecommendedClipSeconds())
                    + " seconds, no abrupt final-frame freeze");
            }
        }
    }

    private double estimateNarrationSeconds(String sentence) {
        if (sentence == null || sentence.isBlank()) {
            return 4.0;
        }
        String[] words = sentence.trim().split("\\s+");
        double seconds = (words.length / 2.35) + 1.0;
        return Math.max(3.0, Math.min(12.0, roundOneDecimal(seconds)));
    }

    private String buildWanTimingNote(double seconds) {
        if (seconds <= 4.5) {
            return "Generate one Wan clip for the full narration duration; avoid abrupt final-frame hold.";
        }
        int clips = (int) Math.ceil(seconds / 4.0);
        return "Narration is longer than a 4-second Wan clip. Generate " + clips
            + " continuation clips or one seamless looping motion totaling at least "
            + roundOneDecimal(seconds) + " seconds; do not stretch a single 4-second clip.";
    }

    private double roundOneDecimal(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private void enforceElectroplatingScience(SceneSegment segment) {
        String combined = joinForFactCheck(
            segment.getSentence(),
            segment.getVisualAnimation(),
            segment.getLocalAnimation(),
            segment.getComfyPrompt(),
            segment.getCoverageNotes()
        );
        if (!containsIgnoreCase(combined, "electroplat")
                && !containsIgnoreCase(combined, "silver nitrate")
                && !containsIgnoreCase(combined, "cathode")
                && !containsIgnoreCase(combined, "deposition")) {
            return;
        }

        replaceInSegment(segment, "galvanic cell", "electrolytic cell");
        replaceInSegment(segment, "galvanic setup", "electrolytic setup");

        if (containsIgnoreCase(combined, "silver nitrate") && containsIgnoreCase(combined, "chloride")) {
            segment.setVisualAnimation("Accurate electrolytic-cell diagram for electroplating: silver nitrate solution contains Ag+ and NO3- ions, with Ag+ moving toward the cathode.");
            segment.setLocalAnimation("Label Ag+ and NO3- in the electrolyte. Do not show chloride ions unless silver chloride is explicitly being discussed. Show Ag+ gaining an electron at the cathode.");
            segment.setLabels(mergeLabels(segment.getLabels(), List.of("Ag+", "NO3-", "Cathode", "Anode", "Electrolyte")));
            segment.setComfyPrompt("Accurate educational chemistry diagram of silver electroplating in an electrolytic cell, silver nitrate electrolyte labeled Ag+ and NO3- ions, cathode and anode clearly labeled, Ag+ ions moving to cathode, clean textbook style");
            segment.setCoverageNotes(appendNote(segment.getCoverageNotes(), "Science guard: silver nitrate is represented as Ag+ and NO3-; chloride ions are excluded unless chloride chemistry is explicitly part of the lesson."));
        }

        if (containsIgnoreCase(combined, "empty space")
                || containsIgnoreCase(combined, "outermost shell")
                || containsIgnoreCase(combined, "outer shell")) {
            segment.setVisualAnimation("Electroplating reaction diagram showing Ag+ ions gaining electrons at the cathode and becoming neutral silver atoms.");
            segment.setLocalAnimation("Animate Ag+ moving to the cathode, then show the half-equation Ag+ + e- -> Ag and silver atoms depositing as a thin coating.");
            segment.setLabels(mergeLabels(segment.getLabels(), List.of("Ag+", "e-", "Ag", "Cathode")));
            segment.setComfyPrompt("Educational electrochemistry diagram, cathode electron transfer, Ag+ plus electron becomes Ag, silver atoms depositing as a coating, accurate labels, clean classroom style");
            segment.setCoverageNotes(appendNote(segment.getCoverageNotes(), "Science guard: deposition is explained as electron gain at the cathode: Ag+ + e- -> Ag."));
        }

        if ((containsIgnoreCase(combined, "gold") && containsIgnoreCase(combined, "silver"))
                && (containsIgnoreCase(combined, "more deposit")
                    || containsIgnoreCase(combined, "deposits more")
                    || containsIgnoreCase(combined, "always deposits"))) {
            segment.setVisualAnimation("Comparison diagram using Faraday's law factors for metal deposition rather than claiming one metal always deposits more.");
            segment.setLocalAnimation("Show deposited mass depends on current, time, molar mass, and number of electrons transferred. Avoid a fixed gold-versus-silver winner unless values are provided.");
            segment.setLabels(mergeLabels(segment.getLabels(), List.of("Current", "Time", "Molar mass", "Electrons transferred")));
            segment.setComfyPrompt("Educational Faraday's law comparison diagram for electroplating, deposited mass depends on current, time, molar mass, electrons transferred, gold and silver examples, no unsupported always-more claim");
            segment.setCoverageNotes(appendNote(segment.getCoverageNotes(), "Science guard: gold-versus-silver deposition is framed with Faraday's law, not as an unconditional more/less claim."));
        }
    }

    private String joinForFactCheck(String... values) {
        StringBuilder joined = new StringBuilder();
        for (String value : values) {
            if (value != null) {
                joined.append(value).append(' ');
            }
        }
        return joined.toString();
    }

    private void replaceInSegment(SceneSegment segment, String target, String replacement) {
        segment.setVisualAnimation(replaceIgnoreCase(segment.getVisualAnimation(), target, replacement));
        segment.setLocalAnimation(replaceIgnoreCase(segment.getLocalAnimation(), target, replacement));
        segment.setComfyPrompt(replaceIgnoreCase(segment.getComfyPrompt(), target, replacement));
        segment.setCoverageNotes(replaceIgnoreCase(segment.getCoverageNotes(), target, replacement));
        if (segment.getShot() != null) {
            segment.getShot().setHeading(replaceIgnoreCase(segment.getShot().getHeading(), target, replacement));
            segment.getShot().setPrompt(replaceIgnoreCase(segment.getShot().getPrompt(), target, replacement));
            segment.getShot().setNegativePrompt(replaceIgnoreCase(segment.getShot().getNegativePrompt(), target, replacement));
        }
        if (segment.getLtxShot() != null) {
            segment.getLtxShot().setHeading(replaceIgnoreCase(segment.getLtxShot().getHeading(), target, replacement));
            segment.getLtxShot().setPrompt(replaceIgnoreCase(segment.getLtxShot().getPrompt(), target, replacement));
            segment.getLtxShot().setNegativePrompt(replaceIgnoreCase(segment.getLtxShot().getNegativePrompt(), target, replacement));
        }
    }

    private String replaceIgnoreCase(String text, String target, String replacement) {
        if (text == null) {
            return null;
        }
        return text.replaceAll("(?i)\\b" + java.util.regex.Pattern.quote(target) + "\\b", replacement);
    }

    private String appendNote(String current, String note) {
        if (current == null || current.isBlank()) {
            return note;
        }
        if (current.contains(note)) {
            return current;
        }
        return current + " " + note;
    }

    private String inferMediaType(SceneSegment segment) {
        if (segment.getLabels() != null && !segment.getLabels().isEmpty()) {
            return "animation_with_labels";
        }
        if (containsIgnoreCase(segment.getVisualAnimation(), "diagram")
                || containsIgnoreCase(segment.getLocalAnimation(), "diagram")
                || containsIgnoreCase(segment.getLocalAnimation(), "arrow")
                || containsIgnoreCase(segment.getLocalAnimation(), "label")) {
            return "animation_with_labels";
        }
        if ("static_image".equals(segment.getMotionType())) {
            return "photo";
        }
        return "animation";
    }

    private String buildDefaultComfyPrompt(SceneSegment segment) {
        String base = segment.getVisualAnimation() != null ? segment.getVisualAnimation() : segment.getSentence();
        return "Educational content visual, " + base
            + ", accurate subject matter, clean composition, high clarity, suitable for classroom video";
    }

    private String defaultNegativePrompt() {
        return "incorrect anatomy, wrong labels, unreadable text, watermark, blurry, distorted, fantasy, irrelevant objects";
    }

    private boolean containsIgnoreCase(String text, String needle) {
        return text != null && text.toLowerCase(java.util.Locale.ROOT).contains(needle.toLowerCase(java.util.Locale.ROOT));
    }

    private List<String> mergeLabels(List<String> existing, List<String> required) {
        List<String> merged = new ArrayList<>();
        if (existing != null) {
            for (String label : existing) {
                if (label != null && !label.isBlank() && !containsLabel(merged, label)) {
                    merged.add(label);
                }
            }
        }
        for (String label : required) {
            if (!containsLabel(merged, label)) {
                merged.add(label);
            }
        }
        return merged;
    }

    private boolean containsLabel(List<String> labels, String target) {
        for (String label : labels) {
            if (label.equalsIgnoreCase(target)) {
                return true;
            }
        }
        return false;
    }

    private String getStringOrDefault(JsonObject obj, String memberName, String fallback) {
        if (obj.has(memberName) && !obj.get(memberName).isJsonNull()) {
            return obj.get(memberName).getAsString();
        }
        return fallback;
    }

    private double getDoubleOrDefault(JsonObject obj, String memberName, double fallback) {
        if (obj.has(memberName) && !obj.get(memberName).isJsonNull()) {
            try {
                return obj.get(memberName).getAsDouble();
            } catch (Exception ignored) {
                return fallback;
            }
        }
        return fallback;
    }
    
    private String extractTitle(String text) {
        // Simple title extraction - first sentence or first 50 chars
        int end = text.indexOf('.');
        if (end > 10 && end < 100) {
            return text.substring(0, end);
        }
        return text.substring(0, Math.min(50, text.length())) + "...";
    }

    private String buildLanguageInstruction(String text) {
        if (containsRange(text, '\u0B80', '\u0BFF')) {
            return "The transcript is Tamil. Write every storyboard field in Tamil, except established scientific/technical terms may stay in English when the transcript uses them.";
        }
        if (containsRange(text, '\u0900', '\u097F')) {
            return "The transcript uses Devanagari script. Write every storyboard field in that same language/script, except established technical terms may stay in English when the transcript uses them.";
        }
        if (containsRange(text, '\u0C00', '\u0C7F')) {
            return "The transcript is Telugu. Write every storyboard field in Telugu, except established technical terms may stay in English when the transcript uses them.";
        }
        if (containsRange(text, '\u0D00', '\u0D7F')) {
            return "The transcript is Malayalam. Write every storyboard field in Malayalam, except established technical terms may stay in English when the transcript uses them.";
        }
        if (containsRange(text, '\u0C80', '\u0CFF')) {
            return "The transcript is Kannada. Write every storyboard field in Kannada, except established technical terms may stay in English when the transcript uses them.";
        }
        if (containsRange(text, '\u0980', '\u09FF')) {
            return "The transcript is Bengali. Write every storyboard field in Bengali, except established technical terms may stay in English when the transcript uses them.";
        }
        if (containsRange(text, '\u0A80', '\u0AFF')) {
            return "The transcript is Gujarati. Write every storyboard field in Gujarati, except established technical terms may stay in English when the transcript uses them.";
        }
        return "Default to English. Write every storyboard field in English unless the transcript itself is clearly in another language. Do not translate English transcript content into Tamil or any other language.";
    }

    private boolean containsRange(String text, char start, char end) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= start && ch <= end) {
                return true;
            }
        }
        return false;
    }
}
