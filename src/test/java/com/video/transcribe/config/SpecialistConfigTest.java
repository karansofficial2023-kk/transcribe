package com.video.transcribe.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

class SpecialistConfigTest {
    /** The shipped properties must keep their path separators: a lost backslash silently disables the specialist models. */
    @Test
    void specialistAndTranslationPathsKeepTheirBackslashes() {
        AppConfig config = new AppConfig();
        for (String entry : config.getWhisperSpecialists().split(";")) {
            String path = entry.substring(entry.indexOf('=') + 1);
            assertTrue(path.contains("\\") || path.contains("/"), "path lost its separators: " + path);
        }
        assertTrue(config.getTranslateModelDir().contains("madlad400"), config.getTranslateModelDir());
        assertTrue(config.getTranslateModelDir().contains("\\") || config.getTranslateModelDir().contains("/"), config.getTranslateModelDir());
    }

    @Test
    void specialistsAreCodeEqualsPathPairsAndTheWhisperScriptExists() {
        for (String entry : new AppConfig().getWhisperSpecialists().split(";")) {
            assertTrue(entry.matches("[a-z]{2}=.+"), entry);
        }
        Path script = Paths.get(new AppConfig().getWhisperScript());
        assertTrue(Files.isRegularFile(script), "whisper script missing: " + script.toAbsolutePath());
    }
}
