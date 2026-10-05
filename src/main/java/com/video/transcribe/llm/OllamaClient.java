package com.video.transcribe.llm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.video.transcribe.config.AppConfig;
import com.video.transcribe.util.Sleeper;

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
    private final boolean think;
    private final double temperature;
    private final int numCtx;
    private final String keepAlive;
    private volatile int nextNumPredict = 0;
    private final LlmCache cache;
    private volatile boolean lastAnswerCutOff;
    // Self-healing: how often a transient failure is retried, the first back-off (doubles each time, capped at 60 s), how long to wait for
    // Ollama to return when it is down, and the clock used for waiting. Overridable with -Dollama.retry.attempts / .base-ms / .service-wait-ms.
    private final int maxAttempts;
    private final long retryBaseMillis;
    private final long serviceWaitMillis;
    private final long servicePollMillis;
    private final Sleeper sleeper;

    /** True when the most recent answer stopped at the token limit instead of finishing, so callers can retry with a larger budget. */
    public boolean lastAnswerWasCutOff() {
        return lastAnswerCutOff;
    }
    
    public OllamaClient(AppConfig config) {
        this(config, config.getOllamaModel());
    }

    /** Client bound to a specific model (e.g. the fast model for structured planning passes). */
    public OllamaClient(AppConfig config, String modelName) {
        this.baseUrl = config.getOllamaUrl();
        this.model = modelName;
        this.think = config.isOllamaThink();
        this.temperature = config.getOllamaTemperature();
        // The fast model (8B) leaves VRAM headroom, so it gets a larger window for long structured plans.
        this.numCtx = modelName.equals(config.getOllamaModel()) ? config.getOllamaNumCtx() : config.getOllamaFastNumCtx();
        this.keepAlive = config.getOllamaKeepAlive();
        this.cache = new LlmCache(config.getOllamaCacheDir());
        // FIX: Much longer timeouts for LLM generation
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .readTimeout(1500, TimeUnit.SECONDS)
            .build();
        this.maxAttempts = Math.max(1, Integer.getInteger("ollama.retry.attempts", 4));
        this.retryBaseMillis = Long.getLong("ollama.retry.base-ms", 4_000L);
        this.serviceWaitMillis = Long.getLong("ollama.retry.service-wait-ms", 600_000L);
        this.servicePollMillis = 5_000L;
        this.sleeper = Sleeper.REAL;

        logger.info("Ollama client configured: URL={}, Model={}", baseUrl, model);
    }

    /** For tests: a client against a given URL with explicit recovery settings and an injected clock. */
    OllamaClient(String baseUrl, String modelName, String cacheDir, int maxAttempts, long retryBaseMillis, long serviceWaitMillis, Sleeper sleeper) {
        this.baseUrl = baseUrl;
        this.model = modelName;
        this.think = false;
        this.temperature = 0.2;
        this.numCtx = 4096;
        this.keepAlive = "5m";
        this.cache = new LlmCache(cacheDir);
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build();
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryBaseMillis = retryBaseMillis;
        this.serviceWaitMillis = serviceWaitMillis;
        this.servicePollMillis = 1L;
        this.sleeper = sleeper;
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
            14. Remove non-educational channel promotion phrases such as subscribe, subscribed,
                subscription prompts, like it, like, share, comment, press the bell icon,
                watch more videos, eBibe videos, Embibe videos, "in this video", "welcome back",
                thanks for watching, stay tuned, and similar social-media calls to action.
                Do not remove curriculum content.
            15. Output ONLY the paraphrased text, no explanations
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
            Remove non-educational channel promotion phrases such as subscribe, subscribed,
            subscription prompts, like it, like, share, comment, press the bell icon,
            watch more videos, eBibe videos, Embibe videos, "in this video", "welcome back",
            thanks for watching, stay tuned, and similar social-media calls to action.
            Do not remove curriculum content.
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
     * Correct only clear ASR spelling and encoding defects before validation.
     * This is deliberately separate from paraphrasing so terminology is reviewed
     * consistently for every detected subject without topic-specific substitutions.
     */
    public String proofreadEducationalTerminology(String text) throws IOException {
        String systemPrompt = """
            You are the subject-matter expert and conservative educational copy editor
            for the subject and topic present in the supplied narration.

            Correct only:
            - obvious speech-to-text misspellings of established subject terminology,
              proper names, organisms, processes, symbols, and technical vocabulary;
            - mojibake or broken punctuation characters.

            Strict rules:
            - Preserve every sentence, fact, example, comparison, number, and conclusion.
            - Do not add, remove, summarize, enrich, reorder, or paraphrase content.
            - Preserve the source language and script.
            - Change a term only when the intended curriculum-standard term is clear
              from context. Otherwise leave it unchanged.
            - Use ordinary apostrophes, quotation marks, commas, and hyphens where needed.
            - Output only the corrected narration.
            """;
        return generate(systemPrompt, "NARRATION TO PROOFREAD:\n" + text);
    }

    /**
     * Add optional curriculum-safe supporting details after a validated paraphrase.
     * This is intentionally separate from transcript paraphrasing so validation can
     * still measure transcript fidelity before enrichment.
     */
    public String enrichParaphraseForCurriculum(String originalTranscript, String validatedParaphrase,
            String projectMaterialsContext, String style) throws IOException {
        String systemPrompt = """
            You are a senior educational script editor, curriculum reviewer, and
            subject-matter expert for the exact topic in the supplied lesson.

            Task:
            Preserve all accurate ideas from the validated paraphrase in a coherent
            sequence, then integrate curriculum enrichment when it strengthens the
            lesson for educational video production.

            Source priority:
            1. Explicit teacher corrections or approved materials in PROJECT MATERIALS
            2. Approved lesson/storyboard/script in PROJECT MATERIALS
            3. Curriculum, objectives, textbook, notes, or references in PROJECT MATERIALS
            4. The original transcript and validated paraphrase
            5. Widely accepted curriculum-standard supporting facts directly tied to the same topic

            Rules:
            - Teacher corrections and approved materials may replace an inaccurate
              example or mechanism in the validated paraphrase.
            - Otherwise preserve the validated paraphrase's accurate ideas and sequence.
            - Do not change the transcript; only return the final narration text.
            - Add only lesson-related enrichment: missing subtopics, advantages,
              disadvantages, applications, comparisons, examples, common mistakes,
              safety notes, prerequisites, or recap points when relevant.
            - Never hardcode a subject, species, formula, place, or topic.
            - Do not add unrelated examples or unverified claims.
            - If supplied materials conflict, follow teacher-approved material and
              avoid unsupported claims.
            - Preserve the source language/script of the paraphrase.
            - Remove channel promotion phrases if any remain.
            - Use plain narration sentences. No Markdown bullets, asterisks,
              tables, headings with symbols, citations, JSON, or notes to the user.
            - When PROJECT MATERIALS explicitly mark topics as required lesson coverage,
              include every required topic. Split dense coverage into concise spoken
              sentences; do not impose a fixed sentence limit that causes omissions.
            - Integrate additions naturally. Do not output headings such as
              "Curriculum Enrichment", production notes, or commentary.
            - Output only the final narration text.
            """;

        String userPrompt = String.format("""
            Style: %s

            PROJECT MATERIALS:
            %s

            ORIGINAL TRANSCRIPT:
            %s

            VALIDATED PARAPHRASE TO PRESERVE:
            %s

            FINAL NARRATION WITH OPTIONAL CURRICULUM ENRICHMENT:
            """,
            style != null ? style : "professional educational narration",
            projectMaterialsContext == null || projectMaterialsContext.isBlank()
                ? "No additional project materials were supplied."
                : projectMaterialsContext,
            originalTranscript,
            validatedParaphrase);

        return generate(systemPrompt, userPrompt);
    }

    /**
     * Correct high-confidence factual defects without tying the pipeline to one
     * subject. This pass runs after optional enrichment so the storyboard is not
     * forced to preserve an inaccurate claim merely because it came from ASR.
     */
    public String factCheckEducationalNarration(String narration,
            String projectMaterialsContext) throws IOException {
        String systemPrompt = """
            You are a conservative senior subject-matter fact-checker for the exact
            educational topic in the supplied narration.

            Source priority:
            1. Explicit teacher corrections and explicitly approved materials
            2. Approved curriculum, textbook, standards, lesson notes, or references
            3. Widely accepted subject knowledge
            4. The narration or transcript

            Requirements:
            - Preserve the lesson topic, learning sequence, useful examples, and language.
            - Correct a factual mechanism, classification, name, formula, organism,
              historical claim, or technical statement only when the correction is
              high confidence.
            - Do not preserve a demonstrably incorrect claim merely for transcript fidelity.
            - Replace unsupported broad claims with accurate qualified wording.
            - If an example cannot be verified confidently, remove that example while
              preserving the concept with a neutral accurate explanation.
            - Never invent a citation, source, species, formula, statistic, or example.
            - Do not add unrelated curriculum material or hardcode another lesson.
            - Remove Markdown and production instructions.
            - Return only the corrected narration as natural spoken sentences.
            """;
        String userPrompt = """
            PROJECT MATERIALS:
            %s

            NARRATION TO FACT-CHECK:
            %s

            CORRECTED NARRATION:
            """.formatted(
                projectMaterialsContext == null || projectMaterialsContext.isBlank()
                    ? "No additional project materials were supplied."
                    : projectMaterialsContext,
                narration);
        String corrected = generate(systemPrompt, userPrompt);
        return auditCorrectedNarration(corrected, projectMaterialsContext);
    }

    private String auditCorrectedNarration(String corrected,
            String projectMaterialsContext) throws IOException {
        List<String> requiredCoverage = extractRequiredCoverage(projectMaterialsContext);
        Map<String, String> coverageById = new LinkedHashMap<>();
        for (int index = 0; index < requiredCoverage.size(); index++) {
            coverageById.put("R%02d".formatted(index + 1), requiredCoverage.get(index));
        }
        String coverageChecklist = coverageById.entrySet().stream()
            .map(entry -> entry.getKey() + " | " + entry.getValue())
            .collect(java.util.stream.Collectors.joining("\n"));
        String auditPrompt = """
            Perform an adversarial final audit of the corrected educational narration.

            PROJECT MATERIALS AND REQUIRED COVERAGE:
            %s

            EXACT REQUIRED COVERAGE CHECKLIST:
            %s

            NARRATION TO AUDIT:
            %s

            Requirements:
            - Check every factual sentence, example, classification, mechanism, formula,
              and named entity against the supplied higher-priority materials.
            - Resolve contradictions inside the narration. A mechanism that prevents or
              reduces a process must never be summarized as causing that process.
            - Apply every explicit correction, prohibition, qualification, and
              species-specific distinction in the project materials.
            - Include every item explicitly marked as required lesson coverage, with a
              clear definition or explanation rather than only naming the term.
            - Keep accurate existing content and the source language.
            - Remove unsupported claims rather than guessing.
            - correctedNarration must be the complete final spoken script, with no
              Markdown, headings, production notes, citations, or audit commentary.
            - Set coverageComplete=true only if all explicit required coverage is present.
            - Return every checklist requirementId exactly once in coverageChecks and set
              covered=true only when correctedNarration explains that mapped requirement clearly.
            - evidenceQuote must be an exact, direct explanatory sentence copied from
              correctedNarration for that requirement, not a keyword or unrelated sentence.
            - remainingConcerns must be empty only when no factual or coverage defect remains.
            """.formatted(
                projectMaterialsContext == null || projectMaterialsContext.isBlank()
                    ? "No additional project materials were supplied."
                    : projectMaterialsContext,
                coverageById.isEmpty()
                    ? "No machine-readable checklist was supplied."
                    : coverageChecklist,
                corrected);
        JsonObject auditSchema = gson.fromJson("""
            {
              "type": "object",
              "properties": {
                "correctedNarration": { "type": "string" },
                "coverageComplete": { "type": "boolean" },
                "issuesResolved": { "type": "integer" },
                "coverageChecks": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "requirementId": { "type": "string" },
                      "covered": { "type": "boolean" },
                      "evidenceQuote": { "type": "string" }
                    },
                    "required": ["requirementId", "covered", "evidenceQuote"],
                    "additionalProperties": false
                  }
                },
                "remainingConcerns": {
                  "type": "array",
                  "items": { "type": "string" }
                }
              },
              "required": [
                "correctedNarration",
                "coverageComplete",
                "issuesResolved",
                "coverageChecks",
                "remainingConcerns"
              ],
              "additionalProperties": false
            }
            """, JsonObject.class);
        if (!coverageById.isEmpty()) {
            auditSchema.getAsJsonObject("properties")
                .getAsJsonObject("coverageChecks")
                .getAsJsonObject("items")
                .getAsJsonObject("properties")
                .getAsJsonObject("requirementId")
                .add("enum", gson.toJsonTree(coverageById.keySet()));
        }
        JsonObject audit = gson.fromJson(generateStructured(
            "You are the final independent educational accuracy and curriculum auditor. "
                + "Return only schema-constrained JSON.", auditPrompt, auditSchema), JsonObject.class);
        Set<String> covered = new LinkedHashSet<>();
        String auditedNarration = audit.get("correctedNarration").getAsString();
        audit.getAsJsonArray("coverageChecks").forEach(element -> {
            JsonObject check = element.getAsJsonObject();
            String evidence = check.get("evidenceQuote").getAsString().trim();
            if (check.get("covered").getAsBoolean() && evidence.length() >= 12
                    && auditedNarration.contains(evidence)) {
                covered.add(check.get("requirementId").getAsString().trim());
            }
        });
        Set<String> missingIds = new LinkedHashSet<>(coverageById.keySet());
        missingIds.removeAll(covered);
        List<String> missing = missingIds.stream().map(coverageById::get).toList();
        if (!audit.get("coverageComplete").getAsBoolean() || !missingIds.isEmpty()
                || !audit.getAsJsonArray("remainingConcerns").isEmpty()) {
            throw new IOException("Narration audit failed. Missing coverage=" + missing
                + "; remaining concerns=" + audit.get("remainingConcerns"));
        }
        return auditedNarration;
    }

    private List<String> extractRequiredCoverage(String projectMaterialsContext) {
        if (projectMaterialsContext == null || projectMaterialsContext.isBlank()) {
            return List.of();
        }
        List<String> requirements = new ArrayList<>();
        Pattern item = Pattern.compile("^\\s*\\d+[.)]\\s+(.+?)\\s*$");
        boolean inCoverage = false;
        for (String line : projectMaterialsContext.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.toLowerCase().contains("required lesson coverage")) {
                inCoverage = true;
                continue;
            }
            if (inCoverage && trimmed.startsWith("## ")) break;
            if (!inCoverage) continue;
            Matcher matcher = item.matcher(trimmed);
            if (matcher.matches()) requirements.add(matcher.group(1).trim());
        }
        return requirements;
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

    /** Structured request with an explicit output-token budget (large JSON plans must never be cut off mid-object). */
    public String generateStructured(String systemPrompt, String userPrompt, JsonObject schema, int maxTokens) throws IOException {
        this.nextNumPredict = maxTokens;
        try {
            return generate(systemPrompt, userPrompt, schema);
        } finally {
            this.nextNumPredict = 0;
        }
    }

    /** Number of non-Latin letters, used to detect narration written in Indic/Arabic scripts. */
    static int nonLatinLetters(String text) {
        int count = 0;
        for (int i = 0; text != null && i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= 0x0590 && Character.isLetter(ch)) count++;
        }
        return count;
    }

    static final String KEEP_LANGUAGE_RULE = "\nLANGUAGE RULE: the source text is not English. Write ALL output in exactly the same "
        + "language and the same script as the source text. Never translate it into English and never transliterate it into Latin "
        + "letters. Keep standard scientific terms in the form the source uses (native script, or English only where the source "
        + "itself uses English). Keep numbers, symbols and formulas unchanged.\n";

    String generate(String systemPrompt, String userPrompt, JsonObject schema) throws IOException {
        if (schema == null && nonLatinLetters(userPrompt) >= 30) {
            systemPrompt = systemPrompt + KEEP_LANGUAGE_RULE;
        }
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        requestBody.addProperty("system", systemPrompt);
        requestBody.addProperty("prompt", userPrompt);
        requestBody.addProperty("stream", false);
        if (schema != null) {
            requestBody.add("format", schema);
        }
        requestBody.addProperty("think", think);
        requestBody.addProperty("keep_alive", keepAlive);
        // Ollama reads sampling parameters from "options"; top-level temperature/num_predict are silently ignored.
        JsonObject options = new JsonObject();
        options.addProperty("temperature", temperature);
        int budget = nextNumPredict > 0 ? nextNumPredict
            : (schema != null ? 16000 : (int) Math.max(1500, Math.min(8000, userPrompt.length() * 1.6 + 800)));
        options.addProperty("num_predict", budget);
        options.addProperty("repeat_penalty", 1.08);
        options.addProperty("num_ctx", numCtx);
        requestBody.add("options", options);
        
        String cached = cache.get(requestBody.toString());
        if (cached != null) {
            logger.info("Ollama answer from cache ({})", model);
            return cached;
        }

        logger.info("Sending request to Ollama ({}) - expecting up to 10 min", model);
        String requestJson = requestBody.toString();
        RetryableException last = null;
        // A dropped connection, a server error, a restarting Ollama or a garbled / empty answer is transient: recover and ask again instead of
        // failing the whole video. Only a request Ollama itself rejects (4xx) is final.
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long start = System.currentTimeMillis();
            try {
                String text = sendOnce(requestJson, budget);
                long elapsed = System.currentTimeMillis() - start;
                LlmCache.MODEL_MILLIS.addAndGet(elapsed);
                logger.info("Ollama response in {} ms", elapsed);
                return text;
            } catch (RetryableException e) {
                last = e;
                if (attempt == maxAttempts) {
                    break;
                }
                logger.warn("Ollama ({}) attempt {}/{} failed: {} - recovering, then retrying", model, attempt, maxAttempts, e.getMessage());
                recoverFrom(e);
                pause(Math.min(60_000L, retryBaseMillis * (1L << (attempt - 1))));
            }
        }
        throw new IOException("Ollama request failed after " + maxAttempts + " attempts: " + (last == null ? "unknown error" : last.getMessage()), last);
    }

    /** One request. Throws RetryableException for anything worth another try, a plain IOException for a request that will never work. */
    private String sendOnce(String requestJson, int budget) throws IOException {
        Request request = new Request.Builder()
            .url(baseUrl + "/api/generate")
            .post(RequestBody.create(requestJson, MediaType.parse("application/json")))
            .build();
        String responseBody;
        int code;
        try (Response response = httpClient.newCall(request).execute()) {
            responseBody = response.body() != null ? response.body().string() : "";
            code = response.code();
        } catch (IOException e) {
            throw new RetryableException("connection problem (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")", e, isConnectFailure(e));
        }
        if (code >= 500 || code == 429 || code == 408) {
            throw new RetryableException("HTTP " + code + ": " + shorten(responseBody), null, false);
        }
        if (code < 200 || code >= 300) {
            throw new IOException("Ollama error " + code + ": " + shorten(responseBody));
        }
        JsonObject jsonResponse;
        String generatedText;
        try {
            jsonResponse = gson.fromJson(responseBody, JsonObject.class);
            JsonElement answer = jsonResponse == null ? null : jsonResponse.get("response");
            if (answer == null || answer.isJsonNull()) {
                throw new RetryableException("the answer has no 'response' field", null, false);
            }
            generatedText = answer.getAsString().trim();
        } catch (RuntimeException e) {                  // not JSON at all (an HTML error page, a cut-off body), or 'response' is not text
            throw new RetryableException("malformed answer (" + e.getClass().getSimpleName() + ")", e, false);
        }
        // done_reason "length" means the model hit its output limit mid-answer: such text is incomplete, so it is flagged and never cached
        // (a cached cut-off answer would be replayed identically on every re-run)
        boolean cutOff = jsonResponse.has("done_reason") && !jsonResponse.get("done_reason").isJsonNull()
            && "length".equals(jsonResponse.get("done_reason").getAsString());
        lastAnswerCutOff = cutOff;
        if (generatedText.isEmpty()) {
            throw new RetryableException("empty answer", null, false);
        }
        if (cutOff) {
            logger.warn("Ollama ({}) stopped at its output limit of {} tokens; the answer is incomplete and is not cached", model, budget);
        } else {
            cache.put(requestJson, generatedText);
        }
        return generatedText;
    }

    /** Fixes what can be fixed before the next attempt: waits for Ollama to come back, or frees VRAM after a memory fault. */
    private void recoverFrom(RetryableException e) {
        if (e.connectFailure) {
            waitForService();
        } else if (looksLikeMemoryFault(e.getMessage())) {
            unload();
        }
    }

    private void waitForService() {
        long deadline = System.currentTimeMillis() + serviceWaitMillis;
        long lastNote = 0;
        while (!isAvailable()) {
            if (System.currentTimeMillis() >= deadline) {
                logger.error("Ollama ({}) did not come back within {} s", baseUrl, serviceWaitMillis / 1000);
                return;
            }
            if (System.currentTimeMillis() - lastNote > 30_000) {
                logger.warn("Ollama is not reachable at {}; waiting for it to return (start it with: ollama serve)", baseUrl);
                lastNote = System.currentTimeMillis();
            }
            pause(servicePollMillis);
        }
        logger.info("Ollama is reachable again");
    }

    private void pause(long millis) {
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isConnectFailure(IOException e) {
        String text = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
        return e instanceof java.net.ConnectException || e instanceof java.net.UnknownHostException
            || text.contains("failed to connect") || text.contains("connection refused");
    }

    private static boolean looksLikeMemoryFault(String message) {
        String text = String.valueOf(message).toLowerCase(java.util.Locale.ROOT);
        return text.contains("memory") || text.contains("runner") || text.contains("cuda") || text.contains("terminated")
            || text.contains("killed") || text.contains("insufficient");
    }

    private static String shorten(String text) {
        String single = String.valueOf(text).replaceAll("\\s+", " ").trim();
        return single.length() > 200 ? single.substring(0, 200) + "..." : single;
    }

    /** A failure worth another attempt; connectFailure marks "Ollama is not running / not reachable". */
    private static final class RetryableException extends IOException {
        private static final long serialVersionUID = 1L;
        final boolean connectFailure;

        RetryableException(String message, Throwable cause, boolean connectFailure) {
            super(message, cause);
            this.connectFailure = connectFailure;
        }
    }
    
    /** Frees the model's VRAM (keep_alive 0) so GPU-bound tools such as Whisper can use the whole card. */
    public void unload() {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.addProperty("keep_alive", 0);
            Request request = new Request.Builder().url(baseUrl + "/api/generate")
                .post(RequestBody.create(body.toString(), MediaType.parse("application/json"))).build();
            try (Response response = httpClient.newCall(request).execute()) {
                logger.info("Unloaded Ollama model {} ({})", model, response.code());
            }
        } catch (Exception e) {
            logger.debug("Ollama unload skipped: {}", e.getMessage());
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
