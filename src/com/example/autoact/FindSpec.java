package com.example.autoact;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

/**
 * Selector + filters used by NodeFinder / WaitTask / ApiInfo.find.
 *
 * All fields are plain data; no accessors, no builder. Top-level class (not nested)
 * to satisfy d8's inner-class limitation.
 *
 * region semantics: node.boundsInScreen must be fully contained inside region
 * (right/bottom inclusive comparison). Missing coord defaults to unbounded
 * (Integer.MIN_VALUE / MAX_VALUE).
 *
 * ancestorId semantics: if set, walk starts from the FIRST node whose
 * getViewIdResourceName() equals ancestorId; if that anchor isn't found the
 * result is empty. Anchor lookup uses the a11y ID fast-path.
 */
public class FindSpec {
    // Default walk cap when the caller doesn't specify `limit`.
    // NodeFinder.find (first-hit) overrides to 1 explicitly.
    public static final int DEFAULT_LIMIT = 30;

    public String by;             // text/textExact/textContains/id/idContains/desc/descContains/classContains/focused
    public String value;
    public String ancestorId;     // optional subtree anchor
    public Rect region;           // optional bounds filter (null = no filter)
    public boolean visibleOnly;
    public boolean clickableOnly;
    public int limit = DEFAULT_LIMIT; // early-return count (walk stops once satisfied)

    /**
     * Parse from a JSON args object (as passed to ApiHandler cmd or a Step).
     * Recognized keys:
     *   by, value, ancestorId,
     *   region (object with x1/y1/x2/y2 or x/y/w/h),
     *   visibleOnly, clickableOnly, limit
     */
    public static FindSpec fromJson(JSONObject a) {
        FindSpec s = new FindSpec();
        s.by = a.optString("by", "text");
        s.value = a.has("value") && !a.isNull("value") ? a.optString("value", "") : "";
        if (a.has("ancestorId") && !a.isNull("ancestorId")) {
            s.ancestorId = a.optString("ancestorId", null);
        }
        s.visibleOnly = a.optBoolean("visibleOnly", false);
        s.clickableOnly = a.optBoolean("clickableOnly", false);
        s.limit = a.optInt("limit", DEFAULT_LIMIT);
        JSONObject r = a.optJSONObject("region");
        if (r != null) s.region = parseRegion(r);
        return s;
    }

    /** Region: {"x":,"y":,"w":,"h":} OR {"x1":,"y1":,"x2":,"y2":}. Missing = unbounded. */
    public static Rect parseRegion(JSONObject r) {
        Rect rc = new Rect();
        if (r.has("w") || r.has("h")) {
            int x = r.optInt("x", 0), y = r.optInt("y", 0);
            int w = r.optInt("w", Integer.MAX_VALUE / 2);
            int h = r.optInt("h", Integer.MAX_VALUE / 2);
            rc.set(x, y, x + w, y + h);
        } else {
            rc.set(r.optInt("x1", Integer.MIN_VALUE),
                   r.optInt("y1", Integer.MIN_VALUE),
                   r.optInt("x2", Integer.MAX_VALUE),
                   r.optInt("y2", Integer.MAX_VALUE));
        }
        return rc;
    }

    /** True if node's screen bounds are fully inside region (or region is null). */
    public boolean regionAccepts(AccessibilityNodeInfo n) {
        if (region == null) return true;
        Rect b = new Rect();
        try { n.getBoundsInScreen(b); } catch (Throwable ignored) { return false; }
        return b.left >= region.left && b.top >= region.top
                && b.right <= region.right && b.bottom <= region.bottom;
    }

    /** Combined accept: visibleOnly + clickableOnly + region. */
    public boolean accepts(AccessibilityNodeInfo n) {
        if (n == null) return false;
        if (visibleOnly && !n.isVisibleToUser()) return false;
        if (clickableOnly && !n.isClickable()) return false;
        if (region != null && !regionAccepts(n)) return false;
        return true;
    }

    /**
     * True if the node's subtree cannot contain any region-matching descendant, so
     * the walker can skip descending into it entirely.
     *
     * Assumes descendants are visually contained in ancestor bounds — true for
     * normal Android views. WebViews / popups may violate this; caller sets region
     * only when the target UI honors bounds containment (candidate bars, dialogs,
     * fixed toolbars, etc).
     *
     * Empty-bounds containers (measurement not yet propagated) are NOT skipped —
     * their children may still have real bounds.
     */
    public boolean subtreeSkippable(AccessibilityNodeInfo n) {
        if (region == null || n == null) return false;
        Rect b = new Rect();
        try { n.getBoundsInScreen(b); } catch (Throwable ignored) { return false; }
        if (b.isEmpty()) return false;
        return b.right  < region.left
            || b.left   > region.right
            || b.bottom < region.top
            || b.top    > region.bottom;
    }
}
