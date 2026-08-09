package com.example.autoact;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.List;

// Side-effecting handlers: scenario run/exec/stop, package install/uninstall/launch,
// intent dispatch. Grouped opposite ApiInfo (read-only introspection).
class ApiActions {

    // -------------------- run / exec / stop --------------------

    static JSONObject runByName(AutomationService svc, JSONObject a, boolean sync)
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

    static JSONObject execScenario(AutomationService svc, JSONObject a, boolean sync)
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

    static JSONObject stop(AutomationService svc) throws JSONException {
        svc.stopScenario("api");
        JSONObject r = new JSONObject();
        r.put("stopped", true);
        return r;
    }

    // -------------------- install / uninstall / packages / launch --------------------

    static JSONObject install(AutomationService svc, JSONObject a) throws Throwable {
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

    static JSONObject uninstall(AutomationService svc, JSONObject a) throws Throwable {
        String pkg = a.optString("package", "");
        if (pkg.length() == 0) throw new IllegalArgumentException("package required");
        String tag = a.optString("tag", "");
        Installer.uninstall(svc, pkg, tag);
        JSONObject r = new JSONObject();
        r.put("package", pkg);
        r.put("note", "uninstall dispatched; user consent UI may appear");
        return r;
    }

    static JSONObject installStatus() throws JSONException {
        InstallStatusReceiver.Snapshot s = InstallStatusReceiver.current();
        JSONObject r = new JSONObject();
        r.put("status",  s.status);
        r.put("code",    s.code);
        r.put("package", s.pkg == null ? JSONObject.NULL : s.pkg);
        r.put("tag",     s.tag == null ? JSONObject.NULL : s.tag);
        return r;
    }

    static JSONObject packages(AutomationService svc, JSONObject a) throws JSONException {
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

    static JSONObject launch(AutomationService svc, JSONObject a) throws Throwable {
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

    static JSONObject openUrl(AutomationService svc, JSONObject a) throws Throwable {
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
    static JSONObject intent(AutomationService svc, JSONObject a) throws Throwable {
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
}
