package com.video.transcribe.validator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.video.transcribe.llm.OllamaClient;

/**
 * Validates paraphrased content against original transcript for accuracy.
 * Checks: factual consistency, key concept preservation, semantic similarity, hallucination detection
 */
public class AccuracyValidator {

    public static final double MIN_OVERALL_SCORE = 80.0;
    public static final double MIN_SCIENTIFIC_ACCURACY_SCORE = 85.0;
    public static final double MIN_TOPIC_COVERAGE_SCORE = 80.0;
    public static final double MIN_HALLUCINATION_SAFETY_SCORE = 85.0;
    
    private static final Logger logger = LoggerFactory.getLogger(AccuracyValidator.class);
    private final OllamaClient ollama;
    private final Gson gson = new Gson();
    
    public AccuracyValidator(OllamaClient ollama) {
        this.ollama = ollama;
    }
    
    public ValidationResult validate(String originalText, String paraphrasedText) throws IOException {
        logger.info("Starting accuracy validation...");
        
        ScoreAssessment semanticAssessment = calculateSemanticSimilarity(originalText, paraphrasedText);
        double semanticScore = semanticAssessment.score();
        ScoreAssessment factualAssessment = checkFactualConsistency(originalText, paraphrasedText);
        double factualScore = factualAssessment.score();
        ScoreAssessment scientificAssessment = checkScientificAccuracy(paraphrasedText);
        double scientificScore = scientificAssessment.score();
        ScoreAssessment keyConceptAssessment = checkKeyConceptsPreserved(originalText, paraphrasedText);
        double keyConceptScore = keyConceptAssessment.score();
        ScoreAssessment topicCoverageAssessment = checkTopicCoverage(originalText, paraphrasedText);
        double topicCoverageScore = topicCoverageAssessment.score();
        ScoreAssessment hallucinationAssessment = detectHallucinations(originalText, paraphrasedText);
        double hallucinationScore = hallucinationAssessment.score();
        
        double overallScore = (semanticScore * 0.10) + (factualScore * 0.15) +
                             (scientificScore * 0.25) + (keyConceptScore * 0.20) +
                             (topicCoverageScore * 0.20) + (hallucinationScore * 0.10);
        
        ValidationResult result = new ValidationResult();
        result.setOriginalLength(originalText.length());
        result.setParaphrasedLength(paraphrasedText.length());
        result.setSemanticSimilarityScore(semanticScore);
        result.setFactualConsistencyScore(factualScore);
        result.setScientificAccuracyScore(scientificScore);
        result.setKeyConceptPreservationScore(keyConceptScore);
        result.setTopicCoverageScore(topicCoverageScore);
        result.setHallucinationScore(hallucinationScore);
        result.setOverallScore(Math.round(overallScore * 100.0) / 100.0);
        result.setPassed(passesQualityGates(result));
        result.setTimestamp(java.time.Instant.now().toString());
        
        List<String> issues = new ArrayList<>(semanticAssessment.issues());
        issues.addAll(factualAssessment.issues());
        issues.addAll(scientificAssessment.issues());
        issues.addAll(keyConceptAssessment.issues());
        issues.addAll(topicCoverageAssessment.issues());
        issues.addAll(hallucinationAssessment.issues());
        issues.addAll(identifyIssues(originalText, paraphrasedText));
        result.setIssues(issues);
        
        logger.info("Validation complete - Overall Score: {}/100", result.getOverallScore());
        return result;
    }

    static boolean passesQualityGates(ValidationResult result) {
        return failedQualityGates(result, MIN_OVERALL_SCORE).isEmpty();
    }

    public static List<String> failedQualityGates(ValidationResult result, double minimumOverallScore) {
        List<String> failures = new ArrayList<>();
        double requiredOverallScore = Math.max(MIN_OVERALL_SCORE, minimumOverallScore);

        addGateFailure(failures, "overall score", result.getOverallScore(), requiredOverallScore);
        addGateFailure(failures, "scientific accuracy", result.getScientificAccuracyScore(),
            MIN_SCIENTIFIC_ACCURACY_SCORE);
        addGateFailure(failures, "topic coverage", result.getTopicCoverageScore(),
            MIN_TOPIC_COVERAGE_SCORE);
        addGateFailure(failures, "hallucination safety", result.getHallucinationScore(),
            MIN_HALLUCINATION_SAFETY_SCORE);
        return failures;
    }

    private static void addGateFailure(List<String> failures, String gate, double actual, double required) {
        if (actual < required) {
            failures.add(String.format("%s %.2f/100 (required %.2f)", gate, actual, required));
        }
    }

    private ScoreAssessment checkScientificAccuracy(String narration) throws IOException {
        String prompt = """
            Independently audit this educational narration against established subject knowledge.
            Do not assume a claim is correct merely because it came from a transcript.
            Check every mechanism, classification, named example, formula, organism, causal claim,
            and technical statement. Penalize unsupported species-general claims, invented anatomy,
            internal contradictions, and explanations that reverse cause and effect.

            NARRATION:
            %s

            Respond ONLY with a JSON object:
            {"score": <number 0-100>, "incorrect_claims": ["..."], "uncertain_claims": ["..."]}
            Score 100 only when no factual defect or unsupported mechanism remains.
            """.formatted(truncate(narration, 8000));
        String response = ollama.generate(
            "You are an independent senior subject-matter fact-checker. Be conservative and strict.",
            prompt
        );
        return new ScoreAssessment(extractScore(response), extractScientificIssues(response));
    }
    
    private ScoreAssessment calculateSemanticSimilarity(String original, String paraphrased) throws IOException {
        String prompt = """
            Compare these two texts and rate their semantic similarity (0-100).
            Focus on meaning preservation, not word overlap.
            IMPORTANT: If the paraphrase corrects an obvious transcript misspelling while preserving the same concept,
            do not penalize heavily. Mention it in reason if needed.
            
            ORIGINAL:
            %s
            
            PARAPHRASED:
            %s
            
            Respond ONLY with a JSON object:
            {"score": <number 0-100>, "reason": "<brief explanation>"}
            """.formatted(truncate(original, 6000), truncate(paraphrased, 6000));
        
        String response = ollama.generate(
            "You are a semantic similarity evaluator. Be strict and accurate.", 
            prompt
        );
        return extractAssessment(response, 80.0, "reason", "Semantic fidelity: ");
    }
    
    private ScoreAssessment checkFactualConsistency(String original, String paraphrased) throws IOException {
        String prompt = """
            Check if all facts from the original text are preserved in the paraphrased version.
            Identify any changed facts, missing facts, or added incorrect facts.
            IMPORTANT: Do not treat spelling correction of a key term as a missing fact when the educational concept is preserved.
            
            ORIGINAL:
            %s
            
            PARAPHRASED:
            %s
            
            Respond ONLY with a JSON object:
            {"score": <number 0-100>, "missing_facts": ["..."], "changed_facts": ["..."], "added_facts": ["..."]}
            """.formatted(truncate(original, 6000), truncate(paraphrased, 6000));
        
        String response = ollama.generate(
            "You are a factual consistency checker. Be thorough and strict.",
            prompt
        );
        return extractAssessment(
            response,
            85.0,
            null,
            null,
            "missing_facts", "Missing source fact: ",
            "changed_facts", "Changed source fact: ",
            "added_facts", "Unsupported or incorrect added fact: "
        );
    }
    
    private ScoreAssessment checkKeyConceptsPreserved(String original, String paraphrased) throws IOException {
        String prompt = """
            Extract key concepts from the original text and check if they appear in the paraphrased version.
            Key concepts include: technical terms, names, processes, definitions, examples.
            IMPORTANT: If a transcript term appears misspelled and the paraphrase uses a common corrected spelling
            for the same concept, count it as preserved, not missing.
            
            ORIGINAL:
            %s
            
            PARAPHRASED:
            %s
            
            Respond ONLY with a JSON object:
            {"score": <number 0-100>, "missing_concepts": ["..."], "preserved_concepts": ["..."]}
            """.formatted(truncate(original, 6000), truncate(paraphrased, 6000));
        
        String response = ollama.generate(
            "You are a concept preservation checker. Focus on educational/scientific accuracy.",
            prompt
        );
        return extractAssessment(
            response,
            80.0,
            null,
            null,
            "missing_concepts", "Missing source concept: "
        );
    }
    
    private ScoreAssessment detectHallucinations(String original, String paraphrased) throws IOException {
        String prompt = """
            Audit the paraphrased text for hallucinations: fabricated, incorrect, unverifiable,
            off-topic, or falsely specific claims. Do not classify the following as hallucinations:
            an obvious transcription correction; a scientifically necessary correction of an
            inaccurate source statement; or a concise, widely accepted explanation that is directly
            relevant and consistent with the source lesson. Source fidelity and omissions are graded
            separately. Penalize invented names, examples, mechanisms, numbers, or claims that cannot
            be supported by the source or established subject knowledge.
            
            ORIGINAL:
            %s
            
            PARAPHRASED:
            %s
            
            Respond ONLY with a JSON object:
            {"score": <number 0-100>, "hallucinations": ["..."], "supported_corrections_or_additions": ["..."], "severity": "low|medium|high"}
            Score: 100 = no hallucinations, 0 = severe hallucinations
            """.formatted(truncate(original, 6000), truncate(paraphrased, 6000));
        
        String response = ollama.generate(
            "You are a hallucination detector. Be extremely strict about fabricated information.",
            prompt
        );
        return extractAssessment(
            response,
            85.0,
            null,
            null,
            "hallucinations", "Hallucination or unsupported claim: "
        );
    }
    
    private List<String> identifyIssues(String original, String paraphrased) throws IOException {
        String prompt = """
            List all accuracy issues between original and paraphrased text.
            Include: missing info, wrong info, changed meaning, poor paraphrasing.
            Do not report an obvious transcript misspelling as changed meaning when the
            paraphrase uses the established scientific or educational term for the same concept.
            Never recommend restoring a misspelling or scientifically invalid source claim.
            
            ORIGINAL:
            %s
            
            PARAPHRASED:
            %s
            
            Respond with a JSON array of issue strings.
            If no issues, return [].
            """.formatted(truncate(original, 6000), truncate(paraphrased, 6000));
        
        String response = ollama.generate(
            "You are a quality assurance expert. List specific issues concisely.",
            prompt
        );
        
        return extractIssueList(response);
    }

    private ScoreAssessment checkTopicCoverage(String original, String paraphrased) throws IOException {
        String prompt = """
            Grade whether the paraphrase covers ALL educational topics from the original.
            Look for missing sections, skipped examples, missing named organisms/objects,
            missing processes, missing comparisons, missing conclusions, and shortened curriculum coverage.
            IMPORTANT: Do not penalize spelling normalization when the same topic/concept remains covered.

            ORIGINAL:
            %s

            PARAPHRASED:
            %s

            Respond ONLY with a JSON object:
            {"score": <number 0-100>, "missing_topics": ["..."], "covered_topics": ["..."]}
            Score 100 means every topic and example is covered. Penalize heavily for omissions.
            """.formatted(truncate(original, 8000), truncate(paraphrased, 8000));

        String response = ollama.generate(
            "You are an educational curriculum coverage auditor. Be strict about missing topics.",
            prompt
        );
        return extractAssessment(
            response,
            80.0,
            null,
            null,
            "missing_topics", "Missing source topic: "
        );
    }
    
    private double extractScore(String jsonResponse) {
        try {
            JsonObject obj = JsonParser.parseString(extractJsonObject(jsonResponse)).getAsJsonObject();
            return obj.get("score").getAsDouble();
        } catch (Exception e) {
            Double fallbackScore = extractLooseScore(jsonResponse);
            if (fallbackScore != null) {
                logger.warn("Recovered loose validation score {} from non-JSON LLM response", fallbackScore);
                return fallbackScore;
            }
            logger.warn("Failed to parse score from LLM response: {}", jsonResponse);
            return 50.0;
        }
    }

    static List<String> extractScientificIssues(String jsonResponse) {
        try {
            JsonObject obj = JsonParser.parseString(extractJsonObject(jsonResponse)).getAsJsonObject();
            List<String> issues = new ArrayList<>();
            appendStringArray(issues, obj, "incorrect_claims", "Scientific accuracy: ");
            appendStringArray(issues, obj, "uncertain_claims", "Scientific uncertainty: ");
            return issues;
        } catch (Exception e) {
            return List.of("Scientific evaluator details could not be parsed; inspect the narration manually.");
        }
    }

    private ScoreAssessment extractAssessment(
            String jsonResponse,
            double issueThreshold,
            String scalarField,
            String scalarPrefix,
            String... arrayFieldPrefixPairs) {
        double score = extractScore(jsonResponse);
        List<String> issues = new ArrayList<>();
        try {
            JsonObject object = JsonParser.parseString(extractJsonObject(jsonResponse)).getAsJsonObject();
            if (score < issueThreshold && scalarField != null && object.has(scalarField)
                    && object.get(scalarField).isJsonPrimitive()) {
                String detail = object.get(scalarField).getAsString().trim();
                if (!detail.isEmpty()) {
                    issues.add((scalarPrefix == null ? "" : scalarPrefix) + detail);
                }
            }
            for (int index = 0; index + 1 < arrayFieldPrefixPairs.length; index += 2) {
                appendStringArray(
                    issues,
                    object,
                    arrayFieldPrefixPairs[index],
                    arrayFieldPrefixPairs[index + 1]
                );
            }
        } catch (Exception e) {
            if (score < issueThreshold) {
                issues.add("Validation details could not be parsed for a score of " + score + "/100.");
            }
        }
        return new ScoreAssessment(score, issues);
    }

    static List<String> extractIssueList(String jsonResponse) {
        try {
            JsonElement root = JsonParser.parseString(extractJsonValue(jsonResponse));
            List<String> issues = new ArrayList<>();
            if (root.isJsonArray()) {
                appendPrimitiveStrings(issues, root.getAsJsonArray(), "");
            } else if (root.isJsonObject()) {
                JsonObject object = root.getAsJsonObject();
                for (String field : List.of(
                        "issues", "missing_facts", "changed_facts", "added_facts",
                        "incorrect_claims", "uncertain_claims", "hallucinations", "missing_topics")) {
                    appendStringArray(issues, object, field, "");
                }
            }
            return issues;
        } catch (Exception e) {
            return List.of("Issue evaluator response could not be parsed; use the dimension-specific findings above.");
        }
    }

    private static void appendPrimitiveStrings(List<String> destination, JsonArray array, String prefix) {
        array.forEach(element -> {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                String value = element.getAsString().trim();
                if (!value.isEmpty()) {
                    destination.add(prefix + value);
                }
            }
        });
    }

    private static void appendStringArray(
            List<String> destination,
            JsonObject object,
            String memberName,
            String prefix) {
        if (!object.has(memberName) || !object.get(memberName).isJsonArray()) {
            return;
        }
        object.getAsJsonArray(memberName).forEach(element -> {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                String value = element.getAsString().trim();
                if (!value.isEmpty()) {
                    destination.add(prefix + value);
                }
            }
        });
    }

    private static String extractJsonObject(String response) {
        if (response == null) {
            return "{}";
        }
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }
        return response;
    }

    private static String extractJsonValue(String response) {
        if (response == null) {
            return "[]";
        }
        int objectStart = response.indexOf('{');
        int arrayStart = response.indexOf('[');
        int start;
        char closing;
        if (objectStart >= 0 && (arrayStart < 0 || objectStart < arrayStart)) {
            start = objectStart;
            closing = '}';
        } else if (arrayStart >= 0) {
            start = arrayStart;
            closing = ']';
        } else {
            return response;
        }
        int end = response.lastIndexOf(closing);
        return end > start ? response.substring(start, end + 1) : response;
    }

    private Double extractLooseScore(String response) {
        if (response == null) {
            return null;
        }
        Pattern scoreOutOfTen = Pattern.compile("(?i)(?:score\\s*[:\\-]?\\s*)?(\\d+(?:\\.\\d+)?)\\s*/\\s*10\\b");
        Matcher outOfTenMatcher = scoreOutOfTen.matcher(response);
        if (outOfTenMatcher.find()) {
            return Math.min(100.0, Double.parseDouble(outOfTenMatcher.group(1)) * 10.0);
        }

        Pattern scoreOutOfHundred = Pattern.compile("(?i)(?:score\\s*[:\\-]?\\s*)?(\\d+(?:\\.\\d+)?)\\s*/\\s*100\\b");
        Matcher outOfHundredMatcher = scoreOutOfHundred.matcher(response);
        if (outOfHundredMatcher.find()) {
            return Math.min(100.0, Double.parseDouble(outOfHundredMatcher.group(1)));
        }

        Pattern scoreField = Pattern.compile("(?i)score\\D{0,10}(\\d+(?:\\.\\d+)?)");
        Matcher scoreFieldMatcher = scoreField.matcher(response);
        if (scoreFieldMatcher.find()) {
            double score = Double.parseDouble(scoreFieldMatcher.group(1));
            return score <= 10.0 ? score * 10.0 : Math.min(100.0, score);
        }
        return null;
    }
    
    private String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "... [truncated]";
    }

    private record ScoreAssessment(double score, List<String> issues) {
    }
}
