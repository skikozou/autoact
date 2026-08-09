package com.example.autoact;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class Logger {

    public static final String TAG = "AutoAct";

    private static final SimpleDateFormat TS =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static final SimpleDateFormat FILE_TS =
            new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);

    private static File currentFile;

    public static synchronized File startNewLog(String tag) {
        File dir = Storage.logsDir();
        String stamp = FILE_TS.format(new Date());
        String safeTag = tag == null ? "run" : tag.replaceAll("[^A-Za-z0-9_.-]", "_");
        currentFile = new File(dir, safeTag + "_" + stamp + ".log");
        write("---- log started: " + currentFile.getAbsolutePath() + " ----");
        return currentFile;
    }

    public static synchronized File currentFile() {
        return currentFile;
    }

    public static synchronized void write(String line) {
        String out = TS.format(new Date()) + " " + line + "\n";
        Log.d(TAG, line);
        if (currentFile == null) return;
        FileOutputStream fos = null;
        OutputStreamWriter w = null;
        try {
            fos = new FileOutputStream(currentFile, true);
            w = new OutputStreamWriter(fos, "UTF-8");
            w.write(out);
            w.flush();
        } catch (IOException e) {
            Log.w(TAG, "log write failed", e);
        } finally {
            try { if (w != null) w.close(); } catch (IOException ignored) {}
            try { if (fos != null) fos.close(); } catch (IOException ignored) {}
        }
    }

    public static String fileStamp() {
        return FILE_TS.format(new Date());
    }
}
