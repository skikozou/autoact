package com.example.autoact;

import android.content.Context;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.List;

// Read-only introspection handlers: health / screen / top / status / find.
// Also owns node-serialization helpers (boundsJson / nodeJson) shared with
// ApiHandler for step-report contexts.
class ApiInfo {

    // -------------------- meta info --------------------

    static JSONObject health(AutomationService svc) throws JSONException {
        JSONObject r = new JSONObject();
        r.put("ok", true);
        r.put("service", "autoact");
        r.put("version", 1);
        r.put("running", svc.isRunnerRunning());
        r.put("port", ApiServer.PORT);
        return r;
    }

    static JSONObject screen(AutomationService svc) throws JSONException {
        DisplayMetrics dm = svc.getResources().getDisplayMetrics();
        int rot = 0;
        try {
            WindowManager wm = (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                Display d = wm.getDefaultDisplay();
                if (d != null) rot = d.getRotation();
            }
        } catch (Throwable ignored) {}
        JSONObject r = new JSONObject();
        r.put("width", dm.widthPixels);
        r.put("height", dm.heightPixels);
        r.put("density", dm.density);
        r.put("densityDpi", dm.densityDpi);
        r.put("rotation", rotationName(rot));
        r.put("rotationDeg", rot * 90);
        return r;
    }

    private static String rotationName(int r) {
        if (r == Surface.ROTATION_90)  return "90";
        if (r == Surface.ROTATION_180) return "180";
        if (r == Surface.ROTATION_270) return "270";
        return "0";
    }

    static JSONObject top(AutomationService svc) throws JSONException {
        JSONObject r = new JSONObject();
        AccessibilityNodeInfo root = null;
        try { root = svc.getRootInActiveWindow(); } catch (Throwable ignored) {}
        String pkg = null;
        if (root != null) {
            CharSequence p = root.getPackageName();
            if (p != null) pkg = p.toString();
            try { root.recycle(); } catch (Throwable ignored) {}
        }
        r.put("package", pkg == null ? JSONObject.NULL : pkg);

        JSONArray windows = new JSONArray();
        try {
            List<AccessibilityWindowInfo> ws = svc.getWindows();
            if (ws != null) {
                for (int i = 0; i < ws.size(); i++) {
                    AccessibilityWindowInfo w = ws.get(i);
                    if (w == null) continue;
                    JSONObject wo = new JSONObject();
                    wo.put("id", w.getId());
                    wo.put("type", w.getType());
                    wo.put("layer", w.getLayer());
                    wo.put("active", w.isActive());
                    wo.put("focused", w.isFocused());
                    Rect b = new Rect();
                    try { w.getBoundsInScreen(b); } catch (Throwable ignored) {}
                    wo.put("bounds", boundsJson(b));
                    AccessibilityNodeInfo wr = w.getRoot();
                    if (wr != null) {
                        CharSequence wp = wr.getPackageName();
                        wo.put("package", wp == null ? JSONObject.NULL : wp.toString());
                        try { wr.recycle(); } catch (Throwable ignored) {}
                    }
                    windows.put(wo);
                }
            }
        } catch (Throwable ignored) {}
        r.put("windows", windows);
        return r;
    }

    static JSONObject status(AutomationService svc) throws JSONException {
        JSONObject r = new JSONObject();
        r.put("running", svc.isRunnerRunning());
        String t = svc.currentTargetPackage();
        r.put("targetPackage", t == null ? JSONObject.NULL : t);
        File log = Logger.currentFile();
        r.put("log", log == null ? JSONObject.NULL : log.getAbsolutePath());
        return r;
    }

    // -------------------- find --------------------
    //
    // Accepts a FindSpec (by/value/ancestorId/region/visibleOnly/clickableOnly/limit).
    // Default limit is FindSpec.DEFAULT_LIMIT (30). Set limit=1 for fastest first-hit.
    // region: {x1,y1,x2,y2} or {x,y,w,h} — walk skips nodes whose bounds fall outside.
    // ancestorId: pkg:id/foo — restrict search to that node's subtree.

    static JSONObject find(AutomationService svc, JSONObject a) throws JSONException {
        FindSpec spec = FindSpec.fromJson(a);

        JSONArray out = new JSONArray();
        try {
            List<AccessibilityWindowInfo> ws = svc.getWindows();
            if (ws != null) {
                for (int i = 0; i < ws.size(); i++) {
                    AccessibilityWindowInfo w = ws.get(i);
                    if (w == null) continue;
                    AccessibilityNodeInfo root = w.getRoot();
                    if (root == null) continue;
                    collectSpec(root, spec, out);
                    try { root.recycle(); } catch (Throwable ignored) {}
                    if (out.length() >= spec.limit) break;
                }
            }
        } catch (Throwable ignored) {}
        if (out.length() == 0) {
            AccessibilityNodeInfo root = svc.getRootInActiveWindow();
            if (root != null) {
                collectSpec(root, spec, out);
                try { root.recycle(); } catch (Throwable ignored) {}
            }
        }
        JSONObject r = new JSONObject();
        r.put("matches", out);
        r.put("count", out.length());
        return r;
    }

    /** Root scope for spec — honors ancestorId if set, else full root subtree. */
    private static void collectSpec(AccessibilityNodeInfo root, FindSpec spec, JSONArray out)
            throws JSONException {
        if (root == null || out.length() >= spec.limit) return;
        AccessibilityNodeInfo scope = root;
        boolean scopeIsAnchor = false;
        if (spec.ancestorId != null && spec.ancestorId.length() > 0) {
            List<AccessibilityNodeInfo> anchorHits = null;
            try { anchorHits = root.findAccessibilityNodeInfosByViewId(spec.ancestorId); }
            catch (Throwable ignored) {}
            if (anchorHits == null || anchorHits.isEmpty()) return;
            scope = anchorHits.get(0);
            for (int i = 1; i < anchorHits.size(); i++) {
                try { anchorHits.get(i).recycle(); } catch (Throwable ignored) {}
            }
            scopeIsAnchor = true;
        }
        try {
            collect(scope, spec, out);
        } finally {
            if (scopeIsAnchor) try { scope.recycle(); } catch (Throwable ignored) {}
        }
    }

    private static void collect(AccessibilityNodeInfo root, FindSpec spec, JSONArray out)
            throws JSONException {
        if (root == null || out.length() >= spec.limit) return;
        String by = spec.by;
        String value = spec.value;

        if ("id".equals(by)) {
            List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByViewId(value);
            if (hits == null) return;
            for (int i = 0; i < hits.size(); i++) {
                AccessibilityNodeInfo n = hits.get(i);
                if (n == null) continue;
                if (spec.accepts(n) && out.length() < spec.limit)
                    out.put(nodeJson(n));
                try { n.recycle(); } catch (Throwable ignored) {}
            }
            return;
        }
        if ("text".equals(by) || "textContains".equals(by) || "textExact".equals(by)) {
            List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText(value);
            if (hits == null) return;
            for (int i = 0; i < hits.size(); i++) {
                AccessibilityNodeInfo n = hits.get(i);
                if (n == null) continue;
                boolean pass = true;
                if ("textExact".equals(by)) {
                    CharSequence t = n.getText();
                    pass = t != null && value.equals(t.toString());
                }
                if (pass && spec.accepts(n) && out.length() < spec.limit)
                    out.put(nodeJson(n));
                try { n.recycle(); } catch (Throwable ignored) {}
            }
            return;
        }
        if ("focused".equals(by)) {
            AccessibilityNodeInfo f = null;
            try { f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT); }
            catch (Throwable ignored) {}
            if (f == null) {
                try { f = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY); }
                catch (Throwable ignored) {}
            }
            if (f != null && spec.accepts(f))
                out.put(nodeJson(f));
            if (f != null) try { f.recycle(); } catch (Throwable ignored) {}
            return;
        }
        walkMatch(root, spec, out);
    }

    private static void walkMatch(AccessibilityNodeInfo n, FindSpec spec, JSONArray out)
            throws JSONException {
        if (n == null || out.length() >= spec.limit) return;
        if (spec.subtreeSkippable(n)) return;
        String by = spec.by;
        String value = spec.value;
        boolean hit = false;
        if ("desc".equals(by)) {
            CharSequence d = n.getContentDescription();
            hit = d != null && value.equals(d.toString());
        } else if ("descContains".equals(by)) {
            CharSequence d = n.getContentDescription();
            hit = d != null && d.toString().contains(value);
        } else if ("classContains".equals(by)) {
            CharSequence c = n.getClassName();
            hit = c != null && c.toString().contains(value);
        } else if ("idContains".equals(by)) {
            String id = null;
            try { id = n.getViewIdResourceName(); } catch (Throwable ignored) {}
            hit = id != null && id.contains(value);
        }
        if (hit && spec.accepts(n)) {
            out.put(nodeJson(n));
            if (out.length() >= spec.limit) return;
        }
        int nc = n.getChildCount();
        for (int i = 0; i < nc; i++) {
            AccessibilityNodeInfo c = null;
            try { c = n.getChild(i); } catch (Throwable ignored) {}
            if (c == null) continue;
            walkMatch(c, spec, out);
            try { c.recycle(); } catch (Throwable ignored) {}
            if (out.length() >= spec.limit) return;
        }
    }

    // -------------------- node serialization helpers --------------------

    static JSONObject nodeJson(AccessibilityNodeInfo n) throws JSONException {
        JSONObject o = new JSONObject();
        CharSequence t = n.getText();
        CharSequence d = n.getContentDescription();
        CharSequence cls = n.getClassName();
        CharSequence pkg = n.getPackageName();
        String id = null;
        try { id = n.getViewIdResourceName(); } catch (Throwable ignored) {}
        o.put("text",     t == null ? JSONObject.NULL : t.toString());
        o.put("desc",     d == null ? JSONObject.NULL : d.toString());
        o.put("class",    cls == null ? JSONObject.NULL : cls.toString());
        o.put("package",  pkg == null ? JSONObject.NULL : pkg.toString());
        o.put("id",       id == null ? JSONObject.NULL : id);
        o.put("clickable",  n.isClickable());
        o.put("longClickable", n.isLongClickable());
        o.put("checkable",  n.isCheckable());
        o.put("checked",    n.isChecked());
        o.put("enabled",    n.isEnabled());
        o.put("focused",    n.isFocused());
        o.put("scrollable", n.isScrollable());
        o.put("visible",    n.isVisibleToUser());
        Rect b = new Rect();
        try { n.getBoundsInScreen(b); } catch (Throwable ignored) {}
        o.put("bounds", boundsJson(b));
        o.put("centerX", (b.left + b.right) / 2);
        o.put("centerY", (b.top + b.bottom) / 2);
        return o;
    }

    static JSONObject boundsJson(Rect b) throws JSONException {
        JSONObject r = new JSONObject();
        r.put("left",   b.left);
        r.put("top",    b.top);
        r.put("right",  b.right);
        r.put("bottom", b.bottom);
        r.put("width",  b.right - b.left);
        r.put("height", b.bottom - b.top);
        return r;
    }
}
