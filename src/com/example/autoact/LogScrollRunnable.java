package com.example.autoact;

import android.widget.ScrollView;

// Posted to MainActivity's log ScrollView to scroll to bottom after each
// appended log line. Not related to a11y scroll gestures — see ActionExecutor.
public class LogScrollRunnable implements Runnable {
    private final ScrollView sv;
    public LogScrollRunnable(ScrollView sv) { this.sv = sv; }
    @Override public void run() {
        if (sv != null) sv.fullScroll(android.view.View.FOCUS_DOWN);
    }
}
