package com.video.transcribe.scene;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.video.transcribe.LanguageSupport.Language;
import com.video.transcribe.config.AppConfig;
import com.video.transcribe.transcription.Glossary;

/**
 * Offline translation through {@code translate_text.py} (MADLAD-400, Apache-2.0): one long-lived worker process, one JSON
 * line per request, so the 6 GB model is loaded once per run instead of once per batch.
 */
public final class ProcessTextTranslator implements StoryboardTranslator.TextTranslator, AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ProcessTextTranslator.class);

    private static final java.time.Duration REQUEST_TIMEOUT = java.time.Duration.ofMinutes(20);

    private final java.util.concurrent.ExecutorService reader = java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "translation-worker-reader");
        thread.setDaemon(true);
        return thread;
    });
    private final Process process;
    private final BufferedWriter toWorker;
    private final BufferedReader fromWorker;
    private final Glossary glossary;

    /** True when the worker script and the model files exist, so the engine can be used. */
    public static boolean available(AppConfig config) {
        Path script = Paths.get(config.getTranslateScript());
        Path model = Paths.get(config.getTranslateModelDir(), "model.safetensors");
        return Files.isRegularFile(script) && Files.isRegularFile(model);
    }

    public ProcessTextTranslator(AppConfig config, Glossary glossary) throws IOException {
        this.glossary = glossary;
        ProcessBuilder builder = new ProcessBuilder(config.getPythonPath(), config.getTranslateScript(),
            "--model-dir", config.getTranslateModelDir(), "--device", config.getWhisperDevice());
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);          // model loading progress goes to the job log
        this.process = builder.start();
        this.toWorker = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.fromWorker = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        logger.info("Translation worker started ({})", config.getTranslateModelDir());
    }

    @Override
    public synchronized List<String> translate(List<String> texts, Language source, Language target) throws IOException {
        JsonObject request = new JsonObject();
        request.addProperty("target", target.code());
        JsonArray array = new JsonArray();
        texts.forEach(array::add);
        request.add("texts", array);
        toWorker.write(request.toString());
        toWorker.newLine();
        toWorker.flush();

        String line;
        try {
            // a stuck CUDA worker must not hang the job (and the GPU lease it holds) forever
            line = reader.submit(fromWorker::readLine).get(REQUEST_TIMEOUT.toMinutes(), TimeUnit.MINUTES);
        } catch (java.util.concurrent.TimeoutException e) {
            process.destroyForcibly();
            throw new IOException("Translation worker did not answer within " + REQUEST_TIMEOUT.toMinutes() + " minutes");
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IOException("Translation worker failed: " + e.getCause(), e.getCause());
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the translation worker", e);
        }
        if (line == null) throw new IOException("Translation worker ended unexpectedly");
        JsonObject answer = JsonParser.parseString(line).getAsJsonObject();
        if (answer.has("error")) throw new IOException("Translation worker: " + answer.get("error").getAsString());
        List<String> out = new ArrayList<>();
        for (JsonElement element : answer.getAsJsonArray("translations")) {
            String text = element.getAsString().trim();
            out.add(glossary == null ? text : glossary.apply(text));
        }
        return out;
    }

    @Override
    public void close() {
        reader.shutdownNow();
        try {
            JsonObject quit = new JsonObject();
            quit.addProperty("command", "quit");
            toWorker.write(quit.toString());
            toWorker.newLine();
            toWorker.flush();
            toWorker.close();
            if (!process.waitFor(30, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (Exception e) {
            process.destroyForcibly();
        }
    }
}
