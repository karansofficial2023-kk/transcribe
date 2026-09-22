package com.video.transcribe.scene;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
    private final boolean curriculumEnrichmentEnabled;
    private final Gson gson = new Gson();
    private static final String PRO_LABEL_STYLE = "high_contrast_box; white_text; dark_background; "
        + "colored_target_dot; 3px_leader_line; 28px_minimum_font; avoid_subject; "
        + "avoid_title_area; avoid_subtitle_area; avoid_logo_area";
    private static final String PRO_SUBTITLE_STYLE = "bottom_band; band_color=black; band_opacity=0.55; "
        + "text_color=white; font_size=42; max_lines=2; align=center; "
        + "horizontal_margin=120; bottom_margin=55";
    private static final String LABELED_MOTION = "arrow_draw_then_label_fade; reveal_in_list_order; "
        + "keep_previous_labels_visible; completed_frame_hold=2.5s";
    private static final JsonObject TOPIC_SCHEMA = JsonParser.parseString("""
        {
          "type": "object",
          "properties": {
            "inferredTopic": {
              "type": "string"
            },
            "filenameTopic": {
              "type": "string"
            },
            "topicMatch": {
              "type": "boolean"
            },
            "confidence": {
              "type": "number"
            },
            "safeStoryboardTitle": {
              "type": "string"
            },
            "subject": {
              "type": "string"
            },
            "smeRole": {
              "type": "string"
            },
            "warning": {
              "type": "string"
            }
          },
          "required": [
            "inferredTopic",
            "filenameTopic",
            "topicMatch",
            "confidence",
            "safeStoryboardTitle",
            "subject",
            "smeRole",
            "warning"
          ],
          "additionalProperties": false
        }
        """).getAsJsonObject();
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
              "sentenceNumbers": {
                "type": "array",
                "items": {
                  "type": "integer"
                }
              }
            },
            "required": [
              "sceneNumber",
              "sceneTitle",
              "sentenceNumbers"
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
                  "photo",
                  "labeled_image",
                  "comparison",
                  "process",
                  "formula",
                  "split_screen",
                  "video_broll"
                ]
              },
              "visualType": {
                "type": "string",
                "enum": [
                  "title_card",
                  "realistic_image",
                  "realistic_labeled_image",
                  "diagram_overlay",
                  "process_steps",
                  "realistic_background_with_labels",
                  "short_motion_clip"
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
              "labelPlacements": {
                "type": "array",
                "items": {
                  "type": "string"
                }
              },
              "labelStyle": {
                "type": "string"
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
              "subtitle": {
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
              "visualType",
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
              "labelPlacements",
              "labelStyle",
              "arrows",
              "highlights",
              "formulaLines",
              "explainSteps",
              "steps",
              "columns",
              "imageRecommendations",
              "motion",
              "subtitle",
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

    private static final JsonObject LABEL_REPAIR_SCHEMA = JsonParser.parseString("""
        {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "rowId": { "type": "string" },
              "labels": { "type": "array", "items": { "type": "string" } },
              "labelPlacements": { "type": "array", "items": { "type": "string" } },
              "visualSubject": { "type": "string" },
              "comfyPrompt": { "type": "string" }
            },
            "required": [
              "rowId", "labels", "labelPlacements",
              "visualSubject", "comfyPrompt"
            ],
            "additionalProperties": false
          }
        }
        """).getAsJsonObject();

    private static final JsonObject SME_REVIEW_SCHEMA = JsonParser.parseString("""
        {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "rowId": { "type": "string" },
              "approved": { "type": "boolean" },
              "issue": { "type": "string" },
              "template": { "type": "string", "enum": ["title_card", "photo", "labeled_image", "comparison", "process", "formula", "split_screen", "video_broll"] },
              "visualType": { "type": "string", "enum": ["title_card", "realistic_image", "realistic_labeled_image", "diagram_overlay", "process_steps", "realistic_background_with_labels", "short_motion_clip"] },
              "heading": { "type": "string" },
              "visualSubject": { "type": "string" },
              "mediaType": { "type": "string", "enum": ["photo", "diagram", "animation", "photo_with_labels", "animation_with_labels", "wan_video"] },
              "motionType": { "type": "string", "enum": ["wan_video", "local_animation", "static_image"] },
              "tool": { "type": "string", "enum": ["pillow_opencv", "ffmpeg", "manim", "comfy_image", "ltx_video", "upscale", "reviewed_asset"] },
              "comfyPrompt": { "type": "string" },
              "coverageNotes": { "type": "string" },
              "formulaLines": { "type": "array", "items": { "type": "string" } }
            },
            "required": ["rowId", "approved", "issue", "template", "visualType", "heading", "visualSubject", "mediaType", "motionType", "tool", "comfyPrompt", "coverageNotes", "formulaLines"],
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
        this(ollama, animationEnabled, videoProvider, false);
    }

    public SceneStoryboardGenerator(OllamaClient ollama, boolean animationEnabled, String videoProvider,
            boolean curriculumEnrichmentEnabled) {
        this.ollama = ollama;
        this.animationEnabled = animationEnabled;
        this.videoProvider = normalizeVideoProvider(videoProvider);
        this.curriculumEnrichmentEnabled = curriculumEnrichmentEnabled;
    }
    
    /**
     * Generate complete storyboard from paraphrased text
     */
    public StoryboardDocument generateStoryboard(String paraphrasedText) throws IOException {
        return generateStoryboard(paraphrasedText, null);
    }

    public StoryboardDocument generateStoryboard(String paraphrasedText, String baseName) throws IOException {
        String storyboardText = normalizeProductionText(paraphrasedText);
        logger.info("Generating scene storyboard from text ({} chars)...", storyboardText.length());
        String languageInstruction = buildLanguageInstruction(storyboardText);
        TopicCheck topicCheck = verifyTopic(storyboardText, baseName, languageInstruction);
        
        // Step 1: Split into logical scenes
        List<Scene> scenes = splitIntoScenes(storyboardText, languageInstruction, topicCheck);
        
        // Step 2: For each scene, generate segments with visuals and images
        for (Scene scene : scenes) {
            enrichSceneWithSegments(scene, languageInstruction, topicCheck);
        }
        normalizeScenes(scenes, storyboardText, topicCheck.safeStoryboardTitle());
        reviewVisualPlansWithSme(scenes, storyboardText, languageInstruction, topicCheck);
        repairAndValidateLabelPlans(scenes, storyboardText, languageInstruction, topicCheck);
        enforceFinalProductionContract(scenes, topicCheck.safeStoryboardTitle());
        rebuildSceneNarrationFromSegments(scenes);
        
        StoryboardDocument doc = new StoryboardDocument();
        doc.setTitle(topicCheck.safeStoryboardTitle());
        doc.setSubject(topicCheck.subject());
        doc.setTopic(topicCheck.inferredTopic());
        doc.setSmeRole(topicCheck.smeRole());
        doc.setSourceText(storyboardText);
        doc.setScenes(scenes);
        doc.setGeneratedAt(java.time.Instant.now().toString());
        
        logger.info("Storyboard generated with {} scenes", scenes.size());
        return doc;
    }

    private void repairAndValidateLabelPlans(List<Scene> scenes, String lessonText,
            String languageInstruction, TopicCheck topicCheck) {
        JsonArray labelCandidates = new JsonArray();
        Set<String> expectedRowIds = new LinkedHashSet<>();
        for (Scene scene : scenes) {
            for (SceneSegment segment : scene.getSegments()) {
                if (isLabeledVisual(segment) || hasLabels(segment)) {
                    JsonObject item = new JsonObject();
                    String rowId = scene.getSceneNumber() + "." + segment.getSegmentNumber();
                    item.addProperty("rowId", rowId);
                    item.addProperty("sentence", segment.getSentence());
                    item.addProperty("heading", segment.getHeading());
                    item.addProperty("visualSubject", segment.getVisualSubject());
                    item.add("labels", gson.toJsonTree(segment.getLabels()));
                    item.add("labelPlacements", gson.toJsonTree(segment.getLabelPlacements()));
                    labelCandidates.add(item);
                    expectedRowIds.add(rowId);
                }
            }
        }

        if (!labelCandidates.isEmpty()) {
            String systemPrompt = """
                You are %s and a scientific illustration director.
                Repair label plans for educational storyboard still images in any subject.
                Return only schema-constrained JSON. Do not change narration or lesson facts.
                """.formatted(topicCheck.smeRole());
            String userPrompt = """
                Review and repair every labeled row below, including rows that appear structurally valid.
                %s

                LESSON CONTEXT:
                %s

                VERIFIED SUBJECT: %s
                VERIFIED TOPIC: %s

                LABELED ROWS TO REVIEW:
                %s

                Requirements for every returned row:
                - labels contains separate atomic curriculum terms; one visible target per item.
                - Never combine distinct labels into one string.
                - Replace vague nouns with precise names supported by the narration.
                - labelPlacements contains exactly one entry per label using:
                  Label name | exact semantic target description | target_xy: AUTO_VERIFY
                - Never invent coordinates for an image that has not been generated. Numeric normalized
                  coordinates are allowed only when assetPath identifies a locked, reviewed asset.
                - visualSubject and comfyPrompt must describe a sharp 1920x1080 label-ready composition
                  in which every semantic target is unobscured with sufficient overlay margins.
                - End comfyPrompt with: No embedded text. No generated labels. No generated arrows.
                  No captions. No watermark. No slide or presentation-card layout.
                - Narration about named structures, anatomical parts, apparatus components,
                  spatial comparisons, visible adaptations, process stages, or contrasting phases
                  requires a stable labeled still or deterministic diagram with complete labels.
                - If a concept genuinely has no trustworthy visible target, return empty labels and
                  placements; the application will convert it to a plain unlabeled realistic image.
                """.formatted(languageInstruction, lessonText, topicCheck.subject(),
                    topicCheck.inferredTopic(), gson.toJson(labelCandidates));
            try {
                String response = ollama.generateStructured(systemPrompt, userPrompt,
                    buildLabelRepairSchema(expectedRowIds));
                applyLabelPlanRepairs(scenes, JsonParser.parseString(response).getAsJsonArray(),
                    expectedRowIds);
            } catch (Exception e) {
                logger.warn("Label-plan repair failed; unsafe labeled rows will be downgraded: {}", e.getMessage());
            }
        }

        for (Scene scene : scenes) {
            for (SceneSegment segment : scene.getSegments()) {
                finalizeLabelConsistency(segment);
            }
        }
    }

    private void reviewVisualPlansWithSme(List<Scene> scenes, String lessonText,
            String languageInstruction, TopicCheck topicCheck) {
        JsonArray rows = new JsonArray();
        Set<String> expected = new LinkedHashSet<>();
        for (Scene scene : scenes) {
            for (SceneSegment segment : scene.getSegments()) {
                JsonObject item = new JsonObject();
                item.addProperty("rowId", scene.getSceneNumber() + "." + segment.getSegmentNumber());
                item.addProperty("sentence", segment.getSentence());
                item.addProperty("template", segment.getTemplate());
                item.addProperty("visualType", segment.getVisualType());
                item.addProperty("heading", segment.getHeading());
                item.addProperty("visualSubject", segment.getVisualSubject());
                item.addProperty("mediaType", segment.getMediaType());
                item.addProperty("motionType", segment.getMotionType());
                item.addProperty("tool", segment.getTool());
                item.addProperty("comfyPrompt", segment.getComfyPrompt());
                item.addProperty("coverageNotes", segment.getCoverageNotes());
                item.add("formulaLines", gson.toJsonTree(segment.getFormulaLines()));
                rows.add(item);
                expected.add(scene.getSceneNumber() + "." + segment.getSegmentNumber());
            }
        }
        if (rows.isEmpty()) return;

        String systemPrompt = "You are " + topicCheck.smeRole()
            + " and the final curriculum and visual-production reviewer. "
            + "Return only schema-constrained JSON. Never rewrite narration.";
        String userPrompt = """
            Perform a final SME audit of every storyboard row for this lesson.
            %s

            VERIFIED SUBJECT: %s
            VERIFIED TOPIC: %s
            REQUIRED SME ROLE: %s

            LESSON SOURCE:
            %s

            STORYBOARD ROWS:
            %s

            Return every row exactly once. Set approved=true only when the visual is factual,
            directly relevant to its narration, and suitable for the detected subject and topic.
            For rejected rows, correct only the production fields in the schema.
            Requirements:
            - Remove all people, organisms, apparatus, locations, reactions, formulas, and examples
              that belong to another lesson or subject.
            - Do not add curriculum claims not supported by the source lesson.
            - Route exact equations, calculations, derivations, symbolic laws, and substitutions to
              template=formula, visualType=process_steps, mediaType=animation,
              motionType=local_animation, tool=manim, with exact formulaLines.
            - AI image prompts describe only a clean background asset. They must not ask the image
              model to render text, labels, arrows, captions, formulas, numbers, or watermarks.
            - Use generated motion video only for natural continuous motion. Use deterministic
              overlays or diagrams for exact teaching content.
            - If one row contains several distinct structures, examples, phases, or mechanisms,
              use stable split-screen/process panels; never combine precise labels with LTX/Wan motion.
            - Keep the heading concise and the coverage note specific to the narration sentence.
            """.formatted(languageInstruction, topicCheck.subject(), topicCheck.inferredTopic(),
                topicCheck.smeRole(), lessonText, gson.toJson(rows));
        try {
            String response = ollama.generateStructured(systemPrompt, userPrompt,
                buildSmeReviewSchema(expected));
            JsonArray reviews = JsonParser.parseString(response).getAsJsonArray();
            Set<String> seen = new LinkedHashSet<>();
            for (var element : reviews) {
                JsonObject review = element.getAsJsonObject();
                String key = getStringOrDefault(review, "rowId", "");
                if (!expected.contains(key)) {
                    logger.warn("SME visual audit returned unknown row {}; ignoring it", key);
                    continue;
                }
                if (!seen.add(key)) {
                    logger.warn("SME visual audit returned duplicate row {}; keeping the first review", key);
                    continue;
                }
                String[] keyParts = key.split("\\.", 2);
                int sceneNumber = Integer.parseInt(keyParts[0]);
                int segmentNumber = Integer.parseInt(keyParts[1]);
                SceneSegment segment = findSegment(scenes, sceneNumber, segmentNumber);
                if (segment == null) {
                    logger.warn("SME visual audit row {} no longer exists; ignoring it", key);
                    continue;
                }
                if (getBooleanOrDefault(review, "approved", false)) continue;
                segment.setTemplate(getStringOrDefault(review, "template", segment.getTemplate()));
                segment.setVisualType(getStringOrDefault(review, "visualType", segment.getVisualType()));
                segment.setHeading(getStringOrDefault(review, "heading", segment.getHeading()));
                segment.setVisualSubject(getStringOrDefault(review, "visualSubject", segment.getVisualSubject()));
                segment.setMediaType(getStringOrDefault(review, "mediaType", segment.getMediaType()));
                segment.setMotionType(getStringOrDefault(review, "motionType", segment.getMotionType()));
                segment.setTool(getStringOrDefault(review, "tool", segment.getTool()));
                segment.setComfyPrompt(getStringOrDefault(review, "comfyPrompt", segment.getComfyPrompt()));
                segment.setCoverageNotes(appendNote(
                    getStringOrDefault(review, "coverageNotes", segment.getCoverageNotes()),
                    "SME correction: " + getStringOrDefault(review, "issue", "visual plan corrected")));
                segment.setFormulaLines(getStringList(review, "formulaLines"));
                enforceStoryboardQuality(segment);
            }
            if (!seen.equals(expected)) {
                Set<String> missing = new LinkedHashSet<>(expected);
                missing.removeAll(seen);
                logger.warn("SME visual audit omitted rows {}; keeping their existing SME-generated plans", missing);
            }
        } catch (Exception e) {
            logger.warn("Final SME visual audit could not be applied; keeping existing SME-generated plans: {}",
                e.getMessage());
        }
    }

    private JsonObject buildSmeReviewSchema(Set<String> expectedRowIds) {
        JsonObject schema = SME_REVIEW_SCHEMA.deepCopy();
        JsonObject rowId = schema.getAsJsonObject("items")
            .getAsJsonObject("properties").getAsJsonObject("rowId");
        rowId.add("enum", gson.toJsonTree(expectedRowIds));
        return schema;
    }

    void finalizeLabelConsistency(SceneSegment segment) {
        // First align rows that arrived as process/photo layouts but contain labels.
        alignVisualTypeWithLabels(segment);
        if ((isLabeledVisual(segment) || hasLabels(segment)) && !isValidLabelPlan(segment)) {
            if (canRemainSpecializedWithoutLabels(segment)) {
                clearUnsafeLabels(segment);
            } else {
                downgradeToUnlabeledVisual(segment);
            }
        }
        // Re-apply alignment after downgrade so every metadata field agrees.
        alignVisualTypeWithLabels(segment);
        enforceLabelContract(segment);
        enforceLabelDuration(segment);
    }

    private boolean hasLabels(SceneSegment segment) {
        return segment.getLabels() != null && !segment.getLabels().isEmpty();
    }

    private void enforceLabelDuration(SceneSegment segment) {
        int labelCount = segment.getLabels() == null ? 0 : segment.getLabels().size();
        if (labelCount == 0) return;
        double minimum = Math.max(segment.getEstimatedNarrationSeconds(), labelCount + 2.5);
        if (segment.getRecommendedClipSeconds() < minimum) {
            segment.setRecommendedClipSeconds(roundOneDecimal(minimum));
        }
        segment.setTimingNotes("Allow approximately 1.0 second per arrow-label reveal, keep earlier labels visible, then hold the completed labeled frame for at least 2.5 seconds.");
    }

    private boolean isSpecializedLayout(SceneSegment segment) {
        return "title_card".equals(segment.getTemplate())
            || "video_broll".equals(segment.getTemplate())
            || "formula".equals(segment.getTemplate())
            || "comparison".equals(segment.getTemplate())
            || "split_screen".equals(segment.getTemplate());
    }

    private boolean canRemainSpecializedWithoutLabels(SceneSegment segment) {
        return "title_card".equals(segment.getTemplate())
            || "video_broll".equals(segment.getTemplate())
            || "formula".equals(segment.getTemplate());
    }

    private void clearUnsafeLabels(SceneSegment segment) {
        segment.setLabels(List.of());
        segment.setLabelPlacements(List.of());
        segment.setArrows(List.of());
        segment.setHighlights(List.of());
        if ("formula".equals(segment.getTemplate())) {
            segment.setMediaType("animation");
        } else if ("video_broll".equals(segment.getTemplate())) {
            segment.setMediaType("wan_video");
        } else {
            segment.setMediaType("photo");
        }
        segment.setCoverageNotes(appendNote(segment.getCoverageNotes(),
            "Strict label-readiness guard: omitted unsafe labels while preserving the specialized layout."));
    }

    private void alignVisualTypeWithLabels(SceneSegment segment) {
        boolean hasLabels = segment.getLabels() != null && !segment.getLabels().isEmpty();
        boolean specializedLayout = isSpecializedLayout(segment);

        if (!hasLabels) {
            if (isLabeledVisual(segment)) {
                downgradeToUnlabeledVisual(segment);
            }
            return;
        }
        if (specializedLayout) {
            return;
        }

        segment.setTemplate("labeled_image");
        segment.setVisualType("realistic_labeled_image");
        segment.setMediaType("photo_with_labels");
        segment.setMotionType("static_image");
    }

    private void applyLabelPlanRepairs(List<Scene> scenes, JsonArray repairs,
            Set<String> expectedRowIds) {
        Set<String> seen = new LinkedHashSet<>();
        for (var element : repairs) {
            JsonObject repair = element.getAsJsonObject();
            String rowId = getStringOrDefault(repair, "rowId", "");
            if (!expectedRowIds.contains(rowId) || !seen.add(rowId)) {
                logger.warn("Label-plan repair returned unknown or duplicate row {}; ignoring it", rowId);
                continue;
            }
            String[] keyParts = rowId.split("\\.", 2);
            int sceneNumber = Integer.parseInt(keyParts[0]);
            int segmentNumber = Integer.parseInt(keyParts[1]);
            SceneSegment segment = findSegment(scenes, sceneNumber, segmentNumber);
            if (segment == null) continue;
            segment.setLabels(sanitizeLabels(getStringList(repair, "labels"), segment));
            segment.setLabelPlacements(getStringList(repair, "labelPlacements"));
            segment.setVisualSubject(repair.get("visualSubject").getAsString());
            segment.setComfyPrompt(ensureNoTextPrompt(repair.get("comfyPrompt").getAsString()));
            enforceLabelContract(segment);
        }
    }

    private JsonObject buildLabelRepairSchema(Set<String> expectedRowIds) {
        JsonObject schema = LABEL_REPAIR_SCHEMA.deepCopy();
        JsonObject rowId = schema.getAsJsonObject("items")
            .getAsJsonObject("properties").getAsJsonObject("rowId");
        rowId.add("enum", gson.toJsonTree(expectedRowIds));
        return schema;
    }

    private void rebuildSceneNarrationFromSegments(List<Scene> scenes) {
        for (Scene scene : scenes) {
            if (scene.getSegments() == null) continue;
            String narration = scene.getSegments().stream()
                .map(SceneSegment::getSentence)
                .filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.joining(" "));
            scene.setNarration(narration);
        }
    }

    private void enforceFinalProductionContract(List<Scene> scenes, String storyboardTitle) {
        for (int sceneIndex = 0; sceneIndex < scenes.size(); sceneIndex++) {
            Scene scene = scenes.get(sceneIndex);
            if (scene.getSegments() == null) continue;
            for (SceneSegment segment : scene.getSegments()) {
                sanitizeAssetPath(segment);
                enforceStoryboardQuality(segment);
                finalizeLabelConsistency(segment);
            }
            normalizeTitleCards(scene,
                sceneIndex == 0 ? storyboardTitle : scene.getSceneTitle(), sceneIndex == 0);
            for (SceneSegment segment : scene.getSegments()) {
                segment.setSubtitleStyle(PRO_SUBTITLE_STYLE);
                segment.setSubtitle(buildSubtitle(segment.getSentence()));
                segment.setComfyPrompt(ensureNoTextPrompt(segment.getComfyPrompt()));
                enforceLabelContract(segment);
                enforceLabelDuration(segment);
            }
        }
    }

    private SceneSegment findSegment(List<Scene> scenes, int sceneNumber, int segmentNumber) {
        return scenes.stream()
            .filter(scene -> scene.getSceneNumber() == sceneNumber)
            .flatMap(scene -> scene.getSegments().stream())
            .filter(segment -> segment.getSegmentNumber() == segmentNumber)
            .findFirst().orElse(null);
    }

    private boolean isValidLabelPlan(SceneSegment segment) {
        List<String> labels = segment.getLabels() == null ? List.of() : segment.getLabels();
        List<String> placements = segment.getLabelPlacements() == null
            ? List.of() : segment.getLabelPlacements();
        if (labels.isEmpty() || placements.size() != labels.size()) return false;
        for (String label : labels) {
            if (label == null || label.isBlank() || isTooGenericLabel(label)
                    || isCombinedLabel(label)) return false;
            String prefix = label.toLowerCase(Locale.ROOT);
            String placement = placements.stream()
                .filter(value -> placementMatchesLabel(value, prefix))
                .findFirst().orElse("");
            if (!hasAllowedTarget(segment, placement) || !hasSpecificTargetDescription(placement, label)) return false;
        }
        return true;
    }

    private boolean placementMatchesLabel(String placement, String lowerLabel) {
        if (placement == null) return false;
        String value = placement.toLowerCase(Locale.ROOT).trim();
        if (!value.startsWith(lowerLabel)) return false;
        String remainder = value.substring(lowerLabel.length()).trim();
        return remainder.startsWith("|") || remainder.startsWith(":");
    }

    private boolean hasAllowedTarget(SceneSegment segment, String placement) {
        if (placement == null) return false;
        if (containsIgnoreCase(placement, "target_xy: AUTO_VERIFY")) return true;
        return segment.getAssetPath() != null && !segment.getAssetPath().isBlank()
            && hasNumericTarget(placement);
    }

    private boolean isTooGenericLabel(String label) {
        String value = label.toLowerCase(Locale.ROOT).trim();
        return value.equals("flower")
            || value.equals("pollinator")
            || value.equals("pollen")
            || value.equals("nectar")
            || value.equals("part")
            || value.equals("component")
            || value.equals("structure")
            || value.equals("object")
            || value.equals("item")
            || value.equals("area");
    }

    private boolean isCombinedLabel(String label) {
        String value = label.toLowerCase(Locale.ROOT).trim();
        if (value.matches(".*[,;/|\\n].*") || value.matches(".*\\s+(?:and|&)\\s+.*")) {
            return true;
        }
        if (List.of("pollen grains", "pollen transfer path", "bee pollinator",
                "flower 1", "flower 2", "positive terminal", "negative terminal",
                "silver nitrate electrolyte").contains(value)) {
            return false;
        }
        int concepts = 0;
        for (String concept : List.of("anther", "stigma", "pollen", "filament", "style",
                "ovary", "nectar", "pollinator", "flower", "cathode", "anode",
                "electrolyte", "positive terminal", "negative terminal", "resistor",
                "switch", "ammeter", "voltmeter")) {
            if (java.util.regex.Pattern.compile("(?i)(?<![\\p{L}\\p{N}])"
                    + java.util.regex.Pattern.quote(concept)
                    + "(?![\\p{L}\\p{N}])").matcher(value).find()) {
                concepts++;
            }
        }
        return concepts > 1;
    }

    private boolean hasNumericTarget(String placement) {
        if (placement == null || containsIgnoreCase(placement, "target=(auto)")) return false;
        String number = "(0(?:\\.\\d+)?|1(?:\\.0+)?)";
        return java.util.regex.Pattern.compile(
            "target=\\(\\s*" + number + "\\s*,\\s*" + number + "\\s*\\)",
            java.util.regex.Pattern.CASE_INSENSITIVE).matcher(placement).find();
    }

    private boolean hasSpecificTargetDescription(String placement, String label) {
        if (placement == null) return false;
        String[] pipeParts = placement.split("\\|", -1);
        if (pipeParts.length >= 3) {
            String description = pipeParts[1].trim();
            return description.length() >= 8
                && !description.equalsIgnoreCase(label)
                && !containsAnyIgnoreCase(description, "object", "thing", "area", "detail");
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
            "(?i)target_description=([^;]+)").matcher(placement);
        if (!matcher.find()) return false;
        String description = matcher.group(1).trim();
        return description.length() >= 8
            && !description.equalsIgnoreCase(label)
            && !description.equalsIgnoreCase("exact visible " + label)
            && !containsAnyIgnoreCase(description, "object", "thing", "area", "detail");
    }

    private void downgradeToUnlabeledVisual(SceneSegment segment) {
        // Diagram, comparison, process, and labeled-image contracts all require
        // trustworthy labels. If that contract cannot be completed, retain the
        // visual concept as a plain still instead of exporting contradictory metadata.
        segment.setTemplate("photo");
        segment.setVisualType("realistic_image");
        segment.setMediaType("photo");
        segment.setMotionType("static_image");
        segment.setLabels(List.of());
        segment.setLabelPlacements(List.of());
        segment.setArrows(List.of());
        segment.setHighlights(List.of());
        segment.setCoverageNotes(appendNote(segment.getCoverageNotes(),
            "Strict label-readiness guard: rendered without labels because exact reviewed targets and coordinates were unavailable."));
    }
    
    /**
     * Split text into logical scenes using LLM
     */
    private TopicCheck verifyTopic(String text, String baseName, String languageInstruction) throws IOException {
        String filenameTopic = titleFromBaseName(baseName);
        String prompt = """
            Verify the real educational topic of this video transcript before storyboard creation.
            Compare the filename-derived topic with the transcript content. If the filename is
            wrong or too narrow, choose a safe storyboard title from the transcript topic instead.
            %s

            FILENAME TOPIC:
            %s

            TRANSCRIPT/PARAPHRASE:
            %s

            Respond ONLY with a JSON object matching the provided schema.
            Rules:
            - inferredTopic must describe the actual topic taught by the transcript.
            - subject must name the curriculum discipline that owns this lesson.
            - smeRole must name the narrow professional expertise needed to review this exact topic,
              not merely say "subject-matter expert".
            - topicMatch is true only when the filename topic matches the transcript topic well enough for a storyboard title.
            - safeStoryboardTitle must be short and suitable for the Word title.
            - warning should be "" when there is no mismatch; otherwise explain the mismatch briefly.
            """.formatted(languageInstruction, filenameTopic, text);

        try {
            String response = ollama.generateStructured(
                "You are a strict educational topic auditor.",
                prompt,
                TOPIC_SCHEMA
            );
            JsonObject obj = JsonParser.parseString(response).getAsJsonObject();
            String inferredTopic = getStringOrDefault(obj, "inferredTopic", extractTitle(text, baseName));
            String checkedFilenameTopic = getStringOrDefault(obj, "filenameTopic", filenameTopic);
            boolean topicMatch = getBooleanOrDefault(obj, "topicMatch", true);
            double confidence = getDoubleOrDefault(obj, "confidence", 0.0);
            String title = cleanStoryboardTitle(getStringOrDefault(obj, "safeStoryboardTitle", ""));
            String subject = getStringOrDefault(obj, "subject", inferSubjectFallback(text));
            String smeRole = getStringOrDefault(obj, "smeRole", "");
            String warning = getStringOrDefault(obj, "warning", "");
            if (isFilenameTopicSupportedByTranscript(checkedFilenameTopic, text)) {
                topicMatch = true;
                title = cleanStoryboardTitle(checkedFilenameTopic);
                warning = "";
            }
            if (title.isBlank()) {
                title = topicMatch && !checkedFilenameTopic.isBlank() ? checkedFilenameTopic : inferredTopic;
            }
            if (!topicMatch) {
                logger.warn("Storyboard topic mismatch: filenameTopic='{}', inferredTopic='{}', confidence={}, warning={}",
                    checkedFilenameTopic, inferredTopic, confidence, warning);
            } else {
                logger.info("Storyboard topic verified: {} (confidence={})", title, confidence);
            }
            if (smeRole.isBlank()) {
                smeRole = buildSmeRole(subject, inferredTopic);
            }
            return new TopicCheck(inferredTopic, checkedFilenameTopic, topicMatch, confidence,
                cleanStoryboardTitle(title), subject, smeRole, warning);
        } catch (Exception e) {
            String fallbackTitle = cleanStoryboardTitle(extractTitle(text, baseName));
            String subject = inferSubjectFallback(text);
            logger.warn("Topic verification failed; using fallback storyboard title '{}': {}", fallbackTitle, e.getMessage());
            return new TopicCheck(fallbackTitle, filenameTopic, true, 0.0, fallbackTitle,
                subject, buildSmeRole(subject, fallbackTitle), "");
        }
    }

    private boolean isFilenameTopicSupportedByTranscript(String filenameTopic, String transcript) {
        if (filenameTopic == null || filenameTopic.isBlank() || transcript == null || transcript.isBlank()) {
            return false;
        }
        Set<String> ignored = Set.of(
            "about", "and", "chapter", "class", "for", "introduction", "lesson", "of",
            "overview", "part", "the", "to", "types", "video");
        List<String> topicWords = Arrays.stream(filenameTopic.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
            .filter(word -> word.length() >= 4 && !ignored.contains(word))
            .distinct()
            .toList();
        if (topicWords.isEmpty()) return false;
        String normalizedTranscript = " " + transcript.toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}]+", " ") + " ";
        long matches = topicWords.stream()
            .filter(word -> normalizedTranscript.contains(" " + word + " "))
            .count();
        return matches >= Math.max(1, (topicWords.size() + 1) / 2);
    }

    private List<Scene> splitIntoScenes(String text, String languageInstruction, TopicCheck topicCheck) throws IOException {
        List<String> sourceSentences = splitSentences(text);
        StringBuilder numberedText = new StringBuilder();
        for (int i = 0; i < sourceSentences.size(); i++) {
            numberedText.append(i + 1).append(": ").append(sourceSentences.get(i)).append('\n');
        }
        String prompt = """
            Group the numbered source sentences into logical scenes.
            Think like a subject-matter expert and curriculum reviewer for this
            specific topic and subject. Keep explanations curriculum-safe and
            suitable for educational video production.
            Each scene should cover a distinct topic or sub-topic.
            Create fewer, stronger scenes: usually 6-10 scenes for a short lesson.
            Avoid micro-scenes and avoid one scene per sentence.
            %s
            %s
            Verified topic: %s
            Verified subject: %s
            Required SME reviewer: %s
            Filename topic match: %s
            
            NUMBERED SOURCE SENTENCES:
            %s
            
            Respond ONLY with a JSON array matching the provided schema.
            Do not include markdown, explanations, examples, or fields outside the schema.
            
            Rules:
            - Return sentenceNumbers only; Java reconstructs narration verbatim from the source.
            - Assign every source sentence number exactly once, in original order.
            - Each scene must contain a contiguous range of sentence numbers.
            - Never invent, omit, duplicate, reorder, paraphrase, or merge source sentences.
            - Scene titles should be descriptive
            - Do not introduce words from any language that is not present in the transcript
            - Do not merge unrelated topics
            - If filename topic match is false, trust the verified topic and transcript content, not the filename.
            - Use a curriculum arc: title/overview, definition, main categories, mechanisms or agents, adaptations/special cases, comparison, summary
            - If and only if the topic is types of pollination, prefer this scene arc when supported by the transcript:
              title card; definition as pollen transfer from anther to stigma; self-pollination; cross-pollination; abiotic agents wind/water; biotic agents insects/birds/bats/animals; adaptations such as homogamy/cleistogamy/dichogamy/herkogamy/heterostyly; self-vs-cross comparison; summary
            """.formatted(languageInstruction, buildCurriculumEnrichmentInstruction(),
                topicCheck.inferredTopic(), topicCheck.subject(), topicCheck.smeRole(),
                topicCheck.topicMatch(), numberedText);
        
        String response = ollama.generateStructured(
            "You are " + topicCheck.smeRole() + " and an educational storyboard director.",
            prompt, SCENES_SCHEMA
        );
        
        return parseScenes(response, sourceSentences);
    }
    
    /**
     * Enrich scene with sentence-level segments, visuals, and image recommendations
     */
    private void enrichSceneWithSegments(Scene scene, String languageInstruction,
            TopicCheck topicCheck) throws IOException {
        String prompt = """
            Break down the following scene narration into sentence-level segments.
            Think like a subject-matter expert and curriculum reviewer for this
            specific topic and subject. Preserve the narration/script exactly;
            improve only storyboard planning, visual choices, labels, coverage
            notes, and ComfyUI Wan/LTX prompts.
            %s
            %s
            %s
            %s
            VERIFIED SUBJECT: %s
            VERIFIED TOPIC: %s
            REQUIRED SME REVIEWER: %s
            For each segment, provide:
            1. The exact sentence text
            2. template: "title_card", "photo", "labeled_image", "comparison", "process", "formula", "split_screen", or "video_broll"
            3. visualType: "title_card", "realistic_image", "realistic_labeled_image", "diagram_overlay", "process_steps", "realistic_background_with_labels", or "short_motion_clip"
            4. heading: short screen title drawn by the renderer
            5. visualSubject: exact frame requirement describing the subject, composition, visible structures, camera angle, and 1080p realism; production metadata that must never be displayed as text
            5. assetPath: use "" unless the application supplied an absolute path to an existing,
               locked, human-reviewed asset. Never invent a filename or relative path.
            6. mediaType: "photo", "diagram", "animation", "photo_with_labels", "animation_with_labels", or "wan_video"
            7. motionType: "wan_video", "local_animation", or "static_image"
            4. estimatedNarrationSeconds based on sentence length at natural voiceover speed
            5. recommendedClipSeconds for the visual, matching or exceeding narration timing
            6. timingNotes explaining loop, hold, cutaway, or extension strategy
            7. Visual/Animation description (what should be shown on screen)
            8. Local animation instructions for teaching clarity, such as arrows, highlights, zooms, labels, diagrams, step reveals, or comparison panels
            9. labels: exact, atomic curriculum terms that point only to clearly visible objects. Each array item must name one target only; never combine multiple terms in one string.
            10. labelPlacements: one entry per label in "Label name | exact semantic target description | target_xy: AUTO_VERIFY" format. Never invent coordinates for an image that has not been generated. Numeric normalized coordinates are allowed only for a locked reviewed assetPath.
            11. labelStyle: exactly "%s"
            12. arrows, highlights, formulaLines, explainSteps, steps, and columns as renderer overlay instructions
            10. Image recommendations (2 specific no-text image descriptions for stock photo/illustration search)
            13. motion: one controlled camera/reveal instruction, never vague full animation
            14. subtitle: concise screen-readable summary, preferably 8-14 words; it may shorten narration without changing meaning
            15. subtitleStyle: exactly "%s"
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
            - Each segment sentence must be copied exactly from this scene narration. Do not create new sentence fragments.
            - Prefer one strong segment for this scene unless the scene contains two clearly different visual ideas.
            - Do not create empty white/card layouts. Use real HD photo/background assets or deterministic diagrams with strong composition.
            - Use 8 to 14 total segments for a 1-2 minute lesson when possible; if narration is longer, keep one concept per segment.
            - Target 5 to 9 seconds per segment; short title cards may be 3 to 5 seconds.
            - Every 2-3 segments, vary visual rhythm using title_card, labeled_image, comparison, process, formula, split_screen, or video_broll.
            - Do not change the narration/script while creating storyboard rows; the sentence must come from the narration
            - Think as the correct SME for the detected subject and topic
            - Use curriculum-safe explanations and standard subject terminology
            - Never introduce off-topic animals, plants, tools, reactions, locations, or examples from previous videos or prompt examples.
            - If a visual detail is uncertain, avoid inventing it; use a neutral diagram, label, or coverage note instead
            - Never use vague placeholder labels such as "observe detail", "bees present", "important part", "key detail", "main object", "thing", or "area".
            - This label rule applies to every subject. Split combined output such as "term A term B term C" into separate array items when they are distinct structures, stages, variables, regions, people, objects, or concepts.
            - Prefer precise curriculum names over generic words. Qualify a generic noun using the visible role or identity supported by the narration, for example a named component, actor, location, stage, input, output, axis, force, reagent, organ, or process path.
            - A labeled_image/realistic_labeled_image/diagram_overlay must contain at least one reliable atomic label and one matching placement per label. Process and comparison visuals must also include the exact stage, role, or side labels needed to interpret them. If reliable visible targets cannot be identified, use a plain realistic image or short_motion_clip instead; formula rows remain deterministic Manim output.
            - Decide whether labels teach necessary spatial information. Labels are normally required for anatomy/parts, apparatus, maps, definitions based on named parts, mechanisms with visible targets, and adaptations tied to visible structures. Labels are normally unnecessary for title cards, mood/background images, broad concept photos, recap images, transitions, and simple documentary photos.
            - A named visible adaptation, positional difference, developmental phase, timing contrast, or mechanism must use a stable labeled still or deterministic labeled diagram, never moving Wan/LTX footage. Split crowded concepts into focused rows.
            - If labels are needed, use template=labeled_image, visualType=realistic_labeled_image, and mediaType=photo_with_labels. If labels are not needed, labels and labelPlacements must both be empty and use template=photo with visualType=realistic_image and mediaType=photo, or another appropriate non-labeled specialized type.
            - Labels must be exact curriculum nouns visible in the background asset, not general instructions to the viewer.
            - Every label must have a matching labelPlacements entry naming the same label and an unambiguous visible target. Never point a structure label to an animal, background, or approximate area.
            - Coordinate examples describe the required format only. Choose coordinates from the requested composition; do not copy coordinates from another subject or frame.
            - For every labeled still, provide an exact semantic target description and target_xy: AUTO_VERIFY. The renderer resolves the anchor only after the final image exists.
            - Numeric normalized coordinates may appear only when assetPath identifies a locked reviewed asset. Never guess coordinates for generated imagery.
            - If the exact target will not be reliably visible, remove that label or change to a reviewed still/diagram where it is visible.
            - Choose the number of labels from the topic, lesson requirement, and visible structures in that frame. Do not impose a fixed label count.
            - If all required labels cannot remain readable without overlap, divide the concept into additional focused frames; do not omit required curriculum labels merely to meet an arbitrary count.
            - When one narration sentence names several distinct structures, examples, phases, or mechanisms, use stable split-screen/process panels or a sequence of deterministic stills. Do not combine them into one moving LTX/Wan shot with precise labels.
            - labelStyle must keep labels and arrows clear of the subject, heading, and subtitle safe areas.
            - For labeled stills, motion must be arrow_draw_then_label_fade. Reveal labels in list order, keep earlier labels visible, and hold the completed frame for at least 2.5 seconds.
            - Core renderer rule: never ask AI image/video models to create exact text, labels, arrows, formulas, legends, numbers, or scientific names inside the generated image/video.
            - Put all exact text, labels, arrows, legends, highlights, formulas, and subtitles in overlay fields so Pillow/OpenCV/Manim/FFmpeg can draw them precisely.
            - comfyPrompt and shot prompts must request clean backgrounds with no text, no labels, no captions, no watermarks, and no formula text.
            - Every still-image prompt must request sharp 1920x1080 educational photography, subject-accurate structures, realistic natural lighting, clear subject separation, sufficient empty overlay margins, no embedded text, no generated labels, no generated arrows, no captions, no watermark, and no slide or presentation-card layout.
            - Use title_card exactly once, for the first lesson shot only. Every later row must use a content visual.
            - Use labeled_image for apparatus/anatomy/parts; labels/arrows are overlays, not generated in the image.
            - Use process for one-by-one steps; steps are renderer text overlays.
            - Use comparison for self-vs-cross, strong-vs-weak, before-vs-after, or concept contrasts; columns are renderer text overlays.
            - Use formula for chemistry/physics/math formulas and derivations; tool must be manim and formulaLines must contain exact formula text.
            - Use split_screen for two related visuals; labels are overlays.
            - Use video_broll only for simple natural motion where exact labels/formulas are not required.
            - short_motion_clip/video_broll rows must have empty labels and empty labelPlacements. Put precise labels on a separate still or diagram row.
            - Tool routing: title_card/labeled_image/process/comparison/split_screen usually use pillow_opencv plus ffmpeg; formula uses manim; generated still background uses comfy_image; LTX b-roll uses ltx_video; final sharpening can add upscale in assetQualityNotes.
            - Visuals should be specific and actionable for video editors
            - Images should be descriptive enough for stock photo searches and real-HD image generation.
            - Use polished educational documentary style visuals with realistic lighting, shallow depth of field where useful, strong subject placement, and enough negative space for overlays.
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
            - Do not change or rewrite the scene narration while making segment rows.
            - Include all named plants, processes, agents, plant parts, and comparisons from the sentence in the visualAnimation, localAnimation, labels, imageRecommendations, or coverageNotes.
            - If and only if the topic is pollination, use exact labels only when relevant and visible: anther, stigma, pollen grains, filament, style, ovary, nectar guide, pollinator, pollen transfer path.
            - Pollination label coverage by concept, only when that concept is present: sunflower anatomy = ray florets, disc florets, anther, stigma, pollen grains; cleistogamy = closed flower, self-pollination, no pollinator required; cross-pollination = flower 1, flower 2, pollinator, pollen grains, pollen transfer path; heterostyly = long style, short style, anther position, stigma position; herkogamy = anther, stigma, physical separation; dichogamy = male phase, female phase, time separation; insect pollination = nectar guide, pollinator, pollen grains; wind pollination = light pollen, feathery stigma, wind direction; water pollination = floating pollen, water surface, female flower.
            - These pollination labels are conditional guidance, not a checklist for every frame. Never add a label unless its target is present and clearly visible in that specific image requirement.
            - For pollination title cards, use a strong real macro flower/pollinator background and renderer-drawn title "Types of Pollination".
            - For pollination comparison, use columns for self-pollination and cross-pollination instead of many weak cards.
            - For pollination adaptations, cover homogamy, cleistogamy, dichogamy, herkogamy, and heterostyly as overlay terms/process labels only if present in narration.
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
            """.formatted(languageInstruction, buildAnimationModeInstruction(),
                buildVideoProviderInstruction(), buildCurriculumEnrichmentInstruction(),
                PRO_LABEL_STYLE, PRO_SUBTITLE_STYLE,
                topicCheck.subject(), topicCheck.inferredTopic(), topicCheck.smeRole(),
                scene.getSceneTitle(), scene.getNarration());
        
        String response = ollama.generateStructured(
            "You are " + topicCheck.smeRole()
                + " and a professional educational storyboard director.",
            prompt, SEGMENTS_SCHEMA
        );
        
        List<SceneSegment> segments = normalizeSegments(parseSegments(response), scene.getNarration());
        scene.setSegments(segments);
    }
    
    List<Scene> parseScenes(String jsonResponse, List<String> sourceSentences) {
        List<Scene> scenes = new ArrayList<>();
        try {
            JsonArray arr = JsonParser.parseString(jsonResponse).getAsJsonArray();
            int expectedSentence = 1;
            for (int i = 0; i < arr.size(); i++) {
                JsonObject obj = arr.get(i).getAsJsonObject();
                List<Integer> sentenceNumbers = getIntList(obj, "sentenceNumbers");
                if (sentenceNumbers.isEmpty()) {
                    throw new IllegalArgumentException("Scene has no source sentence numbers");
                }
                StringBuilder narration = new StringBuilder();
                for (int sentenceNumber : sentenceNumbers) {
                    if (sentenceNumber != expectedSentence
                            || sentenceNumber < 1 || sentenceNumber > sourceSentences.size()) {
                        throw new IllegalArgumentException("Scene sentence numbers must cover the source once in order");
                    }
                    if (!narration.isEmpty()) narration.append(' ');
                    narration.append(sourceSentences.get(sentenceNumber - 1));
                    expectedSentence++;
                }
                Scene scene = new Scene();
                scene.setSceneNumber(getIntOrDefault(obj, "sceneNumber", i + 1));
                scene.setSceneTitle(getStringOrDefault(obj, "sceneTitle", "Scene " + (i + 1)));
                scene.setNarration(narration.toString());
                scenes.add(scene);
            }
            if (expectedSentence != sourceSentences.size() + 1) {
                throw new IllegalArgumentException("Scene plan omitted source sentences");
            }
        } catch (Exception e) {
            logger.warn("Invalid source-indexed scene plan; using deterministic grouping: {}", e.getMessage());
            scenes = buildDeterministicScenes(sourceSentences);
        }
        return scenes;
    }

    private List<Integer> getIntList(JsonObject obj, String field) {
        List<Integer> values = new ArrayList<>();
        if (obj == null || !obj.has(field) || !obj.get(field).isJsonArray()) return values;
        for (var element : obj.getAsJsonArray(field)) {
            values.add(element.getAsInt());
        }
        return values;
    }

    private List<Scene> buildDeterministicScenes(List<String> sourceSentences) {
        List<Scene> scenes = new ArrayList<>();
        if (sourceSentences.isEmpty()) return scenes;
        int targetScenes = Math.min(8, Math.max(1, (int) Math.ceil(sourceSentences.size() / 2.0)));
        int start = 0;
        for (int i = 0; i < targetScenes; i++) {
            int remainingSentences = sourceSentences.size() - start;
            int remainingScenes = targetScenes - i;
            int count = (int) Math.ceil(remainingSentences / (double) remainingScenes);
            int end = Math.min(sourceSentences.size(), start + count);
            Scene scene = new Scene();
            scene.setSceneNumber(i + 1);
            scene.setSceneTitle(buildHeading(sourceSentences.get(start)));
            scene.setNarration(String.join(" ", sourceSentences.subList(start, end)));
            scenes.add(scene);
            start = end;
        }
        return scenes;
    }

    private void normalizeScenes(List<Scene> scenes, String sourceText, String storyboardTitle) {
        for (int i = 0; i < scenes.size(); i++) {
            Scene scene = scenes.get(i);
            scene.setSceneNumber(i + 1);
            if (scene.getSceneTitle() == null || scene.getSceneTitle().isBlank()) {
                scene.setSceneTitle("Scene " + (i + 1));
            }
            if (scene.getNarration() == null) {
                scene.setNarration("");
            }
            boolean transcriptScene = isSupportedBySource(scene.getNarration(), sourceText);
            scene.setSegments(normalizeSegments(scene.getSegments(), scene.getNarration()));
            correctKnownStoryboardTerminology(scene);
            normalizeTitleCards(scene, i == 0 ? storyboardTitle : scene.getSceneTitle(), i == 0);
            tagSceneSource(scene, transcriptScene);
        }
    }

    private void normalizeTitleCards(Scene scene, String displayTitle, boolean allowOpeningTitleCard) {
        if (scene.getSegments() == null || scene.getSegments().isEmpty()) {
            return;
        }
        for (int i = 0; i < scene.getSegments().size(); i++) {
            SceneSegment segment = scene.getSegments().get(i);
            if ("title_card".equals(segment.getTemplate()) && (!allowOpeningTitleCard || i > 0)) {
                boolean hasLabels = segment.getLabels() != null && !segment.getLabels().isEmpty();
                segment.setTemplate(hasLabels ? "labeled_image" : "photo");
                segment.setVisualType(hasLabels ? "realistic_labeled_image" : "realistic_image");
                segment.setMediaType(hasLabels ? "photo_with_labels" : "photo");
                segment.setMotionType("static_image");
                segment.setCoverageNotes(appendNote(segment.getCoverageNotes(),
                    "Repeated-title guard: render this concept as a content visual, not another presentation title card."));
            }
        }
        SceneSegment first = scene.getSegments().get(0);
        if (!allowOpeningTitleCard) return;
        first.setTemplate("title_card");
        first.setMediaType("photo");
        first.setMotionType("static_image");
        first.setShot(null);
        first.setLtxShot(null);
        String title = cleanStoryboardTitle(displayTitle);
        if (!title.isBlank()) {
            first.setHeading(title);
        }
        first.setVisualType("title_card");
        first.setLabels(List.of());
        first.setLabelPlacements(List.of());
        first.setArrows(List.of());
        first.setHighlights(List.of());
        first.setVisualSubject(buildTitleBackgroundSubject(title));
        first.setComfyPrompt(ensureNoTextPrompt(first.getVisualSubject()));
        first.setMotion("camera: slow_zoom_in; title_animation: fade_up; transition_in: fade; transition_out: crossfade");
        first.setLocalAnimation("Renderer draws only the heading/subtitle/narration as text. Use visualSubject only as background search/generation guidance; do not display visualSubject text on screen.");
        first.setCoverageNotes(appendNote(first.getCoverageNotes(),
            "Title-card guard: visualSubject is background guidance only and must not be rendered as visible text."));
    }

    private boolean isRecapOrConclusion(SceneSegment segment, Scene scene) {
        String text = joinForFactCheck(segment.getHeading(), segment.getSentence(),
            scene.getSceneTitle(), scene.getNarration());
        return containsAnyIgnoreCase(text, "summary", "recap", "conclusion", "review", "key points");
    }

    void correctKnownStoryboardTerminology(Scene scene) {
        scene.setSceneTitle(normalizeProductionText(scene.getSceneTitle()));
        scene.setNarration(normalizeProductionText(scene.getNarration()));
        if (scene.getSegments() == null) return;
        for (SceneSegment segment : scene.getSegments()) {
            segment.setSentence(normalizeProductionText(segment.getSentence()));
            segment.setHeading(normalizeProductionText(segment.getHeading()));
            segment.setVisualSubject(normalizeProductionText(segment.getVisualSubject()));
            segment.setVisualAnimation(normalizeProductionText(segment.getVisualAnimation()));
            segment.setLocalAnimation(normalizeProductionText(segment.getLocalAnimation()));
            segment.setComfyPrompt(normalizeProductionText(segment.getComfyPrompt()));
            segment.setCoverageNotes(normalizeProductionText(segment.getCoverageNotes()));
            segment.setSubtitle(normalizeProductionText(segment.getSubtitle()));
            segment.setImageRecommendations(correctKnownTerms(segment.getImageRecommendations()));
            segment.setLabels(correctKnownTerms(segment.getLabels()));
            segment.setLabelPlacements(correctKnownTerms(segment.getLabelPlacements()));
            correctKnownTerms(segment.getShot());
            correctKnownTerms(segment.getLtxShot());
        }
    }

    private String correctKnownTerm(String value) {
        return normalizeProductionText(value);
    }

    static String normalizeProductionText(String value) {
        if (value == null) return null;
        return value
            .replace('\u2018', '\'').replace('\u2019', '\'').replace('\u02BC', '\'')
            .replace('\u201C', '"').replace('\u201D', '"')
            .replace('\u2013', '-').replace('\u2014', '-')
            .replace('\u2026', '.').replace('\u00A0', ' ')
            .replace('\uFFFD', '\'');
    }

    private List<String> correctKnownTerms(List<String> values) {
        if (values == null) return null;
        return values.stream().map(this::correctKnownTerm).toList();
    }

    private void correctKnownTerms(Shot shot) {
        if (shot == null) return;
        shot.setHeading(correctKnownTerm(shot.getHeading()));
        shot.setPrompt(correctKnownTerm(shot.getPrompt()));
        shot.setNegativePrompt(correctKnownTerm(shot.getNegativePrompt()));
        shot.setJoinInstructions(correctKnownTerm(shot.getJoinInstructions()));
    }

    private String buildTitleBackgroundSubject(String title) {
        if (title == null || title.isBlank()) {
            return "Clean real HD educational background related to the lesson topic, no embedded text";
        }
        return "Sharp 1920x1080 realistic educational background directly representing " + title
            + ", subject-matter accurate, natural lighting, clear subject separation, empty central margins "
            + "for the renderer title, no embedded text";
    }

    private void tagSceneSource(Scene scene, boolean transcriptScene) {
        if (scene.getSegments() == null) {
            return;
        }
        String sourceNote = transcriptScene
            ? "Source: transcript/paraphrase."
            : "Source: curriculum enrichment; this may not appear verbatim in the source video.";
        for (SceneSegment segment : scene.getSegments()) {
            boolean transcriptSegment = isSupportedBySource(segment.getSentence(), scene.getNarration());
            String note = transcriptScene && transcriptSegment
                ? "Source: transcript/paraphrase."
                : sourceNote;
            segment.setCoverageNotes(prependNote(segment.getCoverageNotes(), note));
        }
    }

    private boolean isSupportedBySource(String candidate, String source) {
        if (candidate == null || candidate.isBlank() || source == null || source.isBlank()) {
            return false;
        }
        String candidateKey = normalizeForDuplicateKey(candidate);
        String sourceKey = normalizeForDuplicateKey(source);
        if (candidateKey.isBlank() || sourceKey.isBlank()) {
            return false;
        }
        if (sourceKey.contains(candidateKey)) {
            return true;
        }
        return hasMeaningfulOverlap(candidate, source);
    }

    List<SceneSegment> normalizeSegments(List<SceneSegment> segments, String sceneNarration) {
        List<String> sourceSentences = splitSentences(sceneNarration);
        if (sourceSentences.isEmpty()) return List.of();
        List<SceneSegment> candidates = segments == null ? new ArrayList<>() : new ArrayList<>(segments);
        Set<String> usedKeys = new LinkedHashSet<>();
        List<SceneSegment> cleaned = new ArrayList<>();

        for (SceneSegment segment : candidates) {
            if (segment == null) {
                continue;
            }
            repairSegmentSentence(segment, sourceSentences, usedKeys);
            segment.setLabels(sanitizeLabels(segment.getLabels(), segment));
        }

        for (String sourceSentence : sourceSentences) {
            String sourceKey = normalizeForDuplicateKey(sourceSentence);
            SceneSegment match = null;
            for (SceneSegment candidate : candidates) {
                if (candidate == null || usedKeys.contains(normalizeForDuplicateKey(candidate.getSentence()))) continue;
                if (sourceKey.equals(normalizeForDuplicateKey(candidate.getSentence()))) {
                    match = candidate;
                    break;
                }
            }
            if (match == null) match = createSourceFallbackSegment(sourceSentence);
            match.setSentence(sourceSentence);
            usedKeys.add(sourceKey);
            cleaned.add(match);
        }
        for (int i = 0; i < cleaned.size(); i++) {
            cleaned.get(i).setSegmentNumber(i + 1);
        }
        return cleaned;
    }

    private SceneSegment createSourceFallbackSegment(String sentence) {
        SceneSegment segment = new SceneSegment();
        List<String> labels = extractFallbackLabels(sentence);
        boolean labeled = !labels.isEmpty();
        segment.setSentence(sentence);
        segment.setTemplate(labeled ? "labeled_image" : "photo");
        segment.setVisualType(labeled ? "realistic_labeled_image" : "realistic_image");
        segment.setHeading(buildHeading(sentence));
        segment.setVisualSubject("Sharp 1920x1080 curriculum-accurate visual directly illustrating: " + sentence);
        segment.setAssetPath("");
        segment.setMediaType(labeled ? "photo_with_labels" : "photo");
        segment.setMotionType("static_image");
        segment.setEstimatedNarrationSeconds(estimateNarrationSeconds(sentence));
        segment.setRecommendedClipSeconds(segment.getEstimatedNarrationSeconds());
        segment.setTimingNotes("Hold the visual for the full narration sentence with a subtle slow zoom.");
        segment.setVisualAnimation("Show the exact narrated concept with one clear, topic-specific composition.");
        segment.setLocalAnimation("Use only renderer-controlled overlays supported by the narration.");
        segment.setLabels(labels);
        segment.setLabelPlacements(List.of());
        segment.setLabelStyle(defaultLabelStyle());
        segment.setArrows(List.of());
        segment.setHighlights(List.of());
        segment.setFormulaLines(List.of());
        segment.setExplainSteps(List.of());
        segment.setSteps(List.of());
        segment.setColumns(List.of());
        segment.setImageRecommendations(List.of("Sharp curriculum-accurate real HD visual for: " + sentence));
        segment.setMotion(defaultMotion(segment.getTemplate()));
        segment.setSubtitle(buildSubtitle(sentence));
        segment.setSubtitleStyle(PRO_SUBTITLE_STYLE);
        segment.setTool("pillow_opencv");
        segment.setAssetQualityNotes("Fallback source-coverage visual; use a reviewed asset when precise anatomy or apparatus is required.");
        segment.setComfyPrompt(ensureNoTextPrompt("Sharp 1920x1080 realistic educational image directly matching: " + sentence));
        segment.setCoverageNotes("Source-coverage guard: generated because the LLM omitted this narration sentence.");
        return segment;
    }

    private void repairSegmentSentence(SceneSegment segment, List<String> sourceSentences, Set<String> usedKeys) {
        String sentence = segment.getSentence() != null ? segment.getSentence().trim() : "";
        if (sourceSentences.isEmpty()) {
            return;
        }
        if (containsExactSentence(sourceSentences, sentence)) {
            return;
        }
        for (String sourceSentence : sourceSentences) {
            String sourceKey = normalizeForDuplicateKey(sourceSentence);
            if (usedKeys.contains(sourceKey)) {
                continue;
            }
            if (sentence.isBlank()
                    || containsIgnoreCase(sourceSentence, sentence)
                    || containsIgnoreCase(sentence, sourceSentence)
                    || hasMeaningfulOverlap(sentence, sourceSentence)) {
                segment.setSentence(sourceSentence);
                if (segment.getHeading() == null || segment.getHeading().isBlank()
                        || containsIgnoreCase(sentence, segment.getHeading())) {
                    segment.setHeading(buildHeading(sourceSentence));
                }
                return;
            }
        }
    }

    private List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return sentences;
        }
        String normalized = text.replaceAll("[\\r\\n]+", " ").replaceAll("\\s+", " ").trim();
        String[] parts = normalized.split("(?<=[.!?।])\\s+");
        for (String part : parts) {
            String sentence = part.trim();
            if (!sentence.isBlank()) {
                sentences.add(sentence);
            }
        }
        if (sentences.isEmpty()) {
            sentences.add(normalized);
        }
        return sentences;
    }

    private boolean containsExactSentence(List<String> sourceSentences, String sentence) {
        for (String sourceSentence : sourceSentences) {
            if (sourceSentence.equals(sentence)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasMeaningfulOverlap(String a, String b) {
        String[] words = normalizeForDuplicateKey(a).split(" ");
        int matches = 0;
        String normalizedB = " " + normalizeForDuplicateKey(b) + " ";
        for (String word : words) {
            if (word.length() >= 5 && normalizedB.contains(" " + word + " ")) {
                matches++;
            }
        }
        return matches >= 3;
    }

    String normalizeForDuplicateKey(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}]+", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }
    
    private List<SceneSegment> parseSegments(String jsonResponse) {
        List<SceneSegment> segments = new ArrayList<>();
        try {
            JsonArray arr = JsonParser.parseString(jsonResponse).getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                JsonObject obj = arr.get(i).getAsJsonObject();
                SceneSegment seg = new SceneSegment();
                seg.setSegmentNumber(getIntOrDefault(obj, "segmentNumber", i + 1));
                seg.setSentence(getStringOrDefault(obj, "sentence", ""));
                seg.setTemplate(getStringOrDefault(obj, "template", "labeled_image"));
                seg.setVisualType(getStringOrDefault(obj, "visualType", ""));
                seg.setHeading(getStringOrDefault(obj, "heading", ""));
                seg.setVisualSubject(getStringOrDefault(obj, "visualSubject", ""));
                seg.setAssetPath(getStringOrDefault(obj, "assetPath", ""));
                seg.setMediaType(getStringOrDefault(obj, "mediaType", "animation_with_labels"));
                seg.setMotionType(getStringOrDefault(obj, "motionType", "local_animation"));
                seg.setEstimatedNarrationSeconds(getDoubleOrDefault(obj, "estimatedNarrationSeconds", estimateNarrationSeconds(seg.getSentence())));
                seg.setRecommendedClipSeconds(getDoubleOrDefault(obj, "recommendedClipSeconds", seg.getEstimatedNarrationSeconds()));
                seg.setTimingNotes(getStringOrDefault(obj, "timingNotes", ""));
                seg.setVisualAnimation(getStringOrDefault(obj, "visualAnimation", buildVisualSubject(seg)));
                seg.setLocalAnimation(getStringOrDefault(obj, "localAnimation", ""));

                seg.setLabels(getStringList(obj, "labels"));
                seg.setLabelPlacements(getStringList(obj, "labelPlacements"));
                seg.setLabelStyle(getStringOrDefault(obj, "labelStyle", ""));
                seg.setArrows(getStringList(obj, "arrows"));
                seg.setHighlights(getStringList(obj, "highlights"));
                seg.setFormulaLines(getStringList(obj, "formulaLines"));
                seg.setExplainSteps(getStringList(obj, "explainSteps"));
                seg.setSteps(getStringList(obj, "steps"));
                seg.setColumns(getStringList(obj, "columns"));
                seg.setImageRecommendations(getStringList(obj, "imageRecommendations"));
                seg.setMotion(getStringOrDefault(obj, "motion", ""));
                seg.setSubtitle(getStringOrDefault(obj, "subtitle", ""));
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
            fallback.setVisualType("process_steps");
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
            fallback.setLabelPlacements(List.of());
            fallback.setLabelStyle(defaultLabelStyle());
            fallback.setArrows(List.of());
            fallback.setHighlights(List.of());
            fallback.setFormulaLines(List.of());
            fallback.setExplainSteps(List.of());
            fallback.setSteps(List.of("Show the main concept", "Reveal supporting details", "Hold for narration"));
            fallback.setColumns(List.of());
            fallback.setImageRecommendations(List.of("Image 1: Educational illustration", "Image 2: Related concept diagram"));
            fallback.setMotion("camera: static; reveal: one_by_one; transition_in: fade; transition_out: crossfade");
            fallback.setSubtitle("Main lesson concept");
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
        enforceFormulaRouting(segment);
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
        enforceGeneratedVideoOverlayRules(segment);
        enforceAnimationMode(segment);
        // Formula rendering is deterministic and must survive animation/provider conversion.
        enforceFormulaRouting(segment);
        // Animation mode can convert a still/diagram into Wan/LTX after the first guard.
        enforceGeneratedVideoOverlayRules(segment);
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

        enforceElectroplatingScience(segment);

        if (segment.getCoverageNotes() == null || segment.getCoverageNotes().isBlank()) {
            segment.setCoverageNotes("Covers the narration sentence with matching visuals, labels, and image recommendations.");
        }
        segment.setLabels(sanitizeLabels(segment.getLabels(), segment));
        enforceLabeledVisualQuality(segment);
        enforceLabelContract(segment);
    }

    private void enforceLabeledVisualQuality(SceneSegment segment) {
        if (!isLabeledVisual(segment)) {
            return;
        }
        List<String> labels = segment.getLabels() == null ? List.of() : segment.getLabels();
        if (!labels.isEmpty()) {
            return;
        }

        List<String> derived = sanitizeLabels(extractFallbackLabels(segment.getSentence()), segment);
        if (!derived.isEmpty()) {
            segment.setLabels(derived);
            segment.setCoverageNotes(appendNote(segment.getCoverageNotes(),
                "Label-quality guard: derived atomic labels from the narrated concept."));
            return;
        }

        // A later repair pass gets one opportunity to produce a complete label plan.
    }

    private boolean isLabeledVisual(SceneSegment segment) {
        return "labeled_image".equals(segment.getTemplate())
            || "photo_with_labels".equals(segment.getMediaType())
            || "animation_with_labels".equals(segment.getMediaType())
            || "realistic_labeled_image".equals(segment.getVisualType())
            || "realistic_background_with_labels".equals(segment.getVisualType())
            || "diagram_overlay".equals(segment.getVisualType());
    }

    private void enforceGeneratedVideoOverlayRules(SceneSegment segment) {
        boolean generatedVideo = "video_broll".equals(segment.getTemplate())
            || "wan_video".equals(segment.getMediaType())
            || "wan_video".equals(segment.getMotionType())
            || segment.getShot() != null
            || segment.getLtxShot() != null;
        if (!generatedVideo) {
            return;
        }
        segment.setVisualType("short_motion_clip");
        segment.setLabels(List.of());
        segment.setLabelPlacements(List.of());
        segment.setArrows(List.of());
        segment.setHighlights(List.of());
        segment.setCoverageNotes(appendNote(segment.getCoverageNotes(),
            "Generated-video guard: do not draw exact labels/arrows on this moving shot; use subtitles only or cut to a separate labeled still/diagram."));
    }

    private void enforceTemplateToolRules(SceneSegment segment) {
        if ("formula".equals(segment.getTemplate())) {
            segment.setTool("manim");
            segment.setMotionType("local_animation");
            segment.setMediaType("animation");
            segment.setVisualType("process_steps");
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
        return StoryboardRules.extractFormulaLines(sentence);
    }

    private void enforceFormulaRouting(SceneSegment segment) {
        if (!StoryboardRules.requiresFormulaRenderer(segment)) return;
        segment.setTemplate("formula");
        segment.setVisualType("process_steps");
        segment.setMediaType("animation");
        segment.setMotionType("local_animation");
        segment.setTool("manim");
        segment.setShot(null);
        segment.setLtxShot(null);
        segment.setLabels(List.of());
        segment.setLabelPlacements(List.of());
        segment.setArrows(List.of());
        segment.setHighlights(List.of());
        if (segment.getFormulaLines() == null || segment.getFormulaLines().isEmpty()) {
            segment.setFormulaLines(StoryboardRules.extractFormulaLines(segment.getSentence()));
        }
        segment.setLocalAnimation("Render exact formula lines with Manim; reveal symbols and substitution steps in narration order; hold the final result for readability.");
        segment.setMotion("formula_reveal: line_by_line; transition_in: fade; transition_out: crossfade");
        segment.setAssetQualityNotes(appendNote(segment.getAssetQualityNotes(),
            "Deterministic formula route: Manim draws all equations, symbols, units, and derivation steps; the generated image is background only."));
    }

    private String ensureNoTextPrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return prompt;
        }
        if (containsIgnoreCase(prompt, "1920x1080")
                && containsIgnoreCase(prompt, "no embedded text")
                && containsIgnoreCase(prompt, "no generated labels")) {
            return prompt;
        }
        return "Sharp 1920x1080 educational photography, botanically or subject-matter accurate structures, "
            + "realistic natural lighting, clear subject separation, sufficient empty margins for overlays. "
            + prompt + ". No embedded text. No generated labels. No generated arrows. No captions. "
            + "No watermark. No slide or presentation-card layout.";
    }

    private void enforceProStyleDefaults(SceneSegment segment) {
        if (segment.getTemplate() == null || segment.getTemplate().isBlank()) {
            segment.setTemplate(inferTemplate(segment));
        }
        if (segment.getVisualType() == null || segment.getVisualType().isBlank()) {
            segment.setVisualType(inferVisualType(segment));
        }
        segment.setVisualType(normalizeVisualType(segment));
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
        segment.setLabels(sanitizeLabels(segment.getLabels(), segment));
        if (segment.getLabelPlacements() == null) segment.setLabelPlacements(List.of());
        if (segment.getLabelStyle() == null || segment.getLabelStyle().isBlank()) {
            segment.setLabelStyle(defaultLabelStyle());
        }
        if (segment.getArrows() == null) segment.setArrows(List.of());
        if (segment.getHighlights() == null) segment.setHighlights(List.of());
        if (segment.getFormulaLines() == null) segment.setFormulaLines(List.of());
        if (segment.getExplainSteps() == null) segment.setExplainSteps(List.of());
        if (segment.getSteps() == null) segment.setSteps(List.of());
        if (segment.getColumns() == null) segment.setColumns(List.of());
        if (segment.getMotion() == null || segment.getMotion().isBlank()) {
            segment.setMotion(defaultMotion(segment.getTemplate()));
        }
        if (segment.getSubtitle() == null || segment.getSubtitle().isBlank()) {
            segment.setSubtitle(buildSubtitle(segment.getSentence()));
        }
        segment.setSubtitleStyle(PRO_SUBTITLE_STYLE);
        if (segment.getTool() == null || segment.getTool().isBlank()) {
            segment.setTool(inferTool(segment));
        }
        if (segment.getAssetQualityNotes() == null || segment.getAssetQualityNotes().isBlank()) {
            segment.setAssetQualityNotes(defaultAssetQualityNotes(segment));
        }
    }

    private String normalizeVisualType(SceneSegment segment) {
        String value = segment.getVisualType() == null ? "" : segment.getVisualType().trim();
        return switch (value) {
            case "title_card", "realistic_image", "realistic_labeled_image",
                 "realistic_background_with_labels", "diagram_overlay", "process_steps",
                 "short_motion_clip" -> value;
            case "split_screen_comparison", "comparison" -> "diagram_overlay";
            case "formula/derivation" -> "process_steps";
            default -> inferVisualType(segment);
        };
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
        return "photo";
    }

    private String inferVisualType(SceneSegment segment) {
        return switch (segment.getTemplate()) {
            case "title_card" -> "title_card";
            case "photo" -> "realistic_image";
            case "comparison", "split_screen" -> "diagram_overlay";
            case "process" -> "process_steps";
            case "formula" -> "process_steps";
            case "video_broll" -> "short_motion_clip";
            case "labeled_image" -> "diagram".equals(segment.getMediaType())
                ? "diagram_overlay" : "realistic_labeled_image";
            default -> segment.getLabels() != null && !segment.getLabels().isEmpty()
                ? "realistic_background_with_labels" : "realistic_labeled_image";
        };
    }

    private String defaultLabelStyle() {
        return PRO_LABEL_STYLE;
    }

    private String buildSubtitle(String sentence) {
        if (sentence == null) {
            return "";
        }
        String clean = sentence.replaceAll("[\\r\\n]+", " ").replaceAll("\\s+", " ").trim();
        return clean;
    }

    private void enforceLabelContract(SceneSegment segment) {
        if ("short_motion_clip".equals(segment.getVisualType())
                || "video_broll".equals(segment.getTemplate())) {
            segment.setLabels(List.of());
            segment.setLabelPlacements(List.of());
            String motion = segment.getMotion();
            if (motion == null || motion.isBlank()
                    || "static".equalsIgnoreCase(motion.trim())
                    || "static_image".equalsIgnoreCase(motion.trim())
                    || containsLabelOnlyMotion(motion)) {
                segment.setMotion("natural_motion_only; camera: locked_or_gentle_tracking; "
                    + "transition_in: fade; transition_out: crossfade; no_scientific_labels");
            }
            return;
        }

        List<String> labels = segment.getLabels() == null ? List.of() : segment.getLabels();
        List<String> supplied = segment.getLabelPlacements() == null
            ? List.of() : segment.getLabelPlacements();
        List<String> placements = new ArrayList<>();
        for (String label : labels) {
            String prefix = label.toLowerCase(Locale.ROOT);
            supplied.stream()
                .filter(value -> placementMatchesLabel(value, prefix))
                .findFirst()
                .ifPresent(placement -> placements.add(normalizeLabelPlacement(segment, label, placement)));
        }
        segment.setLabelPlacements(placements);
        segment.setLabelStyle(defaultLabelStyle());
        segment.setMotion(labels.isEmpty() ? removeLabelAnimation(segment.getMotion()) : LABELED_MOTION);
        if (!labels.isEmpty()) {
            segment.setAssetQualityNotes(appendNote(segment.getAssetQualityNotes(),
                "Resolve AUTO_VERIFY anchors after the final image is generated or loaded. Draw an arrow only "
                    + "after its named semantic target is verified; otherwise skip the unsafe arrow."));
        }
    }

    private String normalizeLabelPlacement(SceneSegment segment, String label, String placement) {
        String value = placement == null ? "" : placement.trim();
        String normalizedNumber = "(?:0(?:\\.\\d+)?|1(?:\\.0+)?)";
        String description = extractTargetDescription(value, label);
        java.util.regex.Matcher coordinate = java.util.regex.Pattern
            .compile("(?:target=\\(|target_xy:\\s*)(" + normalizedNumber + ")\\s*,\\s*(" + normalizedNumber + ")\\)?",
                java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher(value);
        boolean lockedAsset = segment.getAssetPath() != null && !segment.getAssetPath().isBlank();
        String target = lockedAsset && coordinate.find()
            ? coordinate.group(1) + "," + coordinate.group(2)
            : "AUTO_VERIFY";
        return label + " | " + description + " | target_xy: " + target;
    }

    private String extractTargetDescription(String placement, String label) {
        String[] pipeParts = placement.split("\\|", -1);
        if (pipeParts.length >= 2 && pipeParts[1].trim().length() >= 8) return pipeParts[1].trim();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("(?i)target_description=([^;]+)").matcher(placement);
        if (matcher.find() && matcher.group(1).trim().length() >= 8) return matcher.group(1).trim();
        return "";
    }

    private String removeLabelAnimation(String motion) {
        if (motion == null || motion.isBlank()) return "slow_zoom_in";
        String cleaned = Arrays.stream(motion.split(";"))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .filter(value -> !containsLabelOnlyMotion(value))
            .collect(java.util.stream.Collectors.joining("; "));
        return cleaned.isBlank() ? "slow_zoom_in" : cleaned;
    }

    private boolean containsLabelOnlyMotion(String motion) {
        return containsAnyIgnoreCase(motion,
            "arrow_draw_then_label_fade", "label_reveal", "reveal_in_list_order",
            "keep_previous_labels_visible", "completed_frame_hold");
    }

    void sanitizeAssetPath(SceneSegment segment) {
        String value = segment.getAssetPath();
        if (value == null || value.isBlank()) {
            segment.setAssetPath("");
            return;
        }
        try {
            Path path = Path.of(value.trim());
            if (path.isAbsolute() && Files.isRegularFile(path)) {
                segment.setAssetPath(path.normalize().toString());
                return;
            }
        } catch (InvalidPathException ignored) {
            // Invalid model-supplied paths are handled like missing files below.
        }
        segment.setAssetPath("");
        segment.setCoverageNotes(appendNote(segment.getCoverageNotes(),
            "Asset guard: ignored an unverified or missing asset path; generate or select the visual normally."));
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
        return base + "; real HD educational background asset, strong subject placement, natural lighting, no text, no labels, no formulas, no arrows, no blank white card";
    }

    private String defaultMotion(String template) {
        return switch (template) {
            case "title_card" -> "camera: slow_zoom_in; title_animation: fade_up; transition_in: fade; transition_out: crossfade";
            case "formula" -> "formula_reveal: line_by_line; camera: static; transition_in: fade; transition_out: crossfade";
            case "comparison" -> "column_reveal: left_then_right; camera: static; transition_in: fade; transition_out: crossfade";
            case "process" -> "step_reveal: one_by_one; camera: static; transition_in: fade; transition_out: crossfade";
            case "split_screen" -> "camera: subtle_independent_zoom; transition_in: fade; transition_out: crossfade";
            case "video_broll" -> "camera: stable; transition_in: fade; transition_out: crossfade";
            case "photo" -> "camera: slow_zoom_in; transition_in: fade; transition_out: crossfade";
            default -> "camera: slow_zoom_in; overlay_sequence: arrow_draw_then_label_fade; label_reveal: one_by_one; transition_in: fade; transition_out: crossfade";
        };
    }

    private String inferTool(SceneSegment segment) {
        return switch (segment.getTemplate()) {
            case "formula" -> "manim";
            case "video_broll" -> "ltx_video";
            case "title_card", "photo", "labeled_image", "comparison", "process", "split_screen" -> "pillow_opencv";
            default -> "pillow_opencv";
        };
    }

    private String defaultAssetQualityNotes(SceneSegment segment) {
        return switch (segment.getTemplate()) {
            case "formula" -> "Use Manim for exact formula text and line-by-line reveal; do not generate formulas inside images.";
            case "video_broll" -> "Use LTX only for short natural motion; composite precise labels/subtitles separately; upscale if needed.";
            case "photo" -> "Use a sharp reviewed or generated no-text realistic image; add heading/subtitle only when required.";
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

    private List<String> sanitizeLabels(List<String> labels, SceneSegment segment) {
        List<String> cleaned = new ArrayList<>();
        if (labels != null) {
            for (String rawLabel : labels) {
                if (rawLabel == null || rawLabel.isBlank()) continue;
                for (String label : splitAtomicLabels(rawLabel, segment)) {
                    String trimmed = normalizeLabel(label.trim());
                    if (isWeakPlaceholderLabel(trimmed) || isStyleOrInstructionLabel(trimmed)) continue;
                    if (!containsLabel(cleaned, trimmed)) cleaned.add(trimmed);
                }
            }
        }
        return cleaned;
    }

    private List<String> splitAtomicLabels(String rawLabel, SceneSegment segment) {
        String raw = rawLabel.trim();
        String lower = raw.toLowerCase(Locale.ROOT);
        List<String> known = new ArrayList<>();
        addKnownLabel(known, lower, "pollen transfer path");
        addKnownLabel(known, lower.replace("pollen transfer path", ""), "pollen grains", "pollen grain", "pollen");
        addKnownLabel(known, lower, "flower 1");
        addKnownLabel(known, lower, "flower 2");
        addKnownLabel(known, lower, "bee pollinator", "bee", "pollinator");
        addKnownLabel(known, lower, "anther");
        addKnownLabel(known, lower, "stigma");
        addKnownLabel(known, lower, "filament");
        addKnownLabel(known, lower, "style");
        addKnownLabel(known, lower, "ovary");
        addKnownLabel(known, lower, "nectar guide");
        if (known.size() > 1) return known;
        if (raw.matches("(?i).*(,|;|/|\\||\\n|\\s+and\\s+|\\s+&\\s+).*")) {
            return java.util.Arrays.stream(raw.split("(?i)[,;/|\\n]+|\\s+(?:and|&)\\s+"))
                .map(String::trim).filter(value -> !value.isBlank()).toList();
        }
        List<String> contextTerms = extractFallbackLabels(joinForFactCheck(raw,
            segment.getSentence(), segment.getHeading(), segment.getVisualSubject(),
            segment.getVisualAnimation(), segment.getLocalAnimation()));
        if (contextTerms.size() > 1 && contextTerms.stream().allMatch(term -> containsIgnoreCase(raw, term)
                || ("pollen grains".equals(term) && containsIgnoreCase(raw, "pollen")))) {
            return contextTerms;
        }
        return List.of(raw);
    }

    private void addKnownLabel(List<String> labels, String text, String canonical, String... aliases) {
        if (containsIgnoreCase(text, canonical)) {
            if (!containsLabel(labels, canonical)) labels.add(canonical);
            return;
        }
        for (String alias : aliases) {
            if (containsIgnoreCase(text, alias)) {
                if (!containsLabel(labels, canonical)) labels.add(canonical);
                return;
            }
        }
    }

    private boolean isStyleOrInstructionLabel(String label) {
        return containsAnyIgnoreCase(label,
            "endpoint must touch", "yellow", "readable at", "no overlap", "dark outline",
            "white label box", "heading", "subtitles", "left side", "right side",
            "upper part", "lower part", "arrow to", "target=", "box=");
    }

    private String normalizeLabel(String label) {
        String normalized = label.toLowerCase(Locale.ROOT).trim();
        return switch (normalized) {
            case "pollen", "pollen grain" -> "pollen grains";
            case "stigma" -> "stigma";
            case "anther" -> "anther";
            case "filament" -> "filament";
            case "style" -> "style";
            case "ovary" -> "ovary";
            case "nectar", "nectar guides" -> "nectar guide";
            case "bee", "bees", "insect", "insects", "bird", "birds", "bat", "bats", "animal", "animals" -> "pollinator";
            case "transfer path", "pollen path", "pollen transfer" -> "pollen transfer path";
            default -> label.trim();
        };
    }

    private boolean isWeakPlaceholderLabel(String label) {
        String normalized = label.toLowerCase(java.util.Locale.ROOT).trim();
        return normalized.equals("observe detail")
            || normalized.equals("bees present")
            || normalized.equals("important part")
            || normalized.equals("key detail")
            || normalized.equals("main object")
            || normalized.equals("area")
            || normalized.equals("thing")
            || normalized.equals("detail")
            || normalized.startsWith("observe ")
            || normalized.endsWith(" present");
    }

    private void applySubjectSpecificLabels(SceneSegment segment) {
        String combined = joinForFactCheck(
            segment.getSentence(),
            segment.getHeading(),
            segment.getVisualSubject(),
            segment.getVisualAnimation(),
            segment.getLocalAnimation(),
            segment.getCoverageNotes()
        );
        if (!containsAnyIgnoreCase(combined, "pollination", "pollen", "anther", "stigma", "flower", "pollinator")) {
            return;
        }

        List<String> exact = new ArrayList<>();
        addIfMentioned(exact, combined, "anther");
        addIfMentioned(exact, combined, "stigma");
        addIfMentioned(exact, combined, "pollen grains", "pollen");
        addIfMentioned(exact, combined, "filament");
        addIfMentioned(exact, combined, "style");
        addIfMentioned(exact, combined, "ovary");
        addIfMentioned(exact, combined, "nectar guide", "nectar");
        addIfMentioned(exact, combined, "pollinator", "bee", "insect", "bird", "bat", "animal");
        addIfMentioned(exact, combined, "pollen transfer path", "transfer");
        if (containsIgnoreCase(combined, "cross-pollination")) {
            exact = mergeLabels(exact, List.of("flower 1", "flower 2", "pollinator", "pollen grains", "pollen transfer path"));
        }
        if (!exact.isEmpty()) {
            segment.setLabels(mergeLabels(exact, List.of()));
        }
    }

    private void addIfMentioned(List<String> labels, String text, String label, String... triggers) {
        if (containsIgnoreCase(text, label)) {
            if (!containsLabel(labels, label)) {
                labels.add(label);
            }
            return;
        }
        for (String trigger : triggers) {
            if (containsIgnoreCase(text, trigger)) {
                if (!containsLabel(labels, label)) {
                    labels.add(label);
                }
                return;
            }
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
        addIfMentioned(labels, sentence, "anther");
        addIfMentioned(labels, sentence, "stigma");
        addIfMentioned(labels, sentence, "pollen grains", "pollen");
        addIfMentioned(labels, sentence, "filament");
        addIfMentioned(labels, sentence, "style");
        addIfMentioned(labels, sentence, "ovary");
        addIfMentioned(labels, sentence, "nectar guide", "nectar");
        addIfMentioned(labels, sentence, "pollinator", "pollinator", "bee", "insect", "bird", "bat", "animal");
        addIfMentioned(labels, sentence, "pollen transfer path", "transfer");
        addIfMentioned(labels, sentence, "cathode");
        addIfMentioned(labels, sentence, "anode");
        addIfMentioned(labels, sentence, "electrolyte");
        addIfMentioned(labels, sentence, "Ag+", "silver ion", "silver ions");
        addIfMentioned(labels, sentence, "NO3-", "nitrate");
        return labels;
    }

    private String buildCurriculumEnrichmentInstruction() {
        if (curriculumEnrichmentEnabled) {
            return """
                Curriculum enrichment mode is ENABLED.
                Create a teacher-style storyboard, not only a literal transcript storyboard.
                Keep the original paraphrased narration ideas, but you may add concise,
                curriculum-standard bridge scenes and definitions that a subject teacher
                would naturally include for completeness. Use only safe textbook facts
                directly related to the same topic. Mark these additions in coverageNotes
                as curriculum enrichment. Do not add unrelated examples or examples from
                other videos.
                For pollination, a complete lesson may include definition, self-pollination,
                cross-pollination, autogamy, geitonogamy, agents such as wind/water/insects/
                birds/bats/animals, adaptations, comparison, and summary when suitable.
                """;
        }
        return """
            Curriculum enrichment mode is DISABLED.
            Make a transcript-only storyboard. Do not add curriculum facts, examples,
            scene topics, or definitions that are absent from the paraphrased narration.
            """;
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
        return "Realistic HD educational background image, " + base
            + ", strong subject placement, natural lighting, sharp detail, documentary style, accurate subject, shallow depth of field where useful, clear negative space for later overlay labels, no blank cards, no slide layout, no white empty panel, no text-heavy graphic panels, no embedded text, no generated labels, no generated arrows";
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

    private String prependNote(String current, String note) {
        if (current == null || current.isBlank()) {
            return note;
        }
        if (current.contains(note)) {
            return current;
        }
        return note + " " + current;
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
        return "Realistic HD educational background image, " + base
            + ", accurate subject matter, strong composition, natural lighting, high clarity, suitable for classroom video, clear negative space for renderer overlays, no blank slide, no white card, no embedded text, no generated labels, no generated arrows";
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

    private boolean getBooleanOrDefault(JsonObject obj, String memberName, boolean fallback) {
        if (obj.has(memberName) && !obj.get(memberName).isJsonNull()) {
            try {
                return obj.get(memberName).getAsBoolean();
            } catch (Exception ignored) {
                return fallback;
            }
        }
        return fallback;
    }
    
    private String extractTitle(String text, String baseName) {
        String fileTitle = titleFromBaseName(baseName);
        if (!fileTitle.isBlank()) {
            return fileTitle;
        }
        int end = text.indexOf('.');
        if (end > 10 && end < 100) {
            return text.substring(0, end);
        }
        return text.substring(0, Math.min(50, text.length())) + "...";
    }

    private String titleFromBaseName(String baseName) {
        if (baseName == null || baseName.isBlank()) {
            return "";
        }
        String title = baseName.replaceAll("(?i)_storyboard$", "")
            .replaceAll("(?i)_paraphrased$", "")
            .replaceAll("(?i)_transcript$", "")
            .replaceAll("^[0-9]+[-_\\s]+", "")
            .replace('-', ' ')
            .replace('_', ' ')
            .replaceAll("\\s+", " ")
            .trim();
        if (title.matches(".*[A-Za-z].*")) {
            return title;
        }
        return "";
    }

    private String cleanStoryboardTitle(String title) {
        if (title == null) {
            return "";
        }
        String cleaned = title.replaceAll("(?i)^storyboard\\s*:\\s*", "")
            .replaceAll("\\s+", " ")
            .trim();
        if (cleaned.length() > 80) {
            cleaned = cleaned.substring(0, 80).trim();
            int lastSpace = cleaned.lastIndexOf(' ');
            if (lastSpace > 30) {
                cleaned = cleaned.substring(0, lastSpace);
            }
        }
        return cleaned;
    }

    private record TopicCheck(
        String inferredTopic,
        String filenameTopic,
        boolean topicMatch,
        double confidence,
        String safeStoryboardTitle,
        String subject,
        String smeRole,
        String warning
    ) {}

    private String inferSubjectFallback(String text) {
        String value = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (containsAnyIgnoreCase(value, "molecule", "ion", "electroly", "reaction", "acid", "base",
                "salt", "cathode", "anode", "molar", "kohlrausch")) return "Chemistry";
        if (containsAnyIgnoreCase(value, "cell membrane", "organism", "tissue", "organ", "plant",
                "flower", "pollination", "genetic", "photosynthesis", "anatomy", "ecology")) {
            return "Biology";
        }
        if (containsAnyIgnoreCase(value, "battery cell", "electric cell", "circuit", "voltage", "current", "resistance",
                "force", "energy", "motion", "wave", "lens", "electric")) return "Physics";
        if (containsAnyIgnoreCase(value, "equation", "theorem", "geometry", "algebra", "fraction",
                "probability", "calculus", "matrix")) return "Mathematics";
        if (containsAnyIgnoreCase(value, "algorithm", "software", "computer", "database", "programming",
                "network", "binary")) return "Computer Science";
        if (containsAnyIgnoreCase(value, "map", "climate", "river", "continent", "latitude",
                "longitude", "population")) return "Geography";
        if (containsAnyIgnoreCase(value, "empire", "century", "revolution", "dynasty", "civilization",
                "historical")) return "History";
        if (containsAnyIgnoreCase(value, "grammar", "noun", "verb", "poem", "literature", "language")) {
            return "Language and Literature";
        }
        if (containsAnyIgnoreCase(value, "economy", "market", "demand", "supply", "inflation", "finance")) {
            return "Economics";
        }
        return "General Education";
    }

    private String buildSmeRole(String subject, String topic) {
        String safeSubject = subject == null || subject.isBlank() ? "education" : subject.trim();
        String safeTopic = topic == null || topic.isBlank() ? "this lesson" : topic.trim();
        return safeSubject + " curriculum specialist with domain expertise in " + safeTopic;
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
