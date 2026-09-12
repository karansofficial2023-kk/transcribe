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
                  }
                },
                "required": [
                  "template",
                  "heading",
                  "prompt",
                  "negativePrompt"
                ],
                "additionalProperties": false
              }
            },
            "required": [
              "segmentNumber",
              "sentence",
              "mediaType",
              "motionType",
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
        this.ollama = ollama;
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
            Each scene should cover a distinct topic or sub-topic.
            Create 3-8 scenes depending on content length.
            %s
            
            TEXT:
            %s
            
            Respond ONLY with a JSON array of objects:
            [
              {
                "sceneNumber": 1,
                "sceneTitle": "Introduction to Topic",
                "narration": "full narration text for this scene..."
              }
            ]
            
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
            %s
            For each sentence, provide:
            1. The exact sentence text
            2. mediaType: "photo", "diagram", "animation", "photo_with_labels", "animation_with_labels", or "wan_video"
            3. motionType: "wan_video", "local_animation", or "static_image"
            4. Visual/Animation description (what should be shown on screen)
            5. Local animation instructions for teaching clarity, such as arrows, highlights, zooms, labels, diagrams, step reveals, or comparison panels
            6. Labels to place on images/diagrams when useful
            7. Image recommendations (2 specific image descriptions for stock photo/illustration search)
            8. ComfyUI prompt for the selected media type
            9. Coverage notes explaining which curriculum facts from the sentence are covered visually
            10. A shot object for Wan/ComfyUI video generation only when the scene needs natural cinematic motion.
            
            SCENE TITLE: %s
            NARRATION:
            %s
            
            Respond ONLY with a JSON array:
            [
              {
                "segmentNumber": 1,
                "sentence": "exact sentence text",
                "mediaType": "wan_video",
                "motionType": "wan_video",
                "visualAnimation": "Natural video: bee moves from one flower to another",
                "localAnimation": "Add subtle label callouts to flower, bee, and pollen after the motion begins",
                "labels": ["Bee", "Flower", "Pollen"],
                "imageRecommendations": ["Image 1: specific description", "Image 2: specific description"],
                "comfyPrompt": "Educational documentary macro shot of a bee visiting a flower, visible pollen grains, natural daylight, realistic botany detail, clean background, high clarity",
                "coverageNotes": "Covers bee, flower, and pollen transfer from the narration sentence.",
                "shot": {
                  "template": "video",
                  "heading": "Bee visiting a flower",
                  "prompt": "Cinematic macro video of a bee naturally moving onto a flower, pollen visible on anthers and bee legs, gentle handheld documentary motion, realistic colors, shallow depth of field, educational clarity",
                  "negativePrompt": "cartoon, fantasy anatomy, wrong plant parts, text artifacts, watermark, blurry, extra limbs, distorted insect, inaccurate flower structure"
                }
              }
            ]
            
            Rules:
            - Split by natural sentence boundaries
            - Visuals should be specific and actionable for video editors
            - Images should be descriptive enough for stock photo searches
            - Use educational documentary style visuals
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
            - If a sentence contains a likely anatomy-risk phrase such as "anther curls", preserve the sentence text, but keep the visual neutral: use a labeled diagram and pollen-transfer arrows instead of instructing physical curling/anther motion.
            - Use Wan video only for natural/cinematic motion. Add shot.template = "video" only for motion such as:
              bee moving flower to flower, wind blowing pollen across grass,
              water surface carrying pollen, bird visiting tubular flower,
              bat visiting night flower
            - Use local_animation for teaching clarity: arrows, labels, highlighted parts, cutaway diagrams, timelines, maps, math/grammar steps, charts, comparisons, or process diagrams
            - Use static_image for a still photo or illustration with optional labels
            - For diagram/photo/animation media, write comfyPrompt as an image prompt or animation design prompt; for wan_video, write comfyPrompt to match shot.prompt.
            - If motionType is not "wan_video", omit the shot field
            - Never add a shot object for static_image or local_animation rows
            - Labels should be short, screen-ready text. Return [] when labels are not useful.
            """.formatted(languageInstruction, scene.getSceneTitle(), scene.getNarration());
        
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
                    JsonObject shotObj = obj.getAsJsonObject("shot");
                    Shot shot = new Shot();
                    shot.setTemplate(getStringOrDefault(shotObj, "template", "video"));
                    shot.setHeading(getStringOrDefault(shotObj, "heading", seg.getVisualAnimation()));
                    shot.setPrompt(getStringOrDefault(shotObj, "prompt", seg.getComfyPrompt()));
                    shot.setNegativePrompt(getStringOrDefault(shotObj, "negativePrompt", defaultNegativePrompt()));
                    seg.setShot(shot);
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

    private void enforceStoryboardQuality(SceneSegment segment) {
        String motionType = segment.getMotionType();
        if (motionType == null || motionType.isBlank()) {
            motionType = "local_animation";
            segment.setMotionType(motionType);
        }

        if (!"wan_video".equals(motionType)) {
            segment.setShot(null);
        }
        if (segment.getMediaType() == null || segment.getMediaType().isBlank()) {
            segment.setMediaType("wan_video".equals(motionType) ? "wan_video" : inferMediaType(segment));
        }
        if (!"wan_video".equals(segment.getMediaType())) {
            segment.setShot(null);
        }

        if (containsIgnoreCase(segment.getSentence(), "anther curls")
                || containsIgnoreCase(segment.getVisualAnimation(), "anther curls")
                || containsIgnoreCase(segment.getLocalAnimation(), "anther curls")
                || containsIgnoreCase(segment.getLocalAnimation(), "movement of the anther")) {
            segment.setMotionType("local_animation");
            segment.setMediaType("animation_with_labels");
            segment.setShot(null);
            segment.setVisualAnimation("Labeled close-up diagram of the flower reproductive parts showing pollen, anther, and sticky stigma.");
            segment.setLocalAnimation("Preserve the narration sentence, but avoid animating the anther physically curling. Use arrows to show pollen transfer toward the stigma and labels for anther, pollen, and stigma.");
            segment.setLabels(mergeLabels(segment.getLabels(), List.of("Anther", "Pollen", "Stigma")));
            segment.setComfyPrompt("Accurate educational botanical diagram of flower reproductive parts, anther, pollen grains, sticky stigma, arrows showing pollen transfer, clean labels, white background, textbook style");
            segment.setCoverageNotes("Fact guard: narration is preserved, but the visual avoids depicting anther curling as literal motion; it covers anther, pollen, and stigma with a labeled pollen-transfer diagram.");
        }

        if (segment.getComfyPrompt() == null || segment.getComfyPrompt().isBlank()) {
            segment.setComfyPrompt(buildDefaultComfyPrompt(segment));
        }

        if (segment.getCoverageNotes() == null || segment.getCoverageNotes().isBlank()) {
            segment.setCoverageNotes("Covers the narration sentence with matching visuals, labels, and image recommendations.");
        }
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
