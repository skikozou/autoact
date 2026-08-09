package com.example.autoact;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ScenarioRunner implements Runnable {

    private final AutomationService svc;
    private final Scenario scenario;
    private volatile boolean stopRequested;
    private Thread thread;

    // (D) Completion latch — replaces awaitRunner's 200ms poll loop.
    private final CountDownLatch finishedLatch = new CountDownLatch(1);
    private volatile boolean overallOk;

    // (C) Per-step report. Populated only if scenario.withReport=true.
    // Read after finishedLatch fires.
    private final List<StepReport> report = new ArrayList<StepReport>();

    public ScenarioRunner(AutomationService svc, Scenario s) {
        this.svc = svc;
        this.scenario = s;
    }

    public void start() {
        thread = new Thread(this, "AutoActRunner");
        thread.setDaemon(true);
        thread.start();
    }

    public void requestStop() {
        stopRequested = true;
        if (thread != null) thread.interrupt();
    }

    public boolean isRunning() {
        return thread != null && thread.isAlive();
    }

    public String targetPackage() {
        return scenario == null ? null : scenario.targetPackage;
    }

    /** Block until run() finishes or maxMs elapses. Returns true if finished within timeout. */
    public boolean awaitFinished(long maxMs) throws InterruptedException {
        return finishedLatch.await(Math.max(0L, maxMs), TimeUnit.MILLISECONDS);
    }

    public boolean overallOk() {
        return overallOk;
    }

    public List<StepReport> report() {
        return report;
    }

    @Override
    public void run() {
        long startedAt = System.currentTimeMillis();
        Logger.write("===== RUN start: " + scenario.name
                + " target=" + scenario.targetPackage
                + " steps=" + scenario.steps.size() + " =====");

        int stepIdx = 0;
        boolean ok_all = true;
        try {
            for (Step st : scenario.steps) {
                stepIdx++;
                if (stopRequested) {
                    Logger.write("STOP requested — aborting at step " + stepIdx);
                    ok_all = false;
                    if (scenario.withReport) {
                        report.add(StepReport.aborted(stepIdx, st.op, "stop-requested"));
                    }
                    break;
                }
                if (scenario.maxDurationMs > 0
                        && System.currentTimeMillis() - startedAt > scenario.maxDurationMs) {
                    Logger.write("MAX duration exceeded — aborting at step " + stepIdx);
                    ok_all = false;
                    if (scenario.withReport) {
                        report.add(StepReport.aborted(stepIdx, st.op, "max-duration"));
                    }
                    break;
                }

                Logger.write("[" + stepIdx + "/" + scenario.steps.size() + "] " + st);

                boolean ok = false;
                int attempts = 1 + Math.max(0, st.retries);
                long stepStart = System.currentTimeMillis();
                String errMsg = null;
                int usedAttempts = 0;
                for (int a = 0; a < attempts; a++) {
                    if (stopRequested) break;
                    usedAttempts = a + 1;
                    try {
                        ok = ActionExecutor.execute(svc, st);
                        errMsg = null;
                    } catch (InterruptedException ie) {
                        Logger.write("  interrupted");
                        ok_all = false;
                        Thread.currentThread().interrupt();
                        errMsg = "interrupted";
                        break;
                    } catch (Throwable t) {
                        Logger.write("  error: " + t);
                        ok = false;
                        errMsg = String.valueOf(t);
                    }
                    if (ok) break;
                    Logger.write("  attempt " + (a + 1) + " failed");
                    try { Thread.sleep(300L); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                long stepDur = System.currentTimeMillis() - stepStart;

                if (scenario.withReport) {
                    report.add(StepReport.of(stepIdx, st.op, ok, stepDur, usedAttempts, errMsg));
                }

                if (!ok) {
                    Logger.write("  -> step failed, aborting scenario");
                    ok_all = false;
                    break;
                }
                Logger.write("  -> ok");
            }
        } finally {
            long dur = System.currentTimeMillis() - startedAt;
            this.overallOk = ok_all;
            Logger.write("===== RUN end: " + (ok_all ? "SUCCESS" : "FAILED")
                    + " (" + dur + "ms) =====");
            try { svc.onRunnerFinished(ok_all); } catch (Throwable ignored) {}
            finishedLatch.countDown();
        }
    }
}
