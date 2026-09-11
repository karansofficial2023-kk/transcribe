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
              "visualAnimation": {
                "type": "string"
              },
              "imageRecommendations": {
                "type": "array",
                "items": {
                  "type": "string"
                }
              }
            },
            "required": [
              "segmentNumber",
              "sentence",
              "visualAnimation",
              "imageRecommendations"
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
        
        // Step 1: Split into logical scenes
        List<Scene> scenes = splitIntoScenes(paraphrasedText);
        
        // Step 2: For each scene, generate segments with visuals and images
        for (Scene scene : scenes) {
            enrichSceneWithSegments(scene);
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
    private List<Scene> splitIntoScenes(String text) throws IOException {
        String prompt = """
            Split the following educational video transcript into logical scenes.
            Each scene should cover a distinct topic or sub-topic.
            Create 3-8 scenes depending on content length.
            
            TEXT:
n            %s
            
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
            - Do not merge unrelated topics
            """.formatted(text);
        
        String response = ollama.generateStructured(
            "You are an expert educational video script writer and storyboard creator.",
            prompt, SCENES_SCHEMA
        );
        
        return parseScenes(response);
    }
    
    /**
     * Enrich scene with sentence-level segments, visuals, and image recommendations
     */
    private void enrichSceneWithSegments(Scene scene) throws IOException {
        String prompt = """
            Break down the following scene narration into sentence-level segments.
            For each sentence, provide:
            1. The exact sentence text
            2. Visual/Animation description (what should be shown on screen)
            3. Image recommendations (2 specific image descriptions for stock photo/illustration search)
            
            SCENE TITLE: %s
            NARRATION:
            %s
            
            Respond ONLY with a JSON array:
            [
              {
                "segmentNumber": 1,
                "sentence": "exact sentence text",
                "visualAnimation": "Animation: description of what animates on screen",
                "imageRecommendations": ["Image 1: specific description", "Image 2: specific description"]
              }
            ]
            
            Rules:
            - Split by natural sentence boundaries
            - Visuals should be specific and actionable for video editors
            - Images should be descriptive enough for stock photo searches
            - Use educational documentary style visuals
            """.formatted(scene.getSceneTitle(), scene.getNarration());
        
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
                seg.setVisualAnimation(obj.get("visualAnimation").getAsString());
                
                List<String> images = new ArrayList<>();
                JsonArray imgArr = obj.getAsJsonArray("imageRecommendations");
                if (imgArr != null) {
                    imgArr.forEach(e -> images.add(e.getAsString()));
                }
                seg.setImageRecommendations(images);
                segments.add(seg);
            }
        } catch (Exception e) {
            logger.error("Failed to parse segments", e);
            SceneSegment fallback = new SceneSegment();
            fallback.setSegmentNumber(1);
            fallback.setSentence("Full scene narration");
            fallback.setVisualAnimation("Animation: Educational content display");
            fallback.setImageRecommendations(List.of("Image 1: Educational illustration", "Image 2: Related concept diagram"));
            segments.add(fallback);
        }
        return segments;
    }
    
    private String extractTitle(String text) {
        // Simple title extraction - first sentence or first 50 chars
        int end = text.indexOf('.');
        if (end > 10 && end < 100) {
            return text.substring(0, end);
        }
        return text.substring(0, Math.min(50, text.length())) + "...";
    }
}