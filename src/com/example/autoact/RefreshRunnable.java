package com.example.autoact;

public class RefreshRunnable implements Runnable {
    private final MainActivity a;
    public RefreshRunnable(MainActivity a) { this.a = a; }
    @Override public void run() {
        if (a != null && !a.isFinishing()) a.publicRefresh();
    }
}
