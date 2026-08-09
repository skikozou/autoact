package com.example.autoact;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class AutomationService extends AccessibilityService {

    public static final String TAG = "AutoAct";

    public static final String ACTION_RUN_SCENARIO  = "com.example.autoact.RUN_SCENARIO";
    public static final String ACTION_STOP_SCENARIO = "com.example.autoact.STOP_SCENARIO";
    public static final String ACTION_DUMP_UI       = "com.example.autoact.DUMP_UI";
    public static final String EXTRA_SCENARIO_PATH  = "scenario_path";
    public static final String EXTRA_TAG            = "tag";

    public static final long VOL_LONG_PRESS_MS = 800L;

    private static AutomationService instance;

    private Handler handler;
    private RunnerBroadcastReceiver receiver;
    private VolStopRunnable volStop;
    private ScenarioRunner runner;
    private ApiServer apiServer;

    // (B) Active event-driven waits. Notified on every a11y event.
    // Kept small (typically 0 or 1); iteration is cheap.
    private final List<WaitTask> waiters = new ArrayList<WaitTask>();

    public static AutomationService getInstance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    | AccessibilityEvent.TYPE_WINDOWS_CHANGED;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                       | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                       | AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            info.notificationTimeout = 100;
            setServiceInfo(info);
        }
        instance = this;
        handler = new Handler(Looper.getMainLooper());
        volStop = new VolStopRunnable(this);

        receiver = new RunnerBroadcastReceiver(this);
        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_RUN_SCENARIO);
        f.addAction(ACTION_STOP_SCENARIO);
        f.addAction(ACTION_DUMP_UI);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, f);
        }

        apiServer = new ApiServer(this);
        apiServer.start();

        Log.i(TAG, "AutomationService connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        // (B) Notify any active waiters — cheap when list is empty.
        WaitTask[] snapshot = null;
        synchronized (waiters) {
            if (!waiters.isEmpty()) {
                snapshot = waiters.toArray(new WaitTask[0]);
            }
        }
        if (snapshot != null) {
            for (int i = 0; i < snapshot.length; i++) {
                try { snapshot[i].checkNow(); } catch (Throwable ignored) {}
            }
        }

        if (runner == null || !runner.isRunning()) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        String target = runner.targetPackage();
        if (target == null || target.length() == 0) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        String pkgStr = pkg.toString();
        // ignore common system UI / input methods
        if (pkgStr.startsWith("com.android.systemui")) return;
        if (pkgStr.startsWith("android")) return;
        if (pkgStr.equals(getPackageName())) return;
        if (!target.equals(pkgStr)) {
            Logger.write("foreground=" + pkgStr + " != target=" + target
                    + " → STOP");
            stopScenario("wrong-foreground:" + pkgStr);
        }
    }

    public void registerWaiter(WaitTask t) {
        if (t == null) return;
        synchronized (waiters) { waiters.add(t); }
    }

    public void unregisterWaiter(WaitTask t) {
        if (t == null) return;
        synchronized (waiters) { waiters.remove(t); }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event == null) return false;
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_VOLUME_UP
                && code != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false;
        }
        if (runner == null || !runner.isRunning()) return false;
        int action = event.getAction();
        if (action == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                handler.removeCallbacks(volStop);
                handler.postDelayed(volStop, VOL_LONG_PRESS_MS);
            }
        } else if (action == KeyEvent.ACTION_UP) {
            handler.removeCallbacks(volStop);
        }
        // returning true consumes the key (prevents volume change during run)
        return true;
    }

    public synchronized void runScenarioFromFile(String path) {
        if (runner != null && runner.isRunning()) {
            Logger.write("run refused: already running");
            return;
        }
        if (path == null) {
            Logger.write("run refused: null path");
            return;
        }
        File f = new File(path);
        if (!f.exists()) {
            Logger.write("run refused: file not found: " + path);
            return;
        }
        Logger.startNewLog(f.getName().replace(".json", ""));
        Scenario sc;
        try {
            sc = ScenarioParser.parseFile(f);
        } catch (Throwable t) {
            Logger.write("parse error: " + t);
            return;
        }
        Logger.write("loaded scenario '" + sc.name + "' from " + path);
        runner = new ScenarioRunner(this, sc);
        runner.start();
    }

    public synchronized void runScenario(Scenario sc) {
        if (runner != null && runner.isRunning()) {
            Logger.write("run refused: already running");
            return;
        }
        if (sc == null) {
            Logger.write("run refused: null scenario");
            return;
        }
        Logger.startNewLog(sc.name == null ? "exec" : sc.name);
        Logger.write("loaded scenario '" + sc.name + "' from API exec");
        runner = new ScenarioRunner(this, sc);
        runner.start();
    }

    public boolean isRunnerRunning() {
        return runner != null && runner.isRunning();
    }

    public ScenarioRunner currentRunner() {
        return runner;
    }

    public String currentTargetPackage() {
        return runner == null ? null : runner.targetPackage();
    }

    public synchronized void stopScenario(String reason) {
        if (runner == null) return;
        Logger.write("STOP (" + reason + ")");
        runner.requestStop();
    }

    public void onRunnerFinished(boolean success) {
        Logger.write("runner finished, success=" + success);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (runner != null) runner.requestStop();
        if (apiServer != null) { apiServer.shutdown(); apiServer = null; }
        try { if (receiver != null) unregisterReceiver(receiver); } catch (Throwable ignored) {}
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (runner != null) runner.requestStop();
        if (apiServer != null) { apiServer.shutdown(); apiServer = null; }
        try { if (receiver != null) unregisterReceiver(receiver); } catch (Throwable ignored) {}
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
