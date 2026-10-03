package com.wagneralmeida.catalog.application.service;

import java.util.concurrent.atomic.AtomicBoolean;

public final class ImportLock {

    private final AtomicBoolean held = new AtomicBoolean();

    public boolean tryAcquire() {
        return held.compareAndSet(false, true);
    }

    public void release() {
        held.set(false);
    }

    public static final class ImportInProgressException extends RuntimeException {

        public ImportInProgressException() {
            super("Another import is already running");
        }
    }
}
