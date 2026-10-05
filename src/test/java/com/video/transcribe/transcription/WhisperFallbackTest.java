package com.video.transcribe.transcription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.video.transcribe.model.TranscriptData;

/** The real failure seen on this machine: "Library cublas64_12.dll is not found or cannot be loaded" on the GPU. */
class WhisperFallbackTest {

    private static final String FAKE_SCRIPT = String.join("\n",
        "import argparse, json, sys",
        "p = argparse.ArgumentParser()",
        "p.add_argument('audio'); p.add_argument('--model'); p.add_argument('--device', default='cuda'); p.add_argument('--output')",
        "p.add_argument('--language'); p.add_argument('--specialists'); p.add_argument('--hotwords-file')",
        "a = p.parse_args()",
        "if a.device == 'cuda':",
        "    print('RuntimeError: Library cublas64_12.dll is not found or cannot be loaded'); sys.exit(1)",
        "if a.device == 'broken':",
        "    print('always fails'); sys.exit(2)",
        "json.dump({'text': 'ran on ' + a.device, 'language': 'en', 'duration': 3.0, 'segments': []}, open(a.output, 'w'))",
        "");

    private Path script(Path dir) throws IOException {
        Path file = dir.resolve("fake_whisper.py");
        Files.writeString(file, FAKE_SCRIPT, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void aGpuFailureIsRerunOnTheCpuAndTheVideoContinues(@TempDir Path dir) throws Exception {
        LocalWhisperTranscriber whisper = new LocalWhisperTranscriber("python", script(dir).toString(), "base", "cuda", 2);
        TranscriptData result = whisper.transcribe(dir.resolve("a.wav"), "en", dir.resolve("out.json"));
        assertEquals("ran on cpu", result.getFullText());
    }

    @Test
    void whenTheCpuFailsToo_theErrorOfTheLastAttemptIsReported(@TempDir Path dir) throws Exception {
        Path failing = dir.resolve("always_fails.py");
        Files.writeString(failing, "import sys\nprint('input is not audio'); sys.exit(2)\n", StandardCharsets.UTF_8);
        LocalWhisperTranscriber whisper = new LocalWhisperTranscriber("python", failing.toString(), "base", "cuda", 2);
        IOException failure = assertThrows(IOException.class, () -> whisper.transcribe(dir.resolve("a.wav"), "en", dir.resolve("out.json")));
        assertTrue(failure.getMessage().contains("input is not audio"), failure.getMessage());
    }

    @Test
    void theCpuFallbackCanBeSwitchedOff(@TempDir Path dir) throws Exception {
        System.setProperty("whisper.cpu-fallback", "false");
        try {
            LocalWhisperTranscriber whisper = new LocalWhisperTranscriber("python", script(dir).toString(), "base", "cuda", 2);
            IOException failure = assertThrows(IOException.class, () -> whisper.transcribe(dir.resolve("a.wav"), "en", dir.resolve("out.json")));
            assertTrue(failure.getMessage().contains("cublas"), failure.getMessage());
        } finally {
            System.clearProperty("whisper.cpu-fallback");
        }
    }
}
