package com.example.autoact;

import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;

public class NodeFinder {

    // Returns first matching node across all windows (falls back to active
    // window if getWindows() is empty). Caller must recycle if non-null.
    public static AccessibilityNodeInfo find(AutomationService svc,
                                             String by, String value) {
        FindSpec s = new FindSpec();
        s.by = by;
        s.value = value;
        s.limit = 1;
        return findFirst(svc, s);
    }

    /**
     * Spec-based find (region/ancestorId/limit). Returns the first matching node
     * or null. Caller must recycle if non-null.
     */
    public static AccessibilityNodeInfo findFirst(AutomationService svc, FindSpec spec) {
        if (svc == null || spec == null || spec.by == null) return null;

        // focused: no value needed, uses findFocus() on active root
        if (Step.BY_FOCUSED.equals(spec.by)) {
            AccessibilityNodeInfo n = findFocused(svc);
            if (n == null) return null;
            if (!spec.accepts(n)) {
                try { n.recycle(); } catch (Throwable ignored) {}
                return null;
            }
            return n;
        }
        if (spec.value == null) return null;

        List<AccessibilityWindowInfo> ws = null;
        try { ws = svc.getWindows(); } catch (Throwable ignored) {}
        if (ws != null && !ws.isEmpty()) {
            for (int i = 0; i < ws.size(); i++) {
                AccessibilityWindowInfo w = ws.get(i);
                if (w == null) continue;
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) continue;
                AccessibilityNodeInfo hit = findInSpec(root, spec);
                try { root.recycle(); } catch (Throwable ignored) {}
                if (hit != null) return hit;
            }
        }
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) return null;
        AccessibilityNodeInfo hit = findInSpec(root, spec);
        try { root.recycle(); } catch (Throwable ignored) {}
        return hit;
    }

    /** Root scope for spec: honors ancestorId (subtree anchor) if set. */
    private static AccessibilityNodeInfo findInSpec(AccessibilityNodeInfo root, FindSpec spec) {
        AccessibilityNodeInfo scope = root;
        boolean scopeIsAnchor = false;
        if (spec.ancestorId != null && spec.ancestorId.length() > 0) {
            AccessibilityNodeInfo anchor = firstById(root, spec.ancestorId);
            if (anchor == null) return null;
            scope = anchor;
            scopeIsAnchor = true;
        }
        AccessibilityNodeInfo hit = findIn(scope, spec.by, spec.value, spec);
        if (scopeIsAnchor) try { scope.recycle(); } catch (Throwable ignored) {}
        return hit;
    }

    private static AccessibilityNodeInfo firstById(AccessibilityNodeInfo root, String id) {
        List<AccessibilityNodeInfo> hits = null;
        try { hits = root.findAccessibilityNodeInfosByViewId(id); } catch (Throwable ignored) {}
        if (hits == null || hits.isEmpty()) return null;
        AccessibilityNodeInfo first = hits.get(0);
        for (int i = 1; i < hits.size(); i++) {
            try { hits.get(i).recycle(); } catch (Throwable ignored) {}
        }
        return first;
    }

    private static AccessibilityNodeInfo findFocused(AutomationService svc) {
        AccessibilityNodeInfo root = null;
        try { root = svc.getRootInActiveWindow(); } catch (Throwable ignored) {}
        if (root == null) return null;
        AccessibilityNodeInfo f = null;
        try { f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT); }
        catch (Throwable ignored) {}
        if (f == null) {
            try { f = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY); }
            catch (Throwable ignored) {}
        }
        try { root.recycle(); } catch (Throwable ignored) {}
        return f;
    }

    private static AccessibilityNodeInfo findIn(AccessibilityNodeInfo root,
                                                String by, String value, FindSpec spec) {
        if (root == null) return null;

        // Fast paths using Android's built-in indexes.
        if (Step.BY_ID.equals(by)) {
            List<AccessibilityNodeInfo> hits =
                    root.findAccessibilityNodeInfosByViewId(value);
            if (hits == null || hits.isEmpty()) return null;
            AccessibilityNodeInfo chosen = null;
            for (int i = 0; i < hits.size(); i++) {
                AccessibilityNodeInfo n = hits.get(i);
                if (n == null) continue;
                if (chosen == null && spec.accepts(n)) { chosen = n; continue; }
                try { n.recycle(); } catch (Throwable ignored) {}
            }
            return chosen;
        }
        if (Step.BY_TEXT.equals(by) || Step.BY_TEXT_CONTAINS.equals(by)) {
            List<AccessibilityNodeInfo> hits =
                    root.findAccessibilityNodeInfosByText(value);
            if (hits == null || hits.isEmpty()) return null;
            AccessibilityNodeInfo exact = null, first = null;
            for (int i = 0; i < hits.size(); i++) {
                AccessibilityNodeInfo n = hits.get(i);
                if (n == null) continue;
                if (!spec.accepts(n)) continue;
                if (first == null) first = n;
                CharSequence t = n.getText();
                if (exact == null && t != null && value.equals(t.toString()))
                    exact = n;
            }
            AccessibilityNodeInfo chosen = exact != null ? exact : first;
            for (int i = 0; i < hits.size(); i++) {
                AccessibilityNodeInfo n = hits.get(i);
                if (n != null && n != chosen) {
                    try { n.recycle(); } catch (Throwable ignored) {}
                }
            }
            return chosen;
        }
        if (Step.BY_TEXT_EXACT.equals(by)) {
            List<AccessibilityNodeInfo> hits =
                    root.findAccessibilityNodeInfosByText(value);
            if (hits == null) return null;
            AccessibilityNodeInfo chosen = null;
            for (int i = 0; i < hits.size(); i++) {
                AccessibilityNodeInfo n = hits.get(i);
                if (n == null) continue;
                if (chosen == null && spec.accepts(n)) {
                    CharSequence t = n.getText();
                    if (t != null && value.equals(t.toString())) {
                        chosen = n;
                        continue;
                    }
                }
                try { n.recycle(); } catch (Throwable ignored) {}
            }
            return chosen;
        }

        // Walking searches: desc / descContains / classContains / idContains.
        return walk(root, by, value, spec);
    }

    private static AccessibilityNodeInfo walk(AccessibilityNodeInfo n,
                                              String by, String value, FindSpec spec) {
        if (n == null) return null;
        if (spec.subtreeSkippable(n)) return null;
        if (matches(n, by, value) && spec.accepts(n)) {
            return AccessibilityNodeInfo.obtain(n);
        }
        int nc = n.getChildCount();
        for (int i = 0; i < nc; i++) {
            AccessibilityNodeInfo c = null;
            try { c = n.getChild(i); } catch (Throwable ignored) {}
            if (c == null) continue;
            AccessibilityNodeInfo hit = walk(c, by, value, spec);
            try { c.recycle(); } catch (Throwable ignored) {}
            if (hit != null) return hit;
        }
        return null;
    }

    private static boolean matches(AccessibilityNodeInfo n, String by, String value) {
        if (Step.BY_DESC.equals(by)) {
            CharSequence d = n.getContentDescription();
            return d != null && value.equals(d.toString());
        }
        if (Step.BY_DESC_CONTAINS.equals(by)) {
            CharSequence d = n.getContentDescription();
            return d != null && d.toString().contains(value);
        }
        if (Step.BY_CLASS_CONTAINS.equals(by)) {
            CharSequence c = n.getClassName();
            return c != null && c.toString().contains(value);
        }
        if (Step.BY_ID_CONTAINS.equals(by)) {
            String id = null;
            try { id = n.getViewIdResourceName(); } catch (Throwable ignored) {}
            return id != null && id.contains(value);
        }
        return false;
    }

    // Walks up parents to find a clickable ancestor (for TextView inside a button-shell).
    public static AccessibilityNodeInfo toClickable(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 6 && cur != null; i++) {
            if (cur.isClickable()) return cur;
            AccessibilityNodeInfo p = cur.getParent();
            if (cur != n) { try { cur.recycle(); } catch (Throwable ignored) {} }
            cur = p;
        }
        return cur;
    }
}
