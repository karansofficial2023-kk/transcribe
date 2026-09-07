package com.video.transcribe.validator;

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
 * Validates paraphrased content against original transcript for accuracy.
 * Checks: factual consistency, key concept preservation, semantic similarity, hallucination detection
 */
public class AccuracyValidator {
    
    private static final Logger logger = LoggerFactory.getLogger(AccuracyValidator.class);
    private final OllamaClient ollama;
    private final Gson gson = new Gson();
    
    public AccuracyValidator(OllamaClient ollama) {
        this.ollama = ollama;
    }
    
    public ValidationResult validate(String originalText, String paraphrasedText) throws IOException {
        logger.info("Starting accuracy validation...");
        
        double semanticScore = calculateSemanticSimilarity(originalText, paraphrasedText);
        double factualScore = checkFactualConsistency(originalText, paraphrasedText);
        double keyConceptScore = checkKeyConceptsPreserved(originalText, paraphrasedText);
        double hallucinationScore = detectHallucinations(originalText, paraphrasedText);
        
        double overallScore = (semanticScore * 0.25) + (factualScore * 0.35) + 
                             (keyConceptScore * 0.25) + (hallucinationScore * 0.15);
        
        ValidationResult result = new ValidationResult();
        result.setOriginalLength(originalText.length());
        result.setParaphrasedLength(paraphrasedText.length());
        result.setSemanticSimilarityScore(semanticScore);
        result.setFactualConsistencyScore(factualScore);
        result.setKeyConceptPreservationScore(keyConceptScore);
        result.setHallucinationScore(hallucinationScore);
        result.setOverallScore(Math.round(overallScore * 100.0) / 100.0);
        result.setPassed(overallScore >= 75.0);
        result.setTimestamp(java.time.Instant.now().toString());
        
        List<String> issues = identifyIssues(originalText, paraphrasedText);
        result.setIssues(issues);
        
        logger.info("Validation complete - Overall Score: {}/100", result.getOverallScore());
        return result;
    }
    
    private double calculateSemanticSimilarity(String original, String paraphrased) throws IOException {
        String prompt = """
            Compare these two texts and rate their semantic similarity (0-100).
            Focus on meaning preservation, not word overlap.
            
            ORIGINAL:
            %s
            
            PARAPHRASED:
            %s
            
            Respond ONLY with a JSON object:
            {"score": <number 0-100>, "reason": "<brief explanation>"}
            """.formatted(truncate(original, 2000), truncate(paraphrased, 2000));
        
        String response = ollama.generate(
            "You are a semantic similarity evaluator. Be strict and accurate.", 
            prompt
        );
        return extractScore(response);
    }
    
    private double checkFactualConsistency(String original, String paraphrased) throws IOException {
        String prompt = """
            Check if all facts from the original text are preserved in the paraphrased version.
            Identify any changed facts, missing facts, or added incorrect facts.
            
            ORIGINAL:
            %s
            
            PARAPHRASED:
            %s
            
            Respond ONLY with a JSON object:
            {"score": <number 0-100>, "missing_facts": ["..."], "changed_facts": ["..."], "added_facts": ["..."]}
            """.formatted(truncate(original, 2000), truncate(paraphrased, 2000));
        
        String response = ollama.generate(
            "You are a factual consistency checker. Be thorough and strict.",
            prompt
        );
        return extractScore(response);
    }
    
    private double checkKeyConceptsPreserved(String original, String paraphrased) throws IOException {
        String prompt = """
            Extract key concepts from the original text and check if they appear in the paraphrased version.
            Key concepts include: technical terms, names, processes, definitions, examples.
            
            ORIGINAL:
            %s
            
            PARAPHRASED:
            %s
            
            Respond ONLY with a JSON object:
            {"score": <number 0-100>, "missing_concepts": ["..."], "preserved_concepts": ["..."]}
            """.formatted(truncate(original, 2000), truncate(paraphrased, 2000));
        
        String response = ollama.generate(
            "You are a concept preservation checker. Focus on educational/scientific accuracy.",
            prompt
        );
        return extractScore(response);
    }
    
    private double detectHallucinations(String original, String paraphrased) throws IOException {
        String prompt = """
            Check if the paraphrased text contains any information NOT present in the original.
            Look for fabricated facts, wrong examples, incorrect definitions, or made-up details.
            
            ORIGINAL:
            %s
            
            PARAPHRASED:
            %s
            
            Respond ONLY with a JSON object:
            {"score": <number 0-100>, "hallucinations": ["..."], "severity": "low|medium|high"}
            Score: 100 = no hallucinations, 0 = severe hallucinations
            """.formatted(truncate(original, 2000), truncate(paraphrased, 2000));
        
        String response = ollama.generate(
            "You are a hallucination detector. Be extremely strict about fabricated information.",
            prompt
        );
        return extractScore(response);
    }
    
    private List<String> identifyIssues(String original, String paraphrased) throws IOException {
        String prompt = """
            List all accuracy issues between original and paraphrased text.
            Include: missing info, wrong info, changed meaning, poor paraphrasing.
            
            ORIGINAL:
            %s
            
            PARAPHRASED:
            %s
            
            Respond with a JSON array of issue strings.
            If no issues, return [].
            """.formatted(truncate(original, 1500), truncate(paraphrased, 1500));
        
        String response = ollama.generate(
            "You are a quality assurance expert. List specific issues concisely.",
            prompt
        );
        
        try {
            JsonArray arr = JsonParser.parseString(response).getAsJsonArray();
            List<String> issues = new ArrayList<>();
            arr.forEach(e -> issues.add(e.getAsString()));
            return issues;
        } catch (Exception e) {
            return List.of("Could not parse issues - manual review recommended");
        }
    }
    
    private double extractScore(String jsonResponse) {
        try {
            JsonObject obj = JsonParser.parseString(jsonResponse).getAsJsonObject();
            return obj.get("score").getAsDouble();
        } catch (Exception e) {
            logger.warn("Failed to parse score from LLM response: {}", jsonResponse);
            return 50.0;
        }
    }
    
    private String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "... [truncated]";
    }
}