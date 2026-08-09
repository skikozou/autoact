package com.example.autoact;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;

// Manifest-declared, exported. Triggered via:
//   am broadcast -a com.example.autoact.RUN_SCENARIO   -n com.example.autoact/.CliReceiver --es scenario <name>
//   am broadcast -a com.example.autoact.RUN_SCENARIO   -n com.example.autoact/.CliReceiver --es scenario_path <abs>
//   am broadcast -a com.example.autoact.STOP_SCENARIO  -n com.example.autoact/.CliReceiver
//   am broadcast -a com.example.autoact.DUMP_UI        -n com.example.autoact/.CliReceiver [--es tag t] [--el delay ms]
public class CliReceiver extends BroadcastReceiver {

    private static final String TAG = "AutoAct";

    @Override
    public void onReceive(Context ctx, Intent i) {
        if (i == null || i.getAction() == null) return;
        String a = i.getAction();

        AutomationService svc = AutomationService.getInstance();
        if (svc == null) {
            Log.w(TAG, "CLI [" + a + "] rejected: AccessibilityService not connected");
            Logger.startNewLog("cli");
            Logger.write("CLI [" + a + "] rejected: service not connected");
            return;
        }

        if (AutomationService.ACTION_RUN_SCENARIO.equals(a)) {
            String path = resolveScenarioPath(i);
            if (path == null) {
                Log.w(TAG, "CLI RUN rejected: missing 'scenario' or 'scenario_path' extra");
                return;
            }
            Log.i(TAG, "CLI RUN: " + path);
            svc.runScenarioFromFile(path);
            return;
        }

        if (AutomationService.ACTION_STOP_SCENARIO.equals(a)) {
            Log.i(TAG, "CLI STOP");
            svc.stopScenario("cli");
            return;
        }

        if (AutomationService.ACTION_DUMP_UI.equals(a)) {
            String tag = i.getStringExtra(AutomationService.EXTRA_TAG);
            if (tag == null) tag = "cli";
            long delay = i.getLongExtra("delay", 0L);
            if (Logger.currentFile() == null) Logger.startNewLog("cli");
            if (delay > 0) {
                Log.i(TAG, "CLI DUMP scheduled in " + delay + "ms tag=" + tag);
                new Handler(Looper.getMainLooper())
                        .postDelayed(new DumpRunnable(tag), delay);
            } else {
                Log.i(TAG, "CLI DUMP tag=" + tag);
                UiDumper.dumpActive(svc, tag);
            }
            return;
        }

        Log.w(TAG, "CLI unknown action: " + a);
    }

    private String resolveScenarioPath(Intent i) {
        String p = i.getStringExtra(AutomationService.EXTRA_SCENARIO_PATH);
        if (p != null && p.length() > 0) return p;
        String name = i.getStringExtra("scenario");
        if (name == null || name.length() == 0) return null;
        if (!name.toLowerCase().endsWith(".json")) name = name + ".json";
        return new File(Storage.scenariosDir(), name).getAbsolutePath();
    }
}
