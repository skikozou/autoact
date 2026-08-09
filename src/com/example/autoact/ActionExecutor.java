package com.example.autoact;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.TimeUnit;

public class ActionExecutor {

    // Returns true on success, false on failure.
    public static boolean execute(AutomationService svc, Step st) throws InterruptedException {
        if (st == null || st.op == null) {
            Logger.write("  skip: null op");
            return false;
        }
        String op = st.op;

        // ---- simple / fast ----
        if (Step.OP_SLEEP.equals(op)) {
            Thread.sleep(st.ms > 0 ? st.ms : 500L);
            return true;
        }
        if (Step.OP_DUMP_UI.equals(op)) {
            return UiDumper.dumpActive(svc, st.tag) != null;
        }
        if (Step.OP_SCREENSHOT.equals(op)) {
            return takeScreenshotToFile(svc, st.tag);
        }

        // ---- global actions ----
        Integer gid = globalActionId(op, st);
        if (gid != null) {
            if (gid.intValue() == Integer.MIN_VALUE) {
                Logger.write("  global action unavailable on this SDK: " + op);
                return false;
            }
            return svc.performGlobalAction(gid.intValue());
        }

        // ---- gestures ----
        if (Step.OP_TAP.equals(op)) {
            return dispatch(svc, GestureBuilder.tap(st.x, st.y, st.durMs), st.durMs);
        }
        if (Step.OP_SWIPE.equals(op)) {
            return dispatch(svc, GestureBuilder.swipe(st.x1, st.y1, st.x2, st.y2, st.durMs),
                    st.durMs);
        }
        if (Step.OP_DRAG.equals(op)) {
            return dispatch(svc,
                    GestureBuilder.drag(st.x1, st.y1, st.x2, st.y2, st.holdMs, st.durMs),
                    st.holdMs + st.durMs + 200L);
        }
        if (Step.OP_CURVE_SWIPE.equals(op)) {
            int cx = st.cx >= 0 ? st.cx : (st.x1 + st.x2) / 2;
            int cy = st.cy >= 0 ? st.cy : (st.y1 + st.y2) / 2;
            return dispatch(svc,
                    GestureBuilder.curveSwipe(st.x1, st.y1, cx, cy, st.x2, st.y2, st.durMs),
                    st.durMs);
        }
        if (Step.OP_PINCH.equals(op)) {
            int cx = st.cx >= 0 ? st.cx : screenCenterX(svc);
            int cy = st.cy >= 0 ? st.cy : screenCenterY(svc);
            int ss = st.startSpan > 0 ? st.startSpan : 800;
            int es = st.endSpan   > 0 ? st.endSpan   : 200;
            return dispatch(svc, GestureBuilder.pinch(cx, cy, ss, es, st.durMs), st.durMs);
        }
        if (Step.OP_MULTI_SWIPE.equals(op)) {
            int offset = st.startSpan > 0 ? st.startSpan : 200;
            return dispatch(svc,
                    GestureBuilder.multiSwipe(st.x1, st.y1, st.x2, st.y2, offset, st.durMs),
                    st.durMs);
        }

        // ---- wait / assert ----
        if (Step.OP_WAIT_FOR.equals(op)) {
            AccessibilityNodeInfo n = waitForStep(svc, st);
            if (n == null) return false;
            try { n.recycle(); } catch (Throwable ignored) {}
            return true;
        }
        if (Step.OP_WAIT_CLICK.equals(op)) {
            AccessibilityNodeInfo n = waitForStep(svc, st);
            if (n == null) return false;
            AccessibilityNodeInfo target = n;
            if (!n.isClickable()) {
                AccessibilityNodeInfo cl = NodeFinder.toClickable(n);
                if (cl != null) target = cl;
            }
            boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (target != n) { try { target.recycle(); } catch (Throwable ignored) {} }
            try { n.recycle(); } catch (Throwable ignored) {}
            return ok;
        }
        if (Step.OP_WAIT_FOR_GONE.equals(op)) {
            return waitForGone(svc, st.by, st.value, st.timeoutMs);
        }
        if (Step.OP_ASSERT.equals(op)) {
            AccessibilityNodeInfo n = NodeFinder.find(svc, st.by, st.value);
            if (n == null) return false;
            try { n.recycle(); } catch (Throwable ignored) {}
            return true;
        }
        if (Step.OP_ASSERT_GONE.equals(op)) {
            AccessibilityNodeInfo n = NodeFinder.find(svc, st.by, st.value);
            if (n == null) return true;
            try { n.recycle(); } catch (Throwable ignored) {}
            return false;
        }

        // ---- node actions ----
        if (Step.OP_CLICK.equals(op))         return clickLike(svc, st, AccessibilityNodeInfo.ACTION_CLICK);
        if (Step.OP_LONG_CLICK.equals(op))    return clickLike(svc, st, AccessibilityNodeInfo.ACTION_LONG_CLICK);
        if (Step.OP_CONTEXT_CLICK.equals(op)) return nodeAction(svc, st, AccessibilityAction.ACTION_CONTEXT_CLICK.getId(), null);

        if (Step.OP_SET_TEXT.equals(op)) {
            Bundle b = new Bundle();
            b.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    st.text == null ? "" : st.text);
            return nodeAction(svc, st, AccessibilityNodeInfo.ACTION_SET_TEXT, b);
        }
        if (Step.OP_SET_SELECTION.equals(op)) {
            Bundle b = new Bundle();
            b.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, st.start);
            b.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,   st.end);
            return nodeAction(svc, st, AccessibilityNodeInfo.ACTION_SET_SELECTION, b);
        }
        if (Step.OP_SET_PROGRESS.equals(op)) {
            if (Build.VERSION.SDK_INT < 24) return false;
            Bundle b = new Bundle();
            b.putFloat(AccessibilityAction.ACTION_SET_PROGRESS.getId() + "",
                    st.progress); // unused key; real arg key below
            b.putFloat("android.view.accessibility.action.ARGUMENT_PROGRESS_VALUE",
                    st.progress);
            return nodeAction(svc, st,
                    AccessibilityAction.ACTION_SET_PROGRESS.getId(), b);
        }

        if (Step.OP_COPY.equals(op))          return nodeAction(svc, st, AccessibilityNodeInfo.ACTION_COPY,             null);
        if (Step.OP_PASTE.equals(op))         return nodeAction(svc, st, AccessibilityNodeInfo.ACTION_PASTE,            null);
        if (Step.OP_CUT.equals(op))           return nodeAction(svc, st, AccessibilityNodeInfo.ACTION_CUT,              null);
        if (Step.OP_SELECT.equals(op))        return nodeAction(svc, st, AccessibilityNodeInfo.ACTION_SELECT,           null);
        if (Step.OP_CLEAR_SELECT.equals(op))  return nodeAction(svc, st, AccessibilityNodeInfo.ACTION_CLEAR_SELECTION,  null);
        if (Step.OP_FOCUS.equals(op))         return nodeAction(svc, st, AccessibilityNodeInfo.ACTION_FOCUS,            null);
        if (Step.OP_CLEAR_FOCUS.equals(op))   return nodeAction(svc, st, AccessibilityNodeInfo.ACTION_CLEAR_FOCUS,      null);
        if (Step.OP_A11Y_FOCUS.equals(op))    return nodeAction(svc, st, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
        if (Step.OP_EXPAND.equals(op))        return nodeAction(svc, st, AccessibilityAction.ACTION_EXPAND.getId(),     null);
        if (Step.OP_COLLAPSE.equals(op))      return nodeAction(svc, st, AccessibilityAction.ACTION_COLLAPSE.getId(),   null);
        if (Step.OP_DISMISS.equals(op))       return nodeAction(svc, st, AccessibilityAction.ACTION_DISMISS.getId(),    null);
        if (Step.OP_SHOW_ON_SCR.equals(op)) {
            if (Build.VERSION.SDK_INT < 23) return false;
            return nodeAction(svc, st, AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId(), null);
        }
        if (Step.OP_IME_ENTER.equals(op)) {
            if (Build.VERSION.SDK_INT < 30) return false;
            return nodeAction(svc, st, AccessibilityAction.ACTION_IME_ENTER.getId(), null);
        }

        // ---- scrolling (node-based) ----
        if (Step.OP_SCROLL.equals(op)) {
            int action = "backward".equalsIgnoreCase(st.dir)
                    ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
            return nodeAction(svc, st, action, null);
        }
        if (Step.OP_SCROLL_UP.equals(op))    return sdk(23) && nodeAction(svc, st, AccessibilityAction.ACTION_SCROLL_UP.getId(),    null);
        if (Step.OP_SCROLL_DOWN.equals(op))  return sdk(23) && nodeAction(svc, st, AccessibilityAction.ACTION_SCROLL_DOWN.getId(),  null);
        if (Step.OP_SCROLL_LEFT.equals(op))  return sdk(23) && nodeAction(svc, st, AccessibilityAction.ACTION_SCROLL_LEFT.getId(),  null);
        if (Step.OP_SCROLL_RIGHT.equals(op)) return sdk(23) && nodeAction(svc, st, AccessibilityAction.ACTION_SCROLL_RIGHT.getId(), null);
        if (Step.OP_PAGE_UP.equals(op))      return sdk(29) && nodeAction(svc, st, AccessibilityAction.ACTION_PAGE_UP.getId(),     null);
        if (Step.OP_PAGE_DOWN.equals(op))    return sdk(29) && nodeAction(svc, st, AccessibilityAction.ACTION_PAGE_DOWN.getId(),   null);
        if (Step.OP_PAGE_LEFT.equals(op))    return sdk(29) && nodeAction(svc, st, AccessibilityAction.ACTION_PAGE_LEFT.getId(),   null);
        if (Step.OP_PAGE_RIGHT.equals(op))   return sdk(29) && nodeAction(svc, st, AccessibilityAction.ACTION_PAGE_RIGHT.getId(),  null);
        if (Step.OP_SCROLL_TO_POS.equals(op)) {
            if (Build.VERSION.SDK_INT < 23) return false;
            Bundle b = new Bundle();
            b.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT, st.row);
            b.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_COLUMN_INT, st.col);
            return nodeAction(svc, st,
                    AccessibilityAction.ACTION_SCROLL_TO_POSITION.getId(), b);
        }

        Logger.write("  unknown op: " + op);
        return false;
    }

    // ---- global action id resolver (returns Integer or null if not a global op).
    // A returned Integer.MIN_VALUE means "known op but unavailable on this SDK".
    private static Integer globalActionId(String op, Step st) {
        if (Step.OP_BACK.equals(op))              return AccessibilityService.GLOBAL_ACTION_BACK;
        if (Step.OP_HOME.equals(op))              return AccessibilityService.GLOBAL_ACTION_HOME;
        if (Step.OP_RECENTS.equals(op))           return AccessibilityService.GLOBAL_ACTION_RECENTS;
        if (Step.OP_NOTIFICATIONS.equals(op))     return AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS;
        if (Step.OP_QUICK_SETTINGS.equals(op))    return AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS;
        if (Step.OP_POWER_DIALOG.equals(op))      return AccessibilityService.GLOBAL_ACTION_POWER_DIALOG;
        if (Step.OP_HEADSET_HOOK.equals(op))      return AccessibilityService.GLOBAL_ACTION_KEYCODE_HEADSETHOOK;

        if (Step.OP_SPLIT_SCREEN.equals(op)) {
            return AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN; // API 24+, minSdk=24
        }

        if (Step.OP_LOCK_SCREEN.equals(op))
            return Build.VERSION.SDK_INT >= 28
                    ? AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN : Integer.MIN_VALUE;
        if (Step.OP_SCREENSHOT_SYS.equals(op))
            return Build.VERSION.SDK_INT >= 28
                    ? AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT : Integer.MIN_VALUE;
        if (Step.OP_A11Y_SHORTCUT.equals(op))
            return Build.VERSION.SDK_INT >= 28
                    ? AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_SHORTCUT : Integer.MIN_VALUE;
        if (Step.OP_A11Y_BUTTON.equals(op))
            return Build.VERSION.SDK_INT >= 28
                    ? AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_BUTTON : Integer.MIN_VALUE;
        if (Step.OP_ALL_APPS.equals(op))
            return Build.VERSION.SDK_INT >= 33
                    ? AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS : Integer.MIN_VALUE;
        if (Step.OP_DPAD.equals(op))
            return Build.VERSION.SDK_INT >= 34 ? dpadId(st.dpad) : Integer.MIN_VALUE;
        return null;
    }

    private static int dpadId(String dir) {
        if (dir == null) return AccessibilityService.GLOBAL_ACTION_DPAD_CENTER;
        String d = dir.toLowerCase();
        if ("up".equals(d))     return AccessibilityService.GLOBAL_ACTION_DPAD_UP;
        if ("down".equals(d))   return AccessibilityService.GLOBAL_ACTION_DPAD_DOWN;
        if ("left".equals(d))   return AccessibilityService.GLOBAL_ACTION_DPAD_LEFT;
        if ("right".equals(d))  return AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT;
        return AccessibilityService.GLOBAL_ACTION_DPAD_CENTER;
    }

    // ---- generic node action helper: locates by st.by/st.value then performs id.
    private static boolean nodeAction(AutomationService svc, Step st,
                                      int actionId, Bundle args) throws InterruptedException {
        AccessibilityNodeInfo n = waitFor(svc, st.by, st.value, st.timeoutMs);
        if (n == null) return false;
        boolean ok;
        try {
            ok = args == null ? n.performAction(actionId) : n.performAction(actionId, args);
        } finally {
            try { n.recycle(); } catch (Throwable ignored) {}
        }
        return ok;
    }

    private static boolean clickLike(AutomationService svc, Step st, int action)
            throws InterruptedException {
        AccessibilityNodeInfo n = waitFor(svc, st.by, st.value, st.timeoutMs);
        if (n == null) return false;
        AccessibilityNodeInfo target = n;
        if (!n.isClickable() && action == AccessibilityNodeInfo.ACTION_CLICK) {
            AccessibilityNodeInfo cl = NodeFinder.toClickable(n);
            if (cl != null) target = cl;
        }
        boolean ok = target.performAction(action);
        if (target != n) { try { target.recycle(); } catch (Throwable ignored) {} }
        try { n.recycle(); } catch (Throwable ignored) {}
        return ok;
    }

    public static AccessibilityNodeInfo waitFor(AutomationService svc,
                                                String by, String value,
                                                long timeoutMs)
            throws InterruptedException {
        if (by == null) return null;
        if (value == null && !Step.BY_FOCUSED.equals(by)) return null;
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while (true) {
            AccessibilityNodeInfo n = NodeFinder.find(svc, by, value);
            if (n != null) return n;
            if (System.currentTimeMillis() >= deadline) return null;
            Thread.sleep(200L);
        }
    }

    /**
     * Spec-aware wait used by OP_WAIT_FOR / OP_WAIT_CLICK step ops and by
     * top-level cmd handlers. Honors st.mode (event|poll), st.intervalMs,
     * st.ancestorId, st.region, st.limit, st.visibleOnly, st.clickableOnly.
     * Defaults to event mode.
     */
    public static AccessibilityNodeInfo waitForStep(AutomationService svc, Step st)
            throws InterruptedException {
        if (st == null || st.by == null) return null;
        if (st.value == null && !Step.BY_FOCUSED.equals(st.by)) return null;
        FindSpec spec = new FindSpec();
        spec.by = st.by;
        spec.value = st.value;
        spec.ancestorId = st.ancestorId;
        spec.region = st.region;
        spec.visibleOnly = st.visibleOnly;
        spec.clickableOnly = st.clickableOnly;
        spec.limit = st.limit <= 0 ? 1 : st.limit;
        long timeout = st.timeoutMs > 0 ? st.timeoutMs : 5000L;
        long interval = st.intervalMs > 0 ? st.intervalMs : 50L;
        String mode = st.mode == null ? "event" : st.mode;
        WaitTask w = new WaitTask(svc, spec, timeout, interval, mode);
        return w.await();
    }

    public static boolean waitForGone(AutomationService svc,
                                      String by, String value, long timeoutMs)
            throws InterruptedException {
        if (by == null) return false;
        if (value == null && !Step.BY_FOCUSED.equals(by)) return false;
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while (true) {
            AccessibilityNodeInfo n = NodeFinder.find(svc, by, value);
            if (n == null) return true;
            try { n.recycle(); } catch (Throwable ignored) {}
            if (System.currentTimeMillis() >= deadline) return false;
            Thread.sleep(200L);
        }
    }

    private static boolean dispatch(AutomationService svc,
                                    GestureDescription g, long expectedMs)
            throws InterruptedException {
        GestureCallback cb = new GestureCallback();
        Handler h = new Handler(Looper.getMainLooper());
        boolean queued = svc.dispatchGesture(g, cb, h);
        if (!queued) return false;
        cb.latch.await(expectedMs + 2000L, TimeUnit.MILLISECONDS);
        return cb.completed;
    }

    private static boolean sdk(int min) {
        return Build.VERSION.SDK_INT >= min;
    }

    private static int screenCenterX(AutomationService svc) {
        DisplayMetrics dm = svc.getResources().getDisplayMetrics();
        return dm.widthPixels / 2;
    }

    private static int screenCenterY(AutomationService svc) {
        DisplayMetrics dm = svc.getResources().getDisplayMetrics();
        return dm.heightPixels / 2;
    }

    // ---- Screenshot to PNG (API 30+) ----
    private static boolean takeScreenshotToFile(AutomationService svc, String tag)
            throws InterruptedException {
        if (Build.VERSION.SDK_INT < 30) {
            Logger.write("  screenshot requires API 30+");
            return false;
        }
        ScreenshotCallback cb = new ScreenshotCallback();
        try {
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.getMainExecutor(), cb);
        } catch (Throwable t) {
            Logger.write("  takeScreenshot threw: " + t);
            return false;
        }
        if (!cb.latch.await(4000L, TimeUnit.MILLISECONDS)) {
            Logger.write("  screenshot timeout");
            return false;
        }
        if (cb.result == null) {
            Logger.write("  screenshot failed, err=" + cb.errorCode);
            return false;
        }
        HardwareBuffer buf = cb.result.getHardwareBuffer();
        Bitmap bmp = null;
        try {
            bmp = Bitmap.wrapHardwareBuffer(buf, cb.result.getColorSpace());
            if (bmp == null) {
                Logger.write("  wrapHardwareBuffer returned null");
                return false;
            }
            String safeTag = tag == null ? "" : "_" + tag.replaceAll("[^A-Za-z0-9_.-]", "_");
            File out = new File(Storage.screenshotsDir(),
                    "shot_" + Logger.fileStamp() + safeTag + ".png");
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(out);
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.flush();
            } finally {
                if (fos != null) try { fos.close(); } catch (Throwable ignored) {}
            }
            Logger.write("  screenshot: " + out.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            Logger.write("  screenshot encode failed: " + t);
            return false;
        } finally {
            if (bmp != null) try { bmp.recycle(); } catch (Throwable ignored) {}
            try { buf.close(); } catch (Throwable ignored) {}
        }
    }
}
