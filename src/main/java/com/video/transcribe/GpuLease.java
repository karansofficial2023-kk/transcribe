package com.video.transcribe;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cross-process GPU lease shared with the Python video generator (same lock file and JSON format). Whisper and Ollama on one
 * 12 GB card starve each other when another heavy job (FLUX, vision QA) runs at the same time; a job takes the lease for its
 * whole run and a second job waits instead of timing out.
 *
 * A lock is stale only when the process that wrote it no longer exists. A lock that is empty or half written is a lock being created
 * right now and gets a short grace period. Stale locks are removed by an atomic rename (never by delete-after-check), and a job
 * releases only a lock that still carries its own process id.
 */
public final class GpuLease implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(GpuLease.class);
    static final Path LOCK = Paths.get(System.getProperty("java.io.tmpdir"), "open_edu_gpu.lock");
    private static final Duration UNREADABLE_GRACE = Duration.ofSeconds(30);
    private static final Pattern PID = Pattern.compile("\"pid\"\\s*:\\s*(\\d+)");

    private final boolean held;

    private GpuLease(boolean held) {
        this.held = held;
    }

    public static GpuLease acquire(String owner, Duration maxWait) throws IOException, InterruptedException {
        Instant deadline = Instant.now().plus(maxWait);
        boolean announced = false;
        while (true) {
            try {
                String info = "{\"pid\": " + ProcessHandle.current().pid() + ", \"owner\": \"" + owner + "\", \"since\": \""
                    + Instant.now() + "\"}";
                Files.writeString(LOCK, info, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                return new GpuLease(true);
            } catch (FileAlreadyExistsException busy) {
                if (stale()) {
                    stealStaleLock();
                    continue;
                }
                if (Instant.now().isAfter(deadline)) {
                    throw new IOException("GPU is busy (another job holds " + LOCK + ")");
                }
                if (!announced) {
                    logger.info("Waiting for the GPU lease held by another job ({})", LOCK);
                    announced = true;
                }
                Thread.sleep(15_000);
            }
        }
    }

    /** The process id recorded in the lock, or empty when the file is missing or cannot be read yet. */
    static OptionalLong ownerPid() {
        try {
            Matcher matcher = PID.matcher(Files.readString(LOCK, StandardCharsets.UTF_8));
            return matcher.find() ? OptionalLong.of(Long.parseLong(matcher.group(1))) : OptionalLong.empty();
        } catch (IOException | RuntimeException e) {
            return OptionalLong.empty();
        }
    }

    static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    static boolean stale() {
        OptionalLong pid = ownerPid();
        if (pid.isPresent()) {
            return !alive(pid.getAsLong());
        }
        try {
            return Duration.between(Files.getLastModifiedTime(LOCK).toInstant(), Instant.now()).compareTo(UNREADABLE_GRACE) > 0;
        } catch (IOException e) {
            return false;               // it disappeared while we looked: nothing to remove
        }
    }

    private static void stealStaleLock() {
        Path grave = LOCK.resolveSibling(LOCK.getFileName() + ".stale." + ProcessHandle.current().pid());
        try {
            Files.move(LOCK, grave, StandardCopyOption.ATOMIC_MOVE);
        } catch (NoSuchFileException | AtomicMoveNotSupportedException e) {
            return;                     // another waiter got there first
        } catch (IOException e) {
            return;
        }
        try {
            Matcher matcher = PID.matcher(Files.readString(grave, StandardCharsets.UTF_8));
            if (matcher.find() && alive(Long.parseLong(matcher.group(1)))) {
                Files.move(grave, LOCK, StandardCopyOption.ATOMIC_MOVE);       // it was a live lock after all: put it back
                return;
            }
        } catch (IOException | RuntimeException e) {
            // unreadable: treat as stale
        }
        try {
            Files.deleteIfExists(grave);
        } catch (IOException e) {
            logger.debug("Could not remove {}: {}", grave, e.getMessage());
        }
    }

    @Override
    public void close() {
        if (!held) return;
        try {
            OptionalLong pid = ownerPid();
            if (pid.isPresent() && pid.getAsLong() == ProcessHandle.current().pid()) {
                Files.deleteIfExists(LOCK);
            }
        } catch (IOException e) {
            logger.warn("Could not release GPU lease: {}", e.getMessage());
        }
    }
}
