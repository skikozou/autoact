package com.example.autoact;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;

import java.util.concurrent.CountDownLatch;

public class GestureCallback extends AccessibilityService.GestureResultCallback {
    public final CountDownLatch latch = new CountDownLatch(1);
    public volatile boolean cancelled;
    public volatile boolean completed;

    @Override
    public void onCompleted(GestureDescription desc) {
        completed = true;
        latch.countDown();
    }

    @Override
    public void onCancelled(GestureDescription desc) {
        cancelled = true;
        latch.countDown();
    }
}
