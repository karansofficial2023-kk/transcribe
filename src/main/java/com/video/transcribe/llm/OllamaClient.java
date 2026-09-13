package com.video.transcribe.llm;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.video.transcribe.config.AppConfig;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Ollama client - reads URL and model from application.properties
 */
public class OllamaClient {
    
    private static final Logger logger = LoggerFactory.getLogger(OllamaClient.class);
    private static final Gson gson = new Gson();
    
    private final OkHttpClient httpClient;
    private final String baseUrl;
    private final String model;
    
    public OllamaClient(AppConfig config) {
        this.baseUrl = config.getOllamaUrl();
        this.model = config.getOllamaModel();
        // FIX: Much longer timeouts for LLM generation
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .readTimeout(600, TimeUnit.SECONDS)
            .build();
        
        logger.info("Ollama client configured: URL={}, Model={}", baseUrl, model);
    }
    
    /**
     * Paraphrase video transcript
     */
    public String paraphraseTranscript(String originalText, String style) throws IOException {
        
        String systemPrompt = """
            You are a professional educational video script paraphraser.
            Think like a subject-matter expert and curriculum reviewer for the
            specific topic and subject in the transcript. Use curriculum-safe,
            age-appropriate explanations and terminology.
            Rewrite the transcript while preserving EXACT same meaning, facts,
            topic coverage, examples, sequence, and educational value.
            
            Rules:
            1. Preserve all facts, numbers, names, technical details
            2. Correct obvious transcription misspellings to the proper educational term for the topic
            3. Preserve technical concepts, names, organisms, processes, and key terms after correcting obvious transcript spelling errors
            4. Change sentence structure and general vocabulary, but do not rename topics, organisms, processes, or key terms into unrelated concepts
            5. If a word appears misspelled but the intended subject term is clear from context, use the corrected subject term
            6. Maintain the same tone and style
            7. Keep similar length
            8. Cover every topic, sub-topic, example, named plant/person/place/process, comparison, and conclusion from the original
            9. Do not summarize away examples or curriculum points
            10. Do not introduce unsupported facts. If the transcript is unclear, keep the safest curriculum-standard wording for the intended concept.
            11. Preserve the original transcript language and script. If the source is English,
               output English. If the source is Tamil, output Tamil. If the source mixes languages,
               keep that mix naturally.
            12. Do not translate to English unless the requested style explicitly asks for translation
            13. Do not introduce Tamil, Hindi, or any other language if the original transcript is English
            14. Output ONLY the paraphrased text, no explanations
            """;
        
        String userPrompt = String.format("""
            Paraphrase the following video transcript. %s
            
            ORIGINAL TRANSCRIPT:
            %s
            
            PARAPHRASED VERSION:
            """, style != null ? "Style: " + style : "", originalText);
        
        return generate(systemPrompt, userPrompt);
    }

    public String repairParaphrase(String originalText, String previousParaphrase, String issues, String style) throws IOException {
        String systemPrompt = """
            You repair educational paraphrases for curriculum completeness.
            Think like a subject-matter expert and curriculum reviewer for the
            specific topic and subject in the transcript.
            Keep the narration as a paraphrase, but restore every missing topic,
            example, detail, sequence, and comparison from the original transcript.
            Do not add facts that are absent from the original.
            Correct obvious transcription misspellings to the proper educational term for the topic.
            Preserve the intended technical concept after correction; do not change it into an unrelated concept.
            Use curriculum-safe, topic-appropriate wording. If a detail is uncertain, choose the safest standard explanation supported by the transcript.
            Preserve the original transcript language/script.
            Output ONLY the improved paraphrased text.
            """;

        String userPrompt = String.format("""
            Style: %s

            ORIGINAL TRANSCRIPT:
            %s

            PREVIOUS PARAPHRASE:
            %s

            VALIDATION ISSUES TO FIX:
            %s

            IMPROVED PARAPHRASE:
            """, style != null ? style : "professional", originalText, previousParaphrase, issues);

        return generate(systemPrompt, userPrompt);
    }
    
    /**
     * Generate text using Ollama
     */
    public String generate(String systemPrompt, String userPrompt) throws IOException {
        return generate(systemPrompt, userPrompt, null);
    }

    /** Request the existing storyboard fields as schema-constrained JSON. */
    public String generateStructured(String systemPrompt, String userPrompt, JsonObject schema) throws IOException {
        return generate(systemPrompt, userPrompt, schema);
    }

    private String generate(String systemPrompt, String userPrompt, JsonObject schema) throws IOException {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        requestBody.addProperty("system", systemPrompt);
        requestBody.addProperty("prompt", userPrompt);
        requestBody.addProperty("stream", false);
        if (schema != null) {
            requestBody.add("format", schema);
        }
        requestBody.addProperty("temperature", 0.7);
        requestBody.addProperty("num_predict", 8000);
        
        RequestBody body = RequestBody.create(
            requestBody.toString(),
            MediaType.parse("application/json")
        );
        
        Request request = new Request.Builder()
            .url(baseUrl + "/api/generate")
            .post(body)
            .build();
        
        logger.info("Sending request to Ollama ({}) - expecting up to 10 min", model);
        long start = System.currentTimeMillis();
        
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            
            if (!response.isSuccessful()) {
                throw new IOException("Ollama error " + response.code() + ": " + responseBody);
            }
            
            JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);
            String generatedText = jsonResponse.get("response").getAsString();
            
            logger.info("Ollama response in {} ms", System.currentTimeMillis() - start);
            return generatedText.trim();
        }
    }
    
    /**
     * Check if Ollama is running
     */
    public boolean isAvailable() {
        try {
            OkHttpClient healthClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build();
                
            Request request = new Request.Builder()
                .url(baseUrl)
                .build();
            
            try (Response response = healthClient.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            return false;
        }
    }
}
