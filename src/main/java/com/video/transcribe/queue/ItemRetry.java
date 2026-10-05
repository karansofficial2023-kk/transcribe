package com.video.transcribe.queue;

import com.video.transcribe.util.Sleeper;

/** Runs one queue item up to N times: transient faults are retried after a growing pause, final verdicts and successes stop at once. */
public final class ItemRetry {

    public record Outcome(boolean success, String error, boolean finalFailure, int attempts) {
    }

    @FunctionalInterface
    public interface Attempt {
        Outcome run(int attempt) throws Exception;
    }

    private ItemRetry() {
    }

    public static Outcome run(int maxAttempts, long backoffMillis, Sleeper sleeper, Attempt attempt) throws InterruptedException {
        Outcome last = new Outcome(false, "not started", false, 0);
        for (int n = 1; n <= Math.max(1, maxAttempts); n++) {
            try {
                last = attempt.run(n);
            } catch (InterruptedException interrupted) {
                throw interrupted;
            } catch (Exception e) {
                String message = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
                last = new Outcome(false, message, FailureKind.isFinal(message), n);
            }
            if (last.success() || last.finalFailure() || n >= maxAttempts) {
                return new Outcome(last.success(), last.error(), last.finalFailure(), n);
            }
            sleeper.sleep(backoffMillis * n);
        }
        return last;
    }
}
