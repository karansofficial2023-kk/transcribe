package com.video.transcribe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Runs against the real lock path, so it is skipped while another job (for example a render) holds the GPU. */
class GpuLeaseTest {
    private boolean someoneElseHoldsIt;

    @BeforeEach
    void notWhileTheGpuIsInUse() {
        someoneElseHoldsIt = Files.exists(GpuLease.LOCK);
        org.junit.jupiter.api.Assumptions.assumeFalse(someoneElseHoldsIt, "a real job holds the GPU lease; not touching it");
    }

    @AfterEach
    void clean() throws IOException {
        if (!someoneElseHoldsIt) Files.deleteIfExists(GpuLease.LOCK);
    }

    @Test
    void takenAndReleased() throws Exception {
        try (GpuLease lease = GpuLease.acquire("test", Duration.ofSeconds(2))) {
            assertEquals(ProcessHandle.current().pid(), GpuLease.ownerPid().getAsLong());
        }
        assertFalse(Files.exists(GpuLease.LOCK));
    }

    @Test
    void aLockOfADeadProcessIsTakenOver() throws Exception {
        Files.writeString(GpuLease.LOCK, "{\"pid\": 4000000, \"owner\": \"crashed\"}");
        assertTrue(GpuLease.stale());
        try (GpuLease lease = GpuLease.acquire("test", Duration.ofSeconds(5))) {
            assertEquals(ProcessHandle.current().pid(), GpuLease.ownerPid().getAsLong());
        }
    }

    @Test
    void aLiveOwnerIsNeverDisplacedHoweverOldTheLock() throws Exception {
        Files.writeString(GpuLease.LOCK, "{\"pid\": " + ProcessHandle.current().pid() + ", \"owner\": \"long job\"}");
        Files.setLastModifiedTime(GpuLease.LOCK, FileTime.from(Instant.now().minus(Duration.ofHours(30))));
        assertFalse(GpuLease.stale());
        assertThrows(IOException.class, () -> GpuLease.acquire("other", Duration.ofMillis(100)));
        assertTrue(Files.exists(GpuLease.LOCK));
    }

    @Test
    void aHalfWrittenLockIsGivenAGracePeriodThenTreatedAsStale() throws Exception {
        Files.writeString(GpuLease.LOCK, "");
        assertFalse(GpuLease.stale());
        Files.setLastModifiedTime(GpuLease.LOCK, FileTime.from(Instant.now().minus(Duration.ofMinutes(2))));
        assertTrue(GpuLease.stale());
    }

    @Test
    void releaseLeavesALockThatNowBelongsToSomeoneElse() throws Exception {
        GpuLease lease = GpuLease.acquire("test", Duration.ofSeconds(2));
        Files.writeString(GpuLease.LOCK, "{\"pid\": 4000000, \"owner\": \"thief\"}");
        lease.close();
        assertTrue(Files.exists(GpuLease.LOCK));
    }
}
