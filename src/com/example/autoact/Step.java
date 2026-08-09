package com.example.autoact;

import android.graphics.Rect;

public class Step {

    // ---- Gesture ops (dispatchGesture) ----
    public static final String OP_TAP           = "tap";
    public static final String OP_SWIPE         = "swipe";
    public static final String OP_DRAG          = "drag";
    public static final String OP_CURVE_SWIPE   = "curveSwipe";
    public static final String OP_PINCH         = "pinch";
    public static final String OP_MULTI_SWIPE   = "multiSwipe";

    // ---- Node-lookup + performAction ----
    public static final String OP_CLICK         = "click";
    public static final String OP_LONG_CLICK    = "longClick";
    public static final String OP_CONTEXT_CLICK = "contextClick";
    public static final String OP_SET_TEXT      = "setText";
    public static final String OP_SET_SELECTION = "setSelection";
    public static final String OP_SET_PROGRESS  = "setProgress";
    public static final String OP_COPY          = "copy";
    public static final String OP_PASTE         = "paste";
    public static final String OP_CUT           = "cut";
    public static final String OP_SELECT        = "select";
    public static final String OP_CLEAR_SELECT  = "clearSelection";
    public static final String OP_FOCUS         = "focus";
    public static final String OP_CLEAR_FOCUS   = "clearFocus";
    public static final String OP_A11Y_FOCUS    = "a11yFocus";
    public static final String OP_EXPAND        = "expand";
    public static final String OP_COLLAPSE      = "collapse";
    public static final String OP_DISMISS       = "dismiss";
    public static final String OP_SHOW_ON_SCR   = "showOnScreen";
    public static final String OP_IME_ENTER     = "imeEnter";

    // ---- Node-lookup + scrolling ----
    public static final String OP_SCROLL          = "scroll";
    public static final String OP_SCROLL_UP       = "scrollUp";
    public static final String OP_SCROLL_DOWN     = "scrollDown";
    public static final String OP_SCROLL_LEFT     = "scrollLeft";
    public static final String OP_SCROLL_RIGHT    = "scrollRight";
    public static final String OP_PAGE_UP         = "pageUp";
    public static final String OP_PAGE_DOWN       = "pageDown";
    public static final String OP_PAGE_LEFT       = "pageLeft";
    public static final String OP_PAGE_RIGHT      = "pageRight";
    public static final String OP_SCROLL_TO_POS   = "scrollToPos";

    // ---- Wait / assert ----
    public static final String OP_WAIT_FOR      = "waitFor";
    public static final String OP_WAIT_FOR_GONE = "waitForGone";
    public static final String OP_WAIT_CLICK    = "waitClick"; // wait then click matched node
    public static final String OP_ASSERT        = "assert";
    public static final String OP_ASSERT_GONE   = "assertGone";
    public static final String OP_SLEEP         = "sleep";

    // ---- Global actions ----
    public static final String OP_BACK              = "back";
    public static final String OP_HOME              = "home";
    public static final String OP_RECENTS           = "recents";
    public static final String OP_NOTIFICATIONS     = "notifications";
    public static final String OP_QUICK_SETTINGS    = "quickSettings";
    public static final String OP_POWER_DIALOG      = "powerDialog";
    public static final String OP_LOCK_SCREEN       = "lockScreen";
    public static final String OP_SPLIT_SCREEN      = "splitScreen";
    public static final String OP_ALL_APPS          = "allApps";
    public static final String OP_DPAD              = "dpad";
    public static final String OP_A11Y_SHORTCUT     = "a11yShortcut";
    public static final String OP_A11Y_BUTTON       = "a11yButton";
    public static final String OP_HEADSET_HOOK      = "headsetHook";
    public static final String OP_SCREENSHOT_SYS    = "screenshotSystem";

    // ---- Screenshot / dump ----
    public static final String OP_SCREENSHOT        = "screenshot";
    public static final String OP_DUMP_UI           = "dumpUi";

    // ---- Selector strategies for `by` ----
    public static final String BY_TEXT           = "text";
    public static final String BY_TEXT_EXACT     = "textExact";
    public static final String BY_TEXT_CONTAINS  = "textContains";
    public static final String BY_ID             = "id";
    public static final String BY_ID_CONTAINS    = "idContains";
    public static final String BY_DESC           = "desc";
    public static final String BY_DESC_CONTAINS  = "descContains";
    public static final String BY_CLASS_CONTAINS = "classContains";
    public static final String BY_FOCUSED        = "focused";

    // ---- Fields (all optional; used per-op) ----
    public String op;
    public String by;
    public String value;
    public String text;
    public String tag;
    public String dir;   // scroll: forward/backward
    public String dpad;  // up/down/left/right/center

    public int x, y;
    public int x1, y1, x2, y2;
    public int cx, cy;             // curve control point / pinch center
    public int start, end;         // setSelection
    public int startSpan, endSpan; // pinch (px)
    public int row, col;           // scrollToPos

    public long ms;
    public long durMs;
    public long holdMs;            // drag pre-hold
    public long timeoutMs;
    public long intervalMs;        // waitFor/waitClick poll interval (poll mode)
    public int retries;
    public float progress;         // setProgress 0..1

    // ---- Find/wait filter fields (A/B) ----
    public String ancestorId;      // find/wait: restrict subtree to this a11y id
    public Rect region;            // find/wait: node bounds must fit inside
    public String mode;            // wait: "event" | "poll" (default: event)
    public boolean visibleOnly;    // find/wait
    public boolean clickableOnly;  // find/wait
    public int limit = 1;          // find early-return count (walk stops once reached)

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(op);
        if (by != null) sb.append(" by=").append(by).append("=\"").append(value).append('"');
        if (text != null) sb.append(" text=\"").append(text).append('"');
        if (ms > 0) sb.append(" ms=").append(ms);
        if (durMs > 0) sb.append(" dur=").append(durMs);
        if (tag != null) sb.append(" tag=").append(tag);
        return sb.toString();
    }
}
