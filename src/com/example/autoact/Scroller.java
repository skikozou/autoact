package com.example.autoact;

import android.widget.ScrollView;

public class Scroller implements Runnable {
    private final ScrollView sv;
    public Scroller(ScrollView sv) { this.sv = sv; }
    @Override public void run() {
        if (sv != null) sv.fullScroll(android.view.View.FOCUS_DOWN);
    }
}
