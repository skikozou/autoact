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

        switch (cmd) {
            // Read-only introspection.
            case "health": return ApiInfo.health(svc);
            case "screen": return ApiInfo.screen(svc);
            case "top":    return ApiInfo.top(svc);
            case "status": return ApiInfo.status(svc);
            case "find":   return ApiInfo.find(svc, a);

            // Scenario lifecycle.
            case "stop":       return ApiActions.stop(svc);
            case "run":        return ApiActions.runByName(svc, a, false);
            case "run_sync":   return ApiActions.runByName(svc, a, true);
            case "exec":       return ApiActions.execScenario(svc, a, true);
            case "exec_async": return ApiActions.execScenario(svc, a, false);

            // Package management.
            case "install":        return ApiActions.install(svc, a);
            case "uninstall":      return ApiActions.uninstall(svc, a);
            case "install_status": return ApiActions.installStatus();
            case "packages":       return ApiActions.packages(svc, a);
            case "launch":         return ApiActions.launch(svc, a);

            // Intent dispatch.
            case "openUrl": return ApiActions.openUrl(svc, a);
            case "intent":  return ApiActions.intent(svc, a);

            // Key alias: {"cmd":"key","args":{"name":"back"}} -> forwarded as op "back"
            case "key": {
                String name = a.optString("name", "");
                if (name.length() == 0) throw new IllegalArgumentException("key: name required");
                return runStep(svc, name, a);
            }

            // Everything else: treat cmd as a Step.op. Args map 1:1 to Step fields.
            default: return runStep(svc, cmd, a);
        }
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
