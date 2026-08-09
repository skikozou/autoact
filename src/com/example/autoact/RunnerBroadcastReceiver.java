package com.example.autoact;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class RunnerBroadcastReceiver extends BroadcastReceiver {

    private final AutomationService svc;

    public RunnerBroadcastReceiver(AutomationService svc) {
        this.svc = svc;
    }

    @Override
    public void onReceive(Context ctx, Intent i) {
        if (i == null || i.getAction() == null) return;
        String a = i.getAction();
        if (AutomationService.ACTION_RUN_SCENARIO.equals(a)) {
            String path = i.getStringExtra(AutomationService.EXTRA_SCENARIO_PATH);
            svc.runScenarioFromFile(path);
        } else if (AutomationService.ACTION_STOP_SCENARIO.equals(a)) {
            svc.stopScenario("broadcast");
        } else if (AutomationService.ACTION_DUMP_UI.equals(a)) {
            String tag = i.getStringExtra(AutomationService.EXTRA_TAG);
            UiDumper.dumpActive(svc, tag == null ? "broadcast" : tag);
        }
    }
}
