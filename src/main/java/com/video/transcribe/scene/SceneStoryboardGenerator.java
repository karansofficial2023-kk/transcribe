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
    private final String videoProvider;
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
              "template": {
                "type": "string",
                "enum": [
                  "title_card",
                  "labeled_image",
                  "comparison",
                  "process",
                  "formula",
                  "split_screen",
                  "video_broll"
                ]
              },
              "heading": {
                "type": "string"
              },
              "visualSubject": {
                "type": "string"
              },
              "assetPath": {
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
              "arrows": {
                "type": "array",
                "items": {
                  "type": "string"
                }
              },
              "highlights": {
                "type": "array",
                "items": {
                  "type": "string"
                }
              },
              "formulaLines": {
                "type": "array",
                "items": {
                  "type": "string"
                }
              },
              "explainSteps": {
                "type": "array",
                "items": {
                  "type": "string"
                }
              },
              "steps": {
                "type": "array",
                "items": {
                  "type": "string"
                }
              },
              "columns": {
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
              "motion": {
                "type": "string"
              },
              "subtitleStyle": {
                "type": "string"
              },
              "tool": {
                "type": "string",
                "enum": [
                  "pillow_opencv",
                  "ffmpeg",
                  "manim",
                  "comfy_image",
                  "ltx_video",
                  "upscale",
                  "reviewed_asset"
                ]
              },
              "assetQualityNotes": {
                "type": "string"
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
                  },
                  "clipDurationSeconds": {
                    "type": "number"
                  },
                  "clipCount": {
                    "type": "integer"
                  },
                  "joinInstructions": {
                    "type": "string"
                  }
                },
                "required": [
                  "template",
                  "heading",
                  "prompt",
                  "negativePrompt",
                  "durationSeconds",
                  "clipDurationSeconds",
                  "clipCount",
                  "joinInstructions"
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
                  },
                  "clipDurationSeconds": {
                    "type": "number"
                  },
                  "clipCount": {
                    "type": "integer"
                  },
                  "joinInstructions": {
                    "type": "string"
                  }
                },
                "required": [
                  "template",
                  "heading",
                  "prompt",
                  "negativePrompt",
                  "durationSeconds",
                  "clipDurationSeconds",
                  "clipCount",
                  "joinInstructions"
                ],
                "additionalProperties": false
              }
            },
            "required": [
              "segmentNumber",
              "sentence",
              "template",
              "heading",
              "visualSubject",
              "assetPath",
              "mediaType",
              "motionType",
              "estimatedNarrationSeconds",
              "recommendedClipSeconds",
              "timingNotes",
              "visualAnimation",
              "localAnimation",
              "labels",
              "arrows",
              "highlights",
              "formulaLines",
              "explainSteps",
              "steps",
              "columns",
              "imageRecommendations",
              "motion",
              "subtitleStyle",
              "tool",
              "assetQualityNotes",
              "comfyPrompt",
              "coverageNotes"
            ],
            "additionalProperties": false
          }
        }
        """).getAsJsonObject();


    
    public SceneStoryboardGenerator(OllamaClient ollama) {
        this(ollama, true, "wan");
    }

    public SceneStoryboardGenerator(OllamaClient ollama, boolean animationEnabled) {
        this(ollama, animationEnabled, "wan");
    }

    public SceneStoryboardGenerator(OllamaClient ollama, boolean animationEnabled, String videoProvider) {
        this.ollama = ollama;
        this.animationEnabled = animationEnabled;
        this.videoProvider = normalizeVideoProvider(videoProvider);
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
            %s
            For each segment, provide:
            1. The exact sentence text
            2. template: "title_card", "labeled_image", "comparison", "process", "formula", "split_screen", or "video_broll"
            3. heading: short screen title drawn by the renderer
            4. visualSubject: clean background image/video subject with no embedded text
            5. assetPath: optional reviewed asset path, or "" when none
            6. mediaType: "photo", "diagram", "animation", "photo_with_labels", "animation_with_labels", or "wan_video"
            7. motionType: "wan_video", "local_animation", or "static_image"
            4. estimatedNarrationSeconds based on sentence length at natural voiceover speed
            5. recommendedClipSeconds for the visual, matching or exceeding narration timing
            6. timingNotes explaining loop, hold, cutaway, or extension strategy
            7. Visual/Animation description (what should be shown on screen)
            8. Local animation instructions for teaching clarity, such as arrows, highlights, zooms, labels, diagrams, step reveals, or comparison panels
            9. labels, arrows, highlights, formulaLines, explainSteps, steps, and columns as renderer overlay instructions
            10. Image recommendations (2 specific no-text image descriptions for stock photo/illustration search)
            11. motion: camera, reveal, and transition plan
            12. subtitleStyle: usually "bottom_band black 38% opacity white centered max 2 lines"
            13. tool: "pillow_opencv", "ffmpeg", "manim", "comfy_image", "ltx_video", "upscale", or "reviewed_asset"
            14. assetQualityNotes: whether to use reviewed asset, generated still, deterministic diagram, LTX b-roll, and/or upscale
            15. ComfyUI prompt for clean background asset only
            16. Coverage notes explaining which curriculum facts from the sentence are covered visually
            17. A shot object for Wan/ComfyUI video generation only when the scene needs natural cinematic motion
            18. An ltxShot object for LTX video generation only for short b-roll motion, including 4-second clip planning
            
            SCENE TITLE: %s
            NARRATION:
            %s
            
            Respond ONLY with a JSON array matching the provided schema.
            Do not include markdown, explanations, examples, or fields outside the schema.
            
            Rules:
            - Split by natural sentence boundaries
            - Use 8 to 14 total segments for a 1-2 minute lesson when possible; if narration is longer, keep one concept per segment.
            - Target 5 to 9 seconds per segment; short title cards may be 3 to 5 seconds.
            - Every 2-3 segments, vary visual rhythm using title_card, labeled_image, comparison, process, formula, split_screen, or video_broll.
            - Do not change the narration/script while creating storyboard rows; the sentence must come from the narration
            - Think as the correct SME for the detected subject and topic
            - Use curriculum-safe explanations and standard subject terminology
            - Never introduce off-topic animals, plants, tools, reactions, locations, or examples from previous videos or prompt examples.
            - If a visual detail is uncertain, avoid inventing it; use a neutral diagram, label, or coverage note instead
            - Core renderer rule: never ask AI image/video models to create exact text, labels, arrows, formulas, legends, numbers, or scientific names inside the generated image/video.
            - Put all exact text, labels, arrows, legends, highlights, formulas, and subtitles in overlay fields so Pillow/OpenCV/Manim/FFmpeg can draw them precisely.
            - comfyPrompt and shot prompts must request clean backgrounds with no text, no labels, no captions, no watermarks, and no formula text.
            - Use title_card for openings and section starts; renderer draws heading/subheading.
            - Use labeled_image for apparatus/anatomy/parts; labels/arrows are overlays, not generated in the image.
            - Use process for one-by-one steps; steps are renderer text overlays.
            - Use comparison for self-vs-cross, strong-vs-weak, before-vs-after, or concept contrasts; columns are renderer text overlays.
            - Use formula for chemistry/physics/math formulas and derivations; tool must be manim and formulaLines must contain exact formula text.
            - Use split_screen for two related visuals; labels are overlays.
            - Use video_broll only for simple natural motion where exact labels/formulas are not required.
            - Tool routing: title_card/labeled_image/process/comparison/split_screen usually use pillow_opencv plus ffmpeg; formula uses manim; generated still background uses comfy_image; LTX b-roll uses ltx_video; final sharpening can add upscale in assetQualityNotes.
            - Visuals should be specific and actionable for video editors
            - Images should be descriptive enough for stock photo searches
            - Use educational documentary style visuals
            - Estimate timing carefully: short sentences may be 3-5 seconds, medium sentences 6-8 seconds, long sentences 9-12 seconds.
            - recommendedClipSeconds must be at least estimatedNarrationSeconds.
            - LTX compatibility requirement: LTX outputs 4-second clips. For LTX video rows, plan clipDurationSeconds = 4.0 and clipCount = ceil(recommendedClipSeconds / 4.0).
            - If narration is longer than 4 seconds, timingNotes must explain that the video generator should create multiple 4-second LTX clips and join them in order.
            - ltxShot.prompt must be detailed enough for joined 4-second clips: describe the full action, subject, environment, camera movement, continuity, and what each clip should continue from.
            - ltxShot.joinInstructions must clearly describe how to split and join the LTX clips without changing narration, losing curriculum coverage, or freezing the last frame.
            - If video generation normally outputs short clips, then for narration longer than the clip length specify continuation clips, seamless loop motion, slow camera movement, or cutaways in timingNotes.
            - Use generated video only where natural motion improves learning: pollinators moving, wind/water motion, liquids flowing, machine/process movement, lab action, real-world cause-effect motion.
            - Do not use generated video for concepts better taught with clean diagrams, labels, equations, maps, grammar steps, comparisons, or anatomy/process charts.
            - LTX must not be used for exact anther/stigma labeling, formulas, graphs with readable numbers, legends, or small precise anatomy.
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
            - Use generated video shots only for natural/cinematic motion that is explicitly supported by the narration; template must be video_broll when using ltxShot
            - Use local_animation for teaching clarity: arrows, labels, highlighted parts, cutaway diagrams, timelines, maps, math/grammar steps, charts, comparisons, or process diagrams
            - Use static_image for a still photo or illustration with optional labels
            - For diagram/photo/animation media, write comfyPrompt as an image prompt or animation design prompt; for wan_video, write comfyPrompt to match shot.prompt.
            - If motionType is not "wan_video", omit both shot and ltxShot fields.
            - Never add shot or ltxShot objects for static_image or local_animation rows.
            - For every wan_video row, shot.durationSeconds and ltxShot.durationSeconds must equal recommendedClipSeconds.
            - For every ltxShot, set clipDurationSeconds = 4.0 and clipCount = ceil(durationSeconds / 4.0).
            - Write shot.prompt for Wan style: cinematic natural motion, stable subject anatomy, smooth camera, no text artifacts.
            - Write ltxShot.prompt for LTX style: realistic educational video, subject-matter accurate, one continuous action split into 4-second continuation clips, clear start-to-end motion, no abrupt final-frame freeze, simple camera path, enough visual detail for the full narration duration.
            - Labels should be short, screen-ready text. Return [] when labels are not useful.
            """.formatted(languageInstruction, buildAnimationModeInstruction(), buildVideoProviderInstruction(), scene.getSceneTitle(), scene.getNarration());
        
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
                seg.setTemplate(getStringOrDefault(obj, "template", "labeled_image"));
                seg.setHeading(getStringOrDefault(obj, "heading", ""));
                seg.setVisualSubject(getStringOrDefault(obj, "visualSubject", ""));
                seg.setAssetPath(getStringOrDefault(obj, "assetPath", ""));
                seg.setMediaType(getStringOrDefault(obj, "mediaType", "animation_with_labels"));
                seg.setMotionType(getStringOrDefault(obj, "motionType", "local_animation"));
                seg.setEstimatedNarrationSeconds(getDoubleOrDefault(obj, "estimatedNarrationSeconds", estimateNarrationSeconds(seg.getSentence())));
                seg.setRecommendedClipSeconds(getDoubleOrDefault(obj, "recommendedClipSeconds", seg.getEstimatedNarrationSeconds()));
                seg.setTimingNotes(getStringOrDefault(obj, "timingNotes", ""));
                seg.setVisualAnimation(obj.get("visualAnimation").getAsString());
                seg.setLocalAnimation(getStringOrDefault(obj, "localAnimation", ""));

                seg.setLabels(getStringList(obj, "labels"));
                seg.setArrows(getStringList(obj, "arrows"));
                seg.setHighlights(getStringList(obj, "highlights"));
                seg.setFormulaLines(getStringList(obj, "formulaLines"));
                seg.setExplainSteps(getStringList(obj, "explainSteps"));
                seg.setSteps(getStringList(obj, "steps"));
                seg.setColumns(getStringList(obj, "columns"));
                seg.setImageRecommendations(getStringList(obj, "imageRecommendations"));
                seg.setMotion(getStringOrDefault(obj, "motion", ""));
                seg.setSubtitleStyle(getStringOrDefault(obj, "subtitleStyle", ""));
                seg.setTool(getStringOrDefault(obj, "tool", "pillow_opencv"));
                seg.setAssetQualityNotes(getStringOrDefault(obj, "assetQualityNotes", ""));
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
            fallback.setTemplate("process");
            fallback.setHeading("Main Content");
            fallback.setVisualSubject("Clean educational background related to the lesson topic, no text");
            fallback.setAssetPath("");
            fallback.setMediaType("animation_with_labels");
            fallback.setMotionType("local_animation");
            fallback.setEstimatedNarrationSeconds(estimateNarrationSeconds(fallback.getSentence()));
            fallback.setRecommendedClipSeconds(fallback.getEstimatedNarrationSeconds());
            fallback.setTimingNotes("Hold the educational diagram for the full narration duration with slow zoom and staged labels.");
            fallback.setVisualAnimation("Animation: Educational content display");
            fallback.setLocalAnimation("Use clear labels, highlights, and step-by-step reveals to explain the concept");
            fallback.setLabels(List.of());
            fallback.setArrows(List.of());
            fallback.setHighlights(List.of());
            fallback.setFormulaLines(List.of());
            fallback.setExplainSteps(List.of());
            fallback.setSteps(List.of("Show the main concept", "Reveal supporting details", "Hold for narration"));
            fallback.setColumns(List.of());
            fallback.setImageRecommendations(List.of("Image 1: Educational illustration", "Image 2: Related concept diagram"));
            fallback.setMotion("camera: static; reveal: one_by_one; transition_in: fade; transition_out: crossfade");
            fallback.setSubtitleStyle("bottom_band black 38% opacity white centered max 2 lines");
            fallback.setTool("pillow_opencv");
            fallback.setAssetQualityNotes("Fallback deterministic overlay layout; generate or choose a clean no-text background.");
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
        shot.setClipDurationSeconds(getDoubleOrDefault(shotObj, "clipDurationSeconds", 4.0));
        shot.setClipCount(getIntOrDefault(shotObj, "clipCount", clipCountForDuration(shot.getDurationSeconds(), shot.getClipDurationSeconds())));
        shot.setJoinInstructions(getStringOrDefault(shotObj, "joinInstructions", buildJoinInstructions(shot.getDurationSeconds(), shot.getClipDurationSeconds())));
        return shot;
    }

    private void enforceStoryboardQuality(SceneSegment segment) {
        enforceProStyleDefaults(segment);
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
        enforceTemplateToolRules(segment);
        if (segment.getLtxShot() != null) {
            segment.setTemplate("video_broll");
            segment.setTool("ltx_video");
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
        segment.setComfyPrompt(ensureNoTextPrompt(segment.getComfyPrompt()));

        enforceTopicRelevance(segment);
        enforceElectroplatingScience(segment);

        if (segment.getCoverageNotes() == null || segment.getCoverageNotes().isBlank()) {
            segment.setCoverageNotes("Covers the narration sentence with matching visuals, labels, and image recommendations.");
        }
    }

    private void enforceTemplateToolRules(SceneSegment segment) {
        if ("formula".equals(segment.getTemplate())) {
            segment.setTool("manim");
            segment.setMotionType("local_animation");
            segment.setMediaType("animation_with_labels");
            segment.setShot(null);
            segment.setLtxShot(null);
            if (segment.getFormulaLines() == null || segment.getFormulaLines().isEmpty()) {
                segment.setFormulaLines(extractFormulaFallback(segment.getSentence()));
            }
            segment.setAssetQualityNotes(appendNote(segment.getAssetQualityNotes(),
                "Formula segment: render exact formulas with Manim and composite over clean background; do not generate formulas inside image/video."));
        }
        if ("video_broll".equals(segment.getTemplate())) {
            segment.setMediaType("wan_video");
            segment.setMotionType("wan_video");
            segment.setTool("ltx_video");
        }
        if (!"video_broll".equals(segment.getTemplate()) && segment.getLtxShot() != null) {
            segment.setLtxShot(null);
        }
    }

    private List<String> extractFormulaFallback(String sentence) {
        if (sentence == null || sentence.isBlank()) {
            return List.of();
        }
        if (sentence.contains("=")) {
            return List.of(sentence);
        }
        return List.of();
    }

    private String ensureNoTextPrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return prompt;
        }
        if (containsIgnoreCase(prompt, "no text") && containsIgnoreCase(prompt, "no labels")) {
            return prompt;
        }
        return prompt + ", no text, no labels, no captions, no arrows, no formulas, no watermark";
    }

    private void enforceProStyleDefaults(SceneSegment segment) {
        if (segment.getTemplate() == null || segment.getTemplate().isBlank()) {
            segment.setTemplate(inferTemplate(segment));
        }
        if (segment.getHeading() == null || segment.getHeading().isBlank()) {
            segment.setHeading(buildHeading(segment.getSentence()));
        }
        if (segment.getVisualSubject() == null || segment.getVisualSubject().isBlank()) {
            segment.setVisualSubject(buildVisualSubject(segment));
        }
        if (segment.getAssetPath() == null) {
            segment.setAssetPath("");
        }
        if (segment.getLabels() == null) segment.setLabels(List.of());
        if (segment.getArrows() == null) segment.setArrows(List.of());
        if (segment.getHighlights() == null) segment.setHighlights(List.of());
        if (segment.getFormulaLines() == null) segment.setFormulaLines(List.of());
        if (segment.getExplainSteps() == null) segment.setExplainSteps(List.of());
        if (segment.getSteps() == null) segment.setSteps(List.of());
        if (segment.getColumns() == null) segment.setColumns(List.of());
        if (segment.getMotion() == null || segment.getMotion().isBlank()) {
            segment.setMotion(defaultMotion(segment.getTemplate()));
        }
        if (segment.getSubtitleStyle() == null || segment.getSubtitleStyle().isBlank()) {
            segment.setSubtitleStyle("bottom_band; band_color=black; band_opacity=0.38; text_color=white; max_lines=2; align=center");
        }
        if (segment.getTool() == null || segment.getTool().isBlank()) {
            segment.setTool(inferTool(segment));
        }
        if (segment.getAssetQualityNotes() == null || segment.getAssetQualityNotes().isBlank()) {
            segment.setAssetQualityNotes(defaultAssetQualityNotes(segment));
        }
    }

    private String inferTemplate(SceneSegment segment) {
        String text = joinForFactCheck(segment.getSentence(), segment.getVisualAnimation(), segment.getLocalAnimation());
        if (containsAnyIgnoreCase(text, "=", "formula", "law", "equation", "lambda", "Λ")) {
            return "formula";
        }
        if (containsAnyIgnoreCase(text, "versus", " vs ", "compare", "comparison", "different", "whereas")) {
            return "comparison";
        }
        if ("wan_video".equals(segment.getMediaType()) || "wan_video".equals(segment.getMotionType())) {
            return "video_broll";
        }
        if (containsAnyIgnoreCase(text, "step", "first", "second", "then", "process")) {
            return "process";
        }
        if (segment.getLabels() != null && !segment.getLabels().isEmpty()) {
            return "labeled_image";
        }
        return "labeled_image";
    }

    private String buildHeading(String sentence) {
        if (sentence == null || sentence.isBlank()) {
            return "Main Concept";
        }
        String heading = sentence.replaceAll("[\\r\\n]+", " ").trim();
        if (heading.length() > 54) {
            heading = heading.substring(0, 54).trim();
            int lastSpace = heading.lastIndexOf(' ');
            if (lastSpace > 20) {
                heading = heading.substring(0, lastSpace);
            }
        }
        return heading;
    }

    private String buildVisualSubject(SceneSegment segment) {
        String base = segment.getVisualAnimation() != null && !segment.getVisualAnimation().isBlank()
            ? segment.getVisualAnimation()
            : segment.getSentence();
        return base + "; clean background asset only; no text, no labels, no formulas, no arrows";
    }

    private String defaultMotion(String template) {
        return switch (template) {
            case "title_card" -> "camera: slow_zoom_in; title_animation: fade_up; transition_in: fade; transition_out: crossfade";
            case "formula" -> "formula_reveal: line_by_line; camera: static; transition_in: fade; transition_out: crossfade";
            case "comparison" -> "column_reveal: left_then_right; camera: static; transition_in: fade; transition_out: crossfade";
            case "process" -> "step_reveal: one_by_one; camera: static; transition_in: fade; transition_out: crossfade";
            case "split_screen" -> "camera: subtle_independent_zoom; transition_in: fade; transition_out: crossfade";
            case "video_broll" -> "camera: stable; transition_in: fade; transition_out: crossfade";
            default -> "camera: slow_zoom_in; label_reveal: one_by_one; transition_in: fade; transition_out: crossfade";
        };
    }

    private String inferTool(SceneSegment segment) {
        return switch (segment.getTemplate()) {
            case "formula" -> "manim";
            case "video_broll" -> "ltx_video";
            case "title_card", "labeled_image", "comparison", "process", "split_screen" -> "pillow_opencv";
            default -> "pillow_opencv";
        };
    }

    private String defaultAssetQualityNotes(SceneSegment segment) {
        return switch (segment.getTemplate()) {
            case "formula" -> "Use Manim for exact formula text and line-by-line reveal; do not generate formulas inside images.";
            case "video_broll" -> "Use LTX only for short natural motion; composite precise labels/subtitles separately; upscale if needed.";
            case "labeled_image" -> "Prefer reviewed real/curriculum asset; otherwise generate a no-text still and draw labels/arrows with Pillow/OpenCV.";
            default -> "Use clean no-text background asset; draw all text, labels, arrows, legends, and subtitles in renderer overlays.";
        };
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
            Prefer real HD photo/photo_with_labels and generated-video outputs.
            Do not choose diagram, animation, or animation_with_labels unless the concept is impossible to show accurately with real imagery.
            Avoid blank educational card layouts, sequence cards, slide-style boxes, and synthetic diagram panels.
            If labels are needed, use photo_with_labels over animation_with_labels.
            Generated video is allowed more often when natural realistic motion helps the lesson.
            """;
    }

    private String buildVideoProviderInstruction() {
        if ("ltx".equals(videoProvider)) {
            return """
                Active generated-video provider is LTX.
                For generated-video rows, prioritize the ltxShot prompt as the production-ready prompt.
                Keep the Wan shot field populated as an alternate prompt, but make the LTX shot the clearest and most directly usable option.
                LTX currently works best as 4-second clips, so every ltxShot must be planned as one or more joined 4-second clips.
                Think like the SME for this subject: make each LTX prompt detailed, visually clear, topic-related, curriculum-safe, and tied only to the narration sentence.
                Do not change narration. Use prompt detail, clip continuity, and join instructions to cover the narration fully.
                """;
        }
        if ("all".equals(videoProvider)) {
            return """
                Active generated-video provider is ALL.
                For generated-video rows, populate both Wan and LTX shot prompts equally well so downstream tools can choose either model.
                """;
        }
        return """
            Active generated-video provider is Wan.
            For generated-video rows, prioritize the Wan shot prompt as the production-ready prompt.
            Keep the LTX shot field populated as an alternate prompt when generated motion is useful.
            """;
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
            "Animation disabled: storyboard is biased toward real HD imagery/photo labels and generated video instead of local card/diagram animation."));
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
        ltxShot.setClipDurationSeconds(4.0);
        ltxShot.setClipCount(clipCountForDuration(ltxShot.getDurationSeconds(), ltxShot.getClipDurationSeconds()));
        ltxShot.setJoinInstructions(buildJoinInstructions(ltxShot.getDurationSeconds(), ltxShot.getClipDurationSeconds()));
        return ltxShot;
    }

    private String buildRealisticLtxPrompt(SceneSegment segment) {
        String base = segment.getVisualAnimation() != null ? segment.getVisualAnimation() : segment.getSentence();
        return "LTX video, realistic HD educational footage, " + base
            + ", split into 4-second continuation clips if needed, one continuous clear action from beginning to end, simple camera movement, stable subject, accurate educational detail, no abrupt final-frame freeze, no text artifacts, no slide layout";
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
            segment.getLtxShot().setClipDurationSeconds(4.0);
            segment.getLtxShot().setClipCount(clipCountForDuration(
                segment.getLtxShot().getDurationSeconds(),
                segment.getLtxShot().getClipDurationSeconds()));
            if (segment.getLtxShot().getJoinInstructions() == null
                    || segment.getLtxShot().getJoinInstructions().isBlank()) {
                segment.getLtxShot().setJoinInstructions(buildJoinInstructions(
                    segment.getLtxShot().getDurationSeconds(),
                    segment.getLtxShot().getClipDurationSeconds()));
            }
            if (segment.getLtxShot().getPrompt() != null && segment.getRecommendedClipSeconds() > 4.5
                    && !containsIgnoreCase(segment.getLtxShot().getPrompt(), "continuous")
                    && !containsIgnoreCase(segment.getLtxShot().getPrompt(), "no abrupt")) {
                segment.getLtxShot().setPrompt(segment.getLtxShot().getPrompt()
                    + ", continuous motion for " + roundOneDecimal(segment.getRecommendedClipSeconds())
                    + " seconds, no abrupt final-frame freeze");
            }
        }
    }

    private int clipCountForDuration(double durationSeconds, double clipDurationSeconds) {
        double clipLength = clipDurationSeconds > 0 ? clipDurationSeconds : 4.0;
        return Math.max(1, (int) Math.ceil(durationSeconds / clipLength));
    }

    private String buildJoinInstructions(double durationSeconds, double clipDurationSeconds) {
        int clips = clipCountForDuration(durationSeconds, clipDurationSeconds);
        if (clips == 1) {
            return "Generate one 4-second LTX clip and trim or hold only as needed to match the narration timing; avoid a frozen final frame.";
        }
        return "Generate " + clips + " separate 4-second LTX clips. Clip 1 establishes the subject and action; each next clip continues the same motion, camera direction, lighting, and subject placement. Join clips in order with straight cuts or subtle crossfades to cover "
            + roundOneDecimal(durationSeconds) + " seconds of narration without changing the script, skipping curriculum facts, or freezing the final frame.";
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
        if ("ltx".equals(videoProvider)) {
            return buildLtxTimingNote(seconds);
        }
        if (seconds <= 4.5) {
            return "Generate one Wan clip for the full narration duration; avoid abrupt final-frame hold.";
        }
        int clips = (int) Math.ceil(seconds / 4.0);
        return "Narration is longer than a 4-second Wan clip. Generate " + clips
            + " continuation clips or one seamless looping motion totaling at least "
            + roundOneDecimal(seconds) + " seconds; do not stretch a single 4-second clip.";
    }

    private String buildLtxTimingNote(double seconds) {
        int clips = clipCountForDuration(seconds, 4.0);
        if (clips == 1) {
            return "LTX plan: generate one 4-second clip and trim/hold subtly to match the narration; avoid a frozen final frame.";
        }
        return "LTX plan: split this visual into " + clips + " joined 4-second clips. Keep the same subject, lighting, camera direction, and action continuity across clips; join in order to cover "
            + roundOneDecimal(seconds) + " seconds of narration.";
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

    private List<String> getStringList(JsonObject obj, String memberName) {
        List<String> values = new ArrayList<>();
        if (!obj.has(memberName) || obj.get(memberName).isJsonNull()) {
            return values;
        }
        try {
            JsonArray arr = obj.getAsJsonArray(memberName);
            if (arr != null) {
                arr.forEach(e -> {
                    if (!e.isJsonNull()) {
                        values.add(e.getAsString());
                    }
                });
            }
        } catch (Exception ignored) {
            try {
                values.add(obj.get(memberName).getAsString());
            } catch (Exception alsoIgnored) {
                return values;
            }
        }
        return values;
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

    private int getIntOrDefault(JsonObject obj, String memberName, int fallback) {
        if (obj.has(memberName) && !obj.get(memberName).isJsonNull()) {
            try {
                return obj.get(memberName).getAsInt();
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
