package com.example.autoact;

import android.view.accessibility.AccessibilityNodeInfo;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Event-driven wait for an a11y node matching a FindSpec.
 *
 * Lifecycle:
 *   1. Caller creates WaitTask(svc, spec, timeoutMs, mode).
 *   2. Caller invokes await():
 *      - immediately runs one sync find (short-circuit if already present).
 *      - registers self with AutomationService.registerWaiter (for event wakes).
 *      - blocks on latch until match or timeout.
 *      - unregisters and returns matched AccessibilityNodeInfo or null.
 *
 * Two modes:
 *   - "event" (default): woken by onAccessibilityEvent → checkNow(). No polling.
 *   - "poll" : plus a periodic re-check every intervalMs. Use when the target UI
 *              may not emit a11y events (rare — most widgets do). Also acts as
 *              a safety net.
 *
 * The service invokes checkNow() on the a11y thread. It's cheap when there are no
 * waiters registered (list is empty most of the time).
 *
 * Caller is responsible for recycling the returned node.
 *
 * Top-level class (not nested) to satisfy d8's inner-class limitation.
 */
public class WaitTask {

    private static final long DEFAULT_POLL_INTERVAL_MS = 50L;

    private final AutomationService svc;
    private final FindSpec spec;
    private final long timeoutMs;
    private final long intervalMs;
    private final boolean pollMode;

    private final CountDownLatch latch = new CountDownLatch(1);
    private volatile AccessibilityNodeInfo result;
    private volatile boolean done;

    public WaitTask(AutomationService svc, FindSpec spec,
                    long timeoutMs, long intervalMs, String mode) {
        this.svc = svc;
        this.spec = spec;
        this.timeoutMs = Math.max(0L, timeoutMs);
        this.intervalMs = intervalMs > 0 ? intervalMs : DEFAULT_POLL_INTERVAL_MS;
        this.pollMode = "poll".equalsIgnoreCase(mode);
    }

    /** Called by AutomationService.onAccessibilityEvent (a11y thread). */
    public void checkNow() {
        if (done) return;
        try {
            AccessibilityNodeInfo n = NodeFinder.findFirst(svc, spec);
            if (n != null) complete(n);
        } catch (Throwable ignored) {}
    }

    /**
     * Block until match found or timeout. Returns matched node (caller recycles) or null.
     * Runs on the caller thread (typically ApiHandler's connection worker).
     */
    public AccessibilityNodeInfo await() throws InterruptedException {
        // 1. immediate sync check — may already be present
        AccessibilityNodeInfo initial = null;
        try { initial = NodeFinder.findFirst(svc, spec); } catch (Throwable ignored) {}
        if (initial != null) return initial;

        // 2. register for event wakes
        svc.registerWaiter(this);
        try {
            long remaining = timeoutMs;
            long start = System.currentTimeMillis();
            while (remaining > 0) {
                long waitSlice = pollMode ? Math.min(remaining, intervalMs) : remaining;
                boolean latched = latch.await(waitSlice, TimeUnit.MILLISECONDS);
                if (latched) break;
                if (pollMode) {
                    checkNow(); // fallback poll
                    if (done) break;
                }
                remaining = timeoutMs - (System.currentTimeMillis() - start);
            }
        } finally {
            svc.unregisterWaiter(this);
        }
        return result;
    }

    private synchronized void complete(AccessibilityNodeInfo n) {
        if (done) {
            try { n.recycle(); } catch (Throwable ignored) {}
            return;
        }
        done = true;
        result = n;
        latch.countDown();
    }
}
