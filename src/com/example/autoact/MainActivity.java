package com.example.autoact;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

public class MainActivity extends Activity implements View.OnClickListener {

    private static final int REQ_POST_NOTIF = 10;
    private static final int MAX_TAIL_LINES = 300;
    private static final long DUMP_DELAY_MS = 2000L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView status;
    private TextView storageStatus;
    private TextView logView;
    private ScrollView scroll;
    private ListView list;
    private ArrayAdapter<String> adapter;
    private List<File> scenarioFiles;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.main);

        status = (TextView) findViewById(R.id.status);
        storageStatus = (TextView) findViewById(R.id.storage_status);
        logView = (TextView) findViewById(R.id.log_view);
        scroll = (ScrollView) findViewById(R.id.scroll);
        list = (ListView) findViewById(R.id.list_scenarios);

        ((Button) findViewById(R.id.btn_a11y)).setOnClickListener(this);
        ((Button) findViewById(R.id.btn_storage)).setOnClickListener(this);
        ((Button) findViewById(R.id.btn_refresh)).setOnClickListener(this);
        ((Button) findViewById(R.id.btn_dump)).setOnClickListener(this);
        ((Button) findViewById(R.id.btn_run)).setOnClickListener(this);
        ((Button) findViewById(R.id.btn_stop)).setOnClickListener(this);

        scenarioFiles = new ArrayList<File>();
        adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_single_choice,
                new ArrayList<String>());
        list.setAdapter(adapter);

        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQ_POST_NOTIF);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.btn_a11y) {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } else if (id == R.id.btn_storage) {
            requestAllFilesAccess();
        } else if (id == R.id.btn_refresh) {
            refresh();
        } else if (id == R.id.btn_dump) {
            scheduleDump();
        } else if (id == R.id.btn_run) {
            runSelected();
        } else if (id == R.id.btn_stop) {
            stopRun();
        }
    }

    private void scheduleDump() {
        if (!Storage.hasAllFilesAccess()) {
            Toast.makeText(this, "全ファイル権限が必要", Toast.LENGTH_SHORT).show();
            return;
        }
        if (AutomationService.getInstance() == null) {
            Toast.makeText(this, "AccessibilityService が未接続", Toast.LENGTH_LONG).show();
            return;
        }
        if (Logger.currentFile() == null) Logger.startNewLog("dump");
        Toast.makeText(this,
                "2秒後にダンプ。対象アプリを前面に。",
                Toast.LENGTH_SHORT).show();
        handler.postDelayed(new DumpRunnable("manual"), DUMP_DELAY_MS);
    }

    private void runSelected() {
        if (!Storage.hasAllFilesAccess()) {
            Toast.makeText(this, "全ファイル権限が必要", Toast.LENGTH_SHORT).show();
            return;
        }
        if (AutomationService.getInstance() == null) {
            Toast.makeText(this, "AccessibilityService が未接続", Toast.LENGTH_LONG).show();
            return;
        }
        int pos = list.getCheckedItemPosition();
        if (pos < 0 || pos >= scenarioFiles.size()) {
            Toast.makeText(this, "シナリオを選択", Toast.LENGTH_SHORT).show();
            return;
        }
        File f = scenarioFiles.get(pos);
        Intent i = new Intent(AutomationService.ACTION_RUN_SCENARIO);
        i.setPackage(getPackageName());
        i.putExtra(AutomationService.EXTRA_SCENARIO_PATH, f.getAbsolutePath());
        sendBroadcast(i);
        Toast.makeText(this, "RUN: " + f.getName(), Toast.LENGTH_SHORT).show();
        handler.postDelayed(new RefreshRunnable(this), 1200L);
    }

    private void stopRun() {
        Intent i = new Intent(AutomationService.ACTION_STOP_SCENARIO);
        i.setPackage(getPackageName());
        sendBroadcast(i);
        Toast.makeText(this, "STOP requested", Toast.LENGTH_SHORT).show();
        handler.postDelayed(new RefreshRunnable(this), 400L);
    }

    void publicRefresh() {
        refresh();
    }

    private void requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < 30) {
            Toast.makeText(this, "not needed on this SDK", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    private void refresh() {
        refreshStatus();
        refreshScenarioList();
        refreshLog();
    }

    private void refreshStatus() {
        boolean a11y = isAccessibilityEnabled();
        status.setText("A11y: " + (a11y ? "ENABLED" : "DISABLED"));
        boolean sto = Storage.hasAllFilesAccess();
        String base = "-";
        try { base = Storage.baseDir().getAbsolutePath(); } catch (Throwable ignored) {}
        storageStatus.setText("Storage: " + (sto ? "OK" : "NG") + "  " + base);
    }

    private boolean isAccessibilityEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(flat)) return false;
        String target = getPackageName() + "/" + AutomationService.class.getName();
        for (String s : flat.split(":")) {
            if (target.equalsIgnoreCase(s)) return true;
        }
        return false;
    }

    private void refreshScenarioList() {
        scenarioFiles.clear();
        adapter.clear();
        if (!Storage.hasAllFilesAccess()) {
            adapter.add("(need all-files-access)");
            adapter.notifyDataSetChanged();
            return;
        }
        File dir = Storage.scenariosDir();
        File[] arr = dir.listFiles();
        if (arr == null || arr.length == 0) {
            adapter.add("(no scenarios in " + dir.getAbsolutePath() + ")");
            adapter.notifyDataSetChanged();
            return;
        }
        List<File> files = new ArrayList<File>(Arrays.asList(arr));
        FileNameComparator cmp = new FileNameComparator();
        java.util.Collections.sort(files, cmp);
        for (File f : files) {
            if (f.isFile() && f.getName().toLowerCase().endsWith(".json")) {
                scenarioFiles.add(f);
                adapter.add(f.getName());
            }
        }
        if (scenarioFiles.isEmpty()) {
            adapter.add("(no *.json in " + dir.getAbsolutePath() + ")");
        }
        adapter.notifyDataSetChanged();
    }

    private void refreshLog() {
        File f = Logger.currentFile();
        if (f == null || !f.exists()) {
            logView.setText("(no log yet)");
            return;
        }
        Deque<String> tail = new ArrayDeque<String>(MAX_TAIL_LINES);
        FileInputStream in = null;
        BufferedReader r = null;
        try {
            in = new FileInputStream(f);
            r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                if (tail.size() >= MAX_TAIL_LINES) tail.removeFirst();
                tail.addLast(line);
            }
        } catch (IOException e) {
            logView.setText("read error: " + e);
            return;
        } finally {
            try { if (r != null) r.close(); } catch (IOException ignored) {}
            try { if (in != null) in.close(); } catch (IOException ignored) {}
        }
        StringBuilder sb = new StringBuilder();
        Iterator<String> it = tail.iterator();
        while (it.hasNext()) sb.append(it.next()).append('\n');
        logView.setText(sb.toString());
        scroll.post(new Scroller(scroll));
    }
}
