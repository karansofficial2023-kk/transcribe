package com.video.transcribe.util;

/** Waiting, as an interface, so recovery logic (backoff, waiting for a service to return) can be tested without real delays. */
@FunctionalInterface
public interface Sleeper {

    void sleep(long millis) throws InterruptedException;

    /** The real thing. */
    Sleeper REAL = Thread::sleep;
}
