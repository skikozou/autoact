package com.example.autoact;

import org.json.JSONException;
import org.json.JSONObject;

// Stateless dispatcher. NDJSON in / NDJSON out.
//
// Request : {"cmd":"<name>", "args":{...}, "id":<any>?}
// Reply OK: {"id":..,"ok":true, "result":{...}, "tookMs":N}
// Reply NG: {"id":..,"ok":false,"error":"..."}
//
// Handler implementations live in ApiInfo (read-only introspection) and
// ApiActions (side effects). This file owns entry, routing, and the fallback
// "cmd as Step.op" path.
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

        // Read-only introspection.
        if ("health".equals(cmd))     return ApiInfo.health(svc);
        if ("screen".equals(cmd))     return ApiInfo.screen(svc);
        if ("top".equals(cmd))        return ApiInfo.top(svc);
        if ("status".equals(cmd))     return ApiInfo.status(svc);
        if ("find".equals(cmd))       return ApiInfo.find(svc, a);

        // Scenario lifecycle.
        if ("stop".equals(cmd))       return ApiActions.stop(svc);
        if ("run".equals(cmd))        return ApiActions.runByName(svc, a, false);
        if ("run_sync".equals(cmd))   return ApiActions.runByName(svc, a, true);
        if ("exec".equals(cmd))       return ApiActions.execScenario(svc, a, true);
        if ("exec_async".equals(cmd)) return ApiActions.execScenario(svc, a, false);

        // Package management.
        if ("install".equals(cmd))        return ApiActions.install(svc, a);
        if ("uninstall".equals(cmd))      return ApiActions.uninstall(svc, a);
        if ("install_status".equals(cmd)) return ApiActions.installStatus();
        if ("packages".equals(cmd))       return ApiActions.packages(svc, a);
        if ("launch".equals(cmd))         return ApiActions.launch(svc, a);

        // Intent dispatch.
        if ("openUrl".equals(cmd))    return ApiActions.openUrl(svc, a);
        if ("intent".equals(cmd))     return ApiActions.intent(svc, a);

        // Key alias: {"cmd":"key","args":{"name":"back"}} -> forwarded as op "back"
        if ("key".equals(cmd)) {
            String name = a.optString("name", "");
            if (name.length() == 0) throw new IllegalArgumentException("key: name required");
            return runStep(svc, name, a);
        }

        // Everything else: treat cmd as a Step.op. Args map 1:1 to Step fields.
        return runStep(svc, cmd, a);
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
        try {
            return ScenarioParser.parseStepWithOp(a, op, 5000L);
        } catch (JSONException e) {
            // parseStepWithOp doesn't require any specific JSON field when op
            // is supplied; this branch is defensive.
            throw new IllegalArgumentException("bad step args: " + e.getMessage());
        }
    }
}
