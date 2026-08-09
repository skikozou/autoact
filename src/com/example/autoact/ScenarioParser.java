package com.example.autoact;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public class ScenarioParser {

    public static Scenario parseFile(File f) throws IOException, JSONException {
        FileInputStream in = null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
        }
        String src = new String(bos.toByteArray(), "UTF-8");
        return parseString(src);
    }

    public static Scenario parseString(String src) throws JSONException {
        JSONObject j = new JSONObject(src);
        Scenario s = new Scenario();
        s.name = j.optString("name", "unnamed");
        s.targetPackage = j.optString("targetPackage", "");
        s.defaultTimeoutMs = j.optLong("defaultTimeoutMs", 5000L);
        s.maxDurationMs = j.optLong("maxDurationMs", 5 * 60 * 1000L);
        s.withReport = j.optBoolean("withReport", false);

        JSONArray arr = j.optJSONArray("steps");
        if (arr == null) throw new JSONException("no 'steps' array");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            s.steps.add(parseStep(o, s.defaultTimeoutMs));
        }
        return s;
    }

    private static Step parseStep(JSONObject o, long defTimeout) throws JSONException {
        Step st = new Step();
        st.op = o.getString("op");
        st.by = o.optString("by", null);
        st.value = o.optString("value", null);
        st.text = o.optString("text", null);
        st.tag = o.optString("tag", null);
        st.dir = o.optString("dir", null);
        st.dpad = o.optString("dpad", null);

        st.x  = o.optInt("x",  0);
        st.y  = o.optInt("y",  0);
        st.x1 = o.optInt("x1", 0);
        st.y1 = o.optInt("y1", 0);
        st.x2 = o.optInt("x2", 0);
        st.y2 = o.optInt("y2", 0);
        st.cx = o.optInt("cx", -1);
        st.cy = o.optInt("cy", -1);

        st.start     = o.optInt("start", 0);
        st.end       = o.optInt("end",   0);
        st.startSpan = o.optInt("startSpan", 0);
        st.endSpan   = o.optInt("endSpan",   0);
        st.row       = o.optInt("row", 0);
        st.col       = o.optInt("col", 0);

        st.ms         = o.optLong("ms", 0L);
        st.durMs      = o.optLong("durMs", 300L);
        st.holdMs     = o.optLong("holdMs", 500L);
        st.timeoutMs  = o.optLong("timeoutMs", defTimeout);
        st.intervalMs = o.optLong("intervalMs", 30L);
        st.retries    = o.optInt("retries", 0);
        st.progress   = (float) o.optDouble("progress", 0.0);

        st.ancestorId = o.optString("ancestorId", null);
        if (o.has("ancestorId") && o.isNull("ancestorId")) st.ancestorId = null;
        st.mode          = o.optString("mode", null);
        st.visibleOnly   = o.optBoolean("visibleOnly", false);
        st.clickableOnly = o.optBoolean("clickableOnly", false);
        st.limit         = o.optInt("limit", 1);
        JSONObject region = o.optJSONObject("region");
        if (region != null) st.region = FindSpec.parseRegion(region);
        return st;
    }
}
