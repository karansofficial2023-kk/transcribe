package com.video.transcribe.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;
import com.video.transcribe.util.Sleeper;

/** A video must survive the faults Ollama really produces: restarts, 5xx errors, garbled or empty answers. No GPU or model needed. */
class OllamaClientResilienceTest {

    /** Minimal Ollama: replies are scripted; the last one repeats. */
    static final class Stub {
        final ConcurrentLinkedQueue<int[]> unused = new ConcurrentLinkedQueue<>();
        final List<Object[]> script = new ArrayList<>();
        final AtomicInteger requests = new AtomicInteger();
        final AtomicInteger unloads = new AtomicInteger();
        HttpServer server;
        int port;

        Stub reply(int status, String body) {
            script.add(new Object[] {status, body});
            return this;
        }

        void start(int fixedPort) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", fixedPort), 0);
            port = server.getAddress().getPort();
            server.createContext("/", exchange -> send(exchange, 200, "Ollama is running"));
            server.createContext("/api/generate", exchange -> {
                String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (request.contains("\"keep_alive\":0")) {                 // the unload call
                    unloads.incrementAndGet();
                    send(exchange, 200, "{\"response\":\"\",\"done\":true}");
                    return;
                }
                int n = requests.getAndIncrement();
                Object[] reply = script.get(Math.min(n, script.size() - 1));
                send(exchange, (Integer) reply[0], (String) reply[1]);
            });
            server.start();
        }

        boolean running() {
            return server != null;
        }

        String url() {
            return "http://127.0.0.1:" + port;
        }

        private static void send(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }

        void stop() {
            if (server != null) {
                server.stop(0);
            }
        }
    }

    private Stub stub;

    @AfterEach
    void shutDown() {
        if (stub != null) {
            stub.stop();
        }
    }

    private static String ok(String text) {
        return "{\"response\":\"" + text + "\",\"done\":true,\"done_reason\":\"stop\"}";
    }

    private OllamaClient client(Stub stub, Path cache, List<Long> slept, int attempts) {
        return new OllamaClient(stub.url(), "qwen3:14b", cache.toString(), attempts, 100, 5_000, slept::add);
    }

    @Test
    void serverErrorsAreRetriedUntilTheAnswerComes(@TempDir Path cache) throws Exception {
        stub = new Stub().reply(500, "boom").reply(503, "overloaded").reply(200, ok("hello"));
        stub.start(0);
        List<Long> slept = new ArrayList<>();
        assertEquals("hello", client(stub, cache, slept, 4).generate("system", "prompt", null));
        assertEquals(3, stub.requests.get());
        assertEquals(List.of(100L, 200L), slept);                              // back-off doubles
    }

    @Test
    void garbledAndEmptyAnswersAreAskedAgain(@TempDir Path cache) throws Exception {
        stub = new Stub().reply(200, "<html>bad gateway</html>").reply(200, "{}").reply(200, "{\"response\":null}")
            .reply(200, ok("   ")).reply(200, ok("finally"));
        stub.start(0);
        assertEquals("finally", client(stub, cache, new ArrayList<>(), 6).generate("s", "p", null));
        assertEquals(5, stub.requests.get());
    }

    @Test
    void aRequestOllamaRejectsIsNotRetried(@TempDir Path cache) throws Exception {
        stub = new Stub().reply(404, "{\"error\":\"model 'nope' not found\"}");
        stub.start(0);
        IOException failure = assertThrows(IOException.class, () -> client(stub, cache, new ArrayList<>(), 4).generate("s", "p", null));
        assertTrue(failure.getMessage().contains("404"), failure.getMessage());
        assertEquals(1, stub.requests.get());
    }

    @Test
    void givesUpWithAClearMessageAfterTheLastAttempt(@TempDir Path cache) throws Exception {
        stub = new Stub().reply(500, "still broken");
        stub.start(0);
        IOException failure = assertThrows(IOException.class, () -> client(stub, cache, new ArrayList<>(), 3).generate("s", "p", null));
        assertTrue(failure.getMessage().contains("after 3 attempts"), failure.getMessage());
        assertTrue(failure.getMessage().contains("still broken"), failure.getMessage());
        assertEquals(3, stub.requests.get());
    }

    @Test
    void waitsForOllamaToComeBackWhenItIsDown(@TempDir Path cache) throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        stub = new Stub().reply(200, ok("back again"));
        Stub late = stub;
        List<Long> slept = new ArrayList<>();
        Sleeper startsOllamaOnFirstWait = millis -> {
            slept.add(millis);
            if (!late.running()) {
                try {
                    late.start(port);                                           // "ollama serve" is started while the pipeline waits
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        stub.port = port;
        OllamaClient client = new OllamaClient("http://127.0.0.1:" + port, "qwen3:14b", cache.toString(), 4, 100, 5_000, startsOllamaOnFirstWait);
        assertEquals("back again", client.generate("s", "p", null));
        assertTrue(stub.running());
        assertEquals(1, stub.requests.get());                                  // the first real request after it returned succeeded
    }

    @Test
    void aMemoryFaultFreesVramBeforeTheRetry(@TempDir Path cache) throws Exception {
        stub = new Stub().reply(500, "{\"error\":\"model runner has unexpectedly stopped, this may be due to resource limitations or an internal error\"}")
            .reply(200, ok("recovered"));
        stub.start(0);
        assertEquals("recovered", client(stub, cache, new ArrayList<>(), 3).generate("s", "p", null));
        assertEquals(1, stub.unloads.get());
    }

    @Test
    void goodAnswersAreCachedButFailedAttemptsAreNot(@TempDir Path cache) throws Exception {
        stub = new Stub().reply(500, "boom").reply(200, ok("cached once"));
        stub.start(0);
        OllamaClient client = client(stub, cache, new ArrayList<>(), 3);
        assertEquals("cached once", client.generate("s", "same question", null));
        int after = stub.requests.get();
        assertEquals("cached once", client.generate("s", "same question", null));
        assertEquals(after, stub.requests.get());                              // second call came from the cache, no request
        assertFalse(java.nio.file.Files.list(cache).anyMatch(p -> p.toString().endsWith(".tmp")));
    }
}
