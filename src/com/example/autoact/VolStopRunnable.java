package com.example.autoact;

public class VolStopRunnable implements Runnable {
    private final AutomationService svc;
    public VolStopRunnable(AutomationService svc) { this.svc = svc; }
    @Override public void run() {
        Logger.write("VOL long-press → STOP");
        svc.stopScenario("vol-longpress");
    }
}
