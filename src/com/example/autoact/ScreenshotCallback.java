package com.example.autoact;

import android.accessibilityservice.AccessibilityService;

import java.util.concurrent.CountDownLatch;

// API 30+ only. Caller must SDK-guard.
public class ScreenshotCallback implements AccessibilityService.TakeScreenshotCallback {
    public final CountDownLatch latch = new CountDownLatch(1);
    public volatile AccessibilityService.ScreenshotResult result;
    public volatile int errorCode = -1;

    @Override
    public void onSuccess(AccessibilityService.ScreenshotResult r) {
        this.result = r;
        latch.countDown();
    }

    @Override
    public void onFailure(int errorCode) {
        this.errorCode = errorCode;
        latch.countDown();
    }
}
