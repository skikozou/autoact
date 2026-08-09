package com.example.autoact;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
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

// Stateless dispatcher. NDJSON in / NDJSON out.
//
// Request : {"cmd":"<name>", "args":{...}, "id":<any>?}
// Reply OK: {"id":..,"ok":true, "result":{...}, "tookMs":N}
// Reply NG: {"id":..,"ok":false,"error":"..."}
public class ApiHandler {

    public static String handle(AutomationService svc, String line) {
        Object id = null;
        JSONObject req;
        try {
            req = new JSONObject(line);
            if (req.has("id")) id = req.opt("id");
        } catch (Throwable t) {
            return err(null, "invalid JSON: " + t);
        }
        String cmd = req.optString("cmd", "");
        JSONObject args = req.optJSONObject("args");
        if (args == null) args = new JSONObject();
        long t0 = System.currentTimeMillis();
        try {
            if (svc == null) throw new IllegalStateException("service not connected");
            Object result = dispatch(svc, cmd, args);
            JSONObject r = new JSONObject();
            if (id != null) r.put("id", id);
            r.put("ok", true);
            r.put("result", result == null ? JSONObject.NULL : result);
            r.put("tookMs", System.currentTimeMillis() - t0);
            return r.toString();
        } catch (Throwable t) {
            String msg = t.getMessage();
            if (msg == null) msg = t.toString();
            return err(id, msg);
        }
    }

    private static String err(Object id, String msg) {
        try {
            JSONObject r = new JSONObject();
            if (id != null) r.put("id", id);
            r.put("ok", false);
            r.put("error", msg);
            return r.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"encoding failed\"}";
        }
    }

    // Returns a JSONObject (map result) or a JSONArray etc.
    private static Object dispatch(AutomationService svc, String cmd, JSONObject a)
            throws Throwable {
        if (cmd == null || cmd.length() == 0)
            throw new IllegalArgumentException("cmd required");

        if ("health".equals(cmd))     return health(svc);
        if ("screen".equals(cmd))     return screen(svc);
        if ("top".equals(cmd))        return top(svc);
        if ("status".equals(cmd))     return status(svc);
        if ("find".equals(cmd))       return find(svc, a);
        if ("stop".equals(cmd))       return stop(svc);
        if ("run".equals(cmd))        return runByName(svc, a, false);
        if ("run_sync".equals(cmd))   return runByName(svc, a, true);
        if ("exec".equals(cmd))       return execScenario(svc, a, true);
        if ("exec_async".equals(cmd)) return execScenario(svc, a, false);
        if ("install".equals(cmd))    return install(svc, a);
        if ("uninstall".equals(cmd))  return uninstall(svc, a);
        if ("install_status".equals(cmd)) return installStatus();
        if ("packages".equals(cmd))   return packages(svc, a);
        if ("launch".equals(cmd))     return launch(svc, a);
        if ("openUrl".equals(cmd))    return openUrl(svc, a);
        if ("intent".equals(cmd))     return intent(svc, a);

        // Key alias: {"cmd":"key","args":{"name":"back"}} -> forwarded as op "back"
        if ("key".equals(cmd)) {
            String name = a.optString("name", "");
            if (name.length() == 0) throw new IllegalArgumentException("key: name required");
            return runStep(svc, name, a);
        }

        // Everything else: treat cmd as a Step.op. Args map 1:1 to Step fields.
        return runStep(svc, cmd, a);
    }

    // -------------------- info --------------------

    private static JSONObject health(AutomationService svc) throws JSONException {
        JSONObject r = new JSONObject();
        r.put("ok", true);
        r.put("service", "autoact");
        r.put("version", 1);
        r.put("running", svc.isRunnerRunning());
        r.put("port", ApiServer.PORT);
        return r;
    }

    private static JSONObject screen(AutomationService svc) throws JSONException {
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

    private static JSONObject top(AutomationService svc) throws JSONException {
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

    private static JSONObject status(AutomationService svc) throws JSONException {
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
    // Default limit is 30 for backwards-compat. Set limit=1 for fastest first-hit.
    // region: {x1,y1,x2,y2} or {x,y,w,h} — walk skips nodes whose bounds fall outside.
    // ancestorId: pkg:id/foo — restrict search to that node's subtree.

    private static JSONObject find(AutomationService svc, JSONObject a) throws JSONException {
        FindSpec spec = FindSpec.fromJson(a);
        // Preserve the old default of 30 for callers that don't pass limit.
        if (!a.has("limit")) spec.limit = 30;

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
        // walking searches: desc/descContains/classContains/idContains
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

    private static JSONObject nodeJson(AccessibilityNodeInfo n) throws JSONException {
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

    private static JSONObject boundsJson(Rect b) throws JSONException {
        JSONObject r = new JSONObject();
        r.put("left",   b.left);
        r.put("top",    b.top);
        r.put("right",  b.right);
        r.put("bottom", b.bottom);
        r.put("width",  b.right - b.left);
        r.put("height", b.bottom - b.top);
        return r;
    }

    // -------------------- run / exec / stop --------------------

    private static JSONObject runByName(AutomationService svc, JSONObject a, boolean sync)
            throws Throwable {
        String name = a.optString("name", "");
        String path = a.optString("path", "");
        if (path.length() == 0) {
            if (name.length() == 0) throw new IllegalArgumentException("name or path required");
            String n = name.toLowerCase().endsWith(".json") ? name : (name + ".json");
            path = new File(Storage.scenariosDir(), n).getAbsolutePath();
        }
        File f = new File(path);
        if (!f.exists()) throw new IllegalStateException("file not found: " + path);
        svc.runScenarioFromFile(path);
        JSONObject r = new JSONObject();
        r.put("started", true);
        r.put("path", path);
        if (sync) awaitRunner(svc, a.optLong("waitMs", 300000L));
        r.put("running", svc.isRunnerRunning());
        return r;
    }

    private static JSONObject execScenario(AutomationService svc, JSONObject a, boolean sync)
            throws Throwable {
        JSONObject sc = a.optJSONObject("scenario");
        if (sc == null) sc = a; // allow passing scenario fields directly at top level
        Scenario s = ScenarioParser.parseString(sc.toString());
        // Top-level withReport takes precedence if set (over anything inside `scenario`)
        if (a.has("withReport")) s.withReport = a.optBoolean("withReport", false);
        svc.runScenario(s);
        ScenarioRunner runner = svc.currentRunner();

        JSONObject r = new JSONObject();
        r.put("started", true);
        r.put("name", s.name);
        if (sync && runner != null) {
            runner.awaitFinished(a.optLong("waitMs", 300000L));
            r.put("ok", runner.overallOk());
            if (s.withReport) {
                JSONArray arr = new JSONArray();
                List<StepReport> reports = runner.report();
                for (int i = 0; i < reports.size(); i++) {
                    arr.put(reports.get(i).toJson());
                }
                r.put("report", arr);
            }
        }
        r.put("running", svc.isRunnerRunning());
        return r;
    }

    // Legacy fallback (kept for run_sync which awaits an unowned runner reference).
    // For exec, awaitFinished latch is used directly (see execScenario above).
    private static void awaitRunner(AutomationService svc, long maxMs)
            throws InterruptedException {
        ScenarioRunner r = svc.currentRunner();
        if (r != null) { r.awaitFinished(maxMs); return; }
        // no runner started (parse fail etc.): brief settle then bail
        Thread.sleep(50L);
    }

    private static JSONObject stop(AutomationService svc) throws JSONException {
        svc.stopScenario("api");
        JSONObject r = new JSONObject();
        r.put("stopped", true);
        return r;
    }

    // -------------------- install / uninstall / packages / launch --------------------

    private static JSONObject install(AutomationService svc, JSONObject a) throws Throwable {
        String path = a.optString("path", "");
        if (path.length() == 0) throw new IllegalArgumentException("path required");
        File apk = new File(path);
        if (!apk.exists()) throw new IllegalStateException("apk not found: " + path);
        String tag = a.optString("tag", "");
        int sessionId = Installer.install(svc, apk, tag);
        JSONObject r = new JSONObject();
        r.put("sessionId", sessionId);
        r.put("path", path);
        r.put("size", apk.length());
        r.put("note", "commit dispatched; user consent UI may appear (use a11y ops to auto-approve)");
        return r;
    }

    private static JSONObject uninstall(AutomationService svc, JSONObject a) throws Throwable {
        String pkg = a.optString("package", "");
        if (pkg.length() == 0) throw new IllegalArgumentException("package required");
        String tag = a.optString("tag", "");
        Installer.uninstall(svc, pkg, tag);
        JSONObject r = new JSONObject();
        r.put("package", pkg);
        r.put("note", "uninstall dispatched; user consent UI may appear");
        return r;
    }

    private static JSONObject installStatus() throws JSONException {
        JSONObject r = new JSONObject();
        r.put("status",  InstallStatusReceiver.lastStatus());
        r.put("code",    InstallStatusReceiver.lastCode());
        String p = InstallStatusReceiver.lastPackage();
        String t = InstallStatusReceiver.lastTag();
        r.put("package", p == null ? JSONObject.NULL : p);
        r.put("tag",     t == null ? JSONObject.NULL : t);
        return r;
    }

    private static JSONObject packages(AutomationService svc, JSONObject a) throws JSONException {
        String filter = a.optString("filter", "");
        boolean userOnly = a.optBoolean("userOnly", false);
        PackageManager pm = svc.getPackageManager();
        List<PackageInfo> ps = pm.getInstalledPackages(0);
        JSONArray arr = new JSONArray();
        if (ps != null) {
            for (int i = 0; i < ps.size(); i++) {
                PackageInfo p = ps.get(i);
                if (p == null || p.packageName == null) continue;
                if (filter.length() > 0 && !p.packageName.contains(filter)) continue;
                ApplicationInfo ai = p.applicationInfo;
                boolean isSystem = ai != null
                        && (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                if (userOnly && isSystem) continue;
                JSONObject o = new JSONObject();
                o.put("package",     p.packageName);
                o.put("versionName", p.versionName == null ? JSONObject.NULL : p.versionName);
                o.put("versionCode", p.versionCode);
                o.put("system",      isSystem);
                if (ai != null) {
                    CharSequence label = pm.getApplicationLabel(ai);
                    o.put("label", label == null ? JSONObject.NULL : label.toString());
                }
                arr.put(o);
            }
        }
        JSONObject r = new JSONObject();
        r.put("packages", arr);
        r.put("count", arr.length());
        return r;
    }

    private static JSONObject launch(AutomationService svc, JSONObject a) throws Throwable {
        String pkg = a.optString("package", "");
        if (pkg.length() == 0) throw new IllegalArgumentException("package required");
        PackageManager pm = svc.getPackageManager();
        Intent i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) throw new IllegalStateException("no launch intent for: " + pkg);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        svc.startActivity(i);
        JSONObject r = new JSONObject();
        r.put("package", pkg);
        r.put("launched", true);
        return r;
    }

    private static JSONObject openUrl(AutomationService svc, JSONObject a) throws Throwable {
        String url = a.optString("url", "");
        if (url.length() == 0) throw new IllegalArgumentException("url required");
        String pkg = a.optString("package", "");
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        if (pkg.length() > 0) i.setPackage(pkg);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        svc.startActivity(i);
        JSONObject r = new JSONObject();
        r.put("url", url);
        r.put("package", pkg.length() == 0 ? JSONObject.NULL : pkg);
        r.put("opened", true);
        return r;
    }

    // Generic Intent dispatch. args:
    //   action     "android.intent.action.VIEW" (defaults to VIEW)
    //   data       "https://..."      -> setData
    //   type       "text/plain"       -> setType
    //   package    "com.foo"          -> setPackage
    //   component  "com.foo/.Bar"     -> setComponent
    //   category   ["android.intent.category.LAUNCHER"]
    //   flags      integer            -> addFlags
    //   extras     {"key":<any>}      -> putExtra (string/int/bool/double)
    //   kind       "activity"|"broadcast"|"service" (default activity)
    private static JSONObject intent(AutomationService svc, JSONObject a) throws Throwable {
        String action = a.optString("action", Intent.ACTION_VIEW);
        Intent i = new Intent(action);
        String data = a.optString("data", "");
        if (data.length() > 0) i.setData(Uri.parse(data));
        String type = a.optString("type", "");
        if (type.length() > 0) {
            if (data.length() > 0) i.setDataAndType(Uri.parse(data), type);
            else i.setType(type);
        }
        String pkg = a.optString("package", "");
        if (pkg.length() > 0) i.setPackage(pkg);
        String comp = a.optString("component", "");
        if (comp.length() > 0) {
            int slash = comp.indexOf('/');
            if (slash > 0) {
                String cp = comp.substring(0, slash);
                String cn = comp.substring(slash + 1);
                if (cn.startsWith(".")) cn = cp + cn;
                i.setClassName(cp, cn);
            }
        }
        JSONArray cats = a.optJSONArray("category");
        if (cats != null) for (int k = 0; k < cats.length(); k++)
            i.addCategory(cats.optString(k));
        int flags = a.optInt("flags", 0);
        i.addFlags(flags | Intent.FLAG_ACTIVITY_NEW_TASK);
        JSONObject extras = a.optJSONObject("extras");
        if (extras != null) {
            java.util.Iterator<String> keys = extras.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                Object v = extras.opt(k);
                if (v instanceof Integer)      i.putExtra(k, ((Integer) v).intValue());
                else if (v instanceof Long)    i.putExtra(k, ((Long) v).longValue());
                else if (v instanceof Boolean) i.putExtra(k, ((Boolean) v).booleanValue());
                else if (v instanceof Double)  i.putExtra(k, ((Double) v).doubleValue());
                else                           i.putExtra(k, v == null ? "" : v.toString());
            }
        }
        String kind = a.optString("kind", "activity");
        if ("broadcast".equals(kind))      svc.sendBroadcast(i);
        else if ("service".equals(kind))   svc.startService(i);
        else                                svc.startActivity(i);
        JSONObject r = new JSONObject();
        r.put("kind", kind);
        r.put("action", action);
        r.put("dispatched", true);
        return r;
    }

    // -------------------- single-step forward --------------------
    //
    // Build a Step from args (keys are Step field names), invoke ActionExecutor
    // synchronously on this connection thread.
    private static JSONObject runStep(AutomationService svc, String op, JSONObject a)
            throws Throwable {
        Step st = stepFromArgs(op, a);
        long t0 = System.currentTimeMillis();
        boolean ok = ActionExecutor.execute(svc, st);
        JSONObject r = new JSONObject();
        r.put("op", op);
        r.put("stepOk", ok);
        r.put("tookMs", System.currentTimeMillis() - t0);
        if (!ok) throw new IllegalStateException("step failed: " + op);
        return r;
    }

    private static Step stepFromArgs(String op, JSONObject a) {
        Step st = new Step();
        st.op   = op;
        st.by   = optStr(a, "by");
        st.value= optStr(a, "value");
        st.text = optStr(a, "text");
        st.tag  = optStr(a, "tag");
        st.dir  = optStr(a, "dir");
        st.dpad = optStr(a, "dpad");

        st.x  = a.optInt("x",  0);
        st.y  = a.optInt("y",  0);
        st.x1 = a.optInt("x1", 0);
        st.y1 = a.optInt("y1", 0);
        st.x2 = a.optInt("x2", 0);
        st.y2 = a.optInt("y2", 0);
        st.cx = a.optInt("cx", -1);
        st.cy = a.optInt("cy", -1);

        st.start     = a.optInt("start", 0);
        st.end       = a.optInt("end",   0);
        st.startSpan = a.optInt("startSpan", 0);
        st.endSpan   = a.optInt("endSpan",   0);
        st.row       = a.optInt("row", 0);
        st.col       = a.optInt("col", 0);

        st.ms        = a.optLong("ms", 0L);
        st.durMs     = a.optLong("durMs", 300L);
        st.holdMs    = a.optLong("holdMs", 500L);
        st.timeoutMs = a.optLong("timeoutMs", 5000L);
        st.intervalMs = a.optLong("intervalMs", 30L);
        st.retries   = a.optInt("retries", 0);
        st.progress  = (float) a.optDouble("progress", 0.0);

        st.ancestorId = optStr(a, "ancestorId");
        st.mode         = optStr(a, "mode");
        st.visibleOnly  = a.optBoolean("visibleOnly", false);
        st.clickableOnly = a.optBoolean("clickableOnly", false);
        st.limit        = a.optInt("limit", 1);
        JSONObject region = a.optJSONObject("region");
        if (region != null) st.region = FindSpec.parseRegion(region);
        return st;
    }

    /** Build a FindSpec from a Step (used by wait/find step ops). */
    static FindSpec specFromStep(Step st) {
        FindSpec s = new FindSpec();
        s.by = st.by;
        s.value = st.value;
        s.ancestorId = st.ancestorId;
        s.region = st.region;
        s.visibleOnly = st.visibleOnly;
        s.clickableOnly = st.clickableOnly;
        s.limit = st.limit <= 0 ? 1 : st.limit;
        return s;
    }

    private static String optStr(JSONObject a, String k) {
        if (!a.has(k) || a.isNull(k)) return null;
        return a.optString(k, null);
    }
}
