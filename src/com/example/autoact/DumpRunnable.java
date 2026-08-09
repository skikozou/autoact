package com.example.autoact;

public class DumpRunnable implements Runnable {
    private final String tag;
    public DumpRunnable(String tag) { this.tag = tag; }
    @Override public void run() {
        AutomationService svc = AutomationService.getInstance();
        if (svc == null) {
            Logger.write("dump requested but service not connected");
            return;
        }
        UiDumper.dumpActive(svc, tag);
    }
}
