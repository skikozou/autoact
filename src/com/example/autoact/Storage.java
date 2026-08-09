package com.example.autoact;

import android.os.Environment;

import java.io.File;

public class Storage {

    public static final String BASE_DIR_NAME = "autoact";

    public static File baseDir() {
        File dl = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        File base = new File(dl, BASE_DIR_NAME);
        if (!base.exists()) base.mkdirs();
        return base;
    }

    public static File scenariosDir() {
        File d = new File(baseDir(), "scenarios");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File dumpsDir() {
        File d = new File(baseDir(), "dumps");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File logsDir() {
        File d = new File(baseDir(), "logs");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File screenshotsDir() {
        File d = new File(baseDir(), "screenshots");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static boolean hasAllFilesAccess() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return true;
    }
}
