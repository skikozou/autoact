package com.example.autoact;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Per-step execution record. Populated by ScenarioRunner when scenario.withReport=true
 * and returned in the exec response's `report` array.
 *
 * Top-level class (not nested/anonymous) to satisfy d8's inner-class limitation.
 */
public class StepReport {
    public int index;         // 1-based
    public String op;
    public boolean ok;
    public long tookMs;
    public int attempts;
    public String error;      // null on success

    public static StepReport of(int index, String op, boolean ok,
                                long tookMs, int attempts, String error) {
        StepReport r = new StepReport();
        r.index = index;
        r.op = op;
        r.ok = ok;
        r.tookMs = tookMs;
        r.attempts = attempts;
        r.error = error;
        return r;
    }

    public static StepReport aborted(int index, String op, String reason) {
        return of(index, op, false, 0L, 0, "aborted:" + reason);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("index", index);
        o.put("op", op == null ? JSONObject.NULL : op);
        o.put("ok", ok);
        o.put("tookMs", tookMs);
        o.put("attempts", attempts);
        if (error != null) o.put("error", error);
        return o;
    }
}
