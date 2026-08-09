package com.example.autoact;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;

// Thin wrapper around PackageInstaller.Session for both install and uninstall.
// Status arrives asynchronously via InstallStatusReceiver. commit() returns
// the sessionId; the API caller can query status via /status endpoint later
// (last install status is kept on AutomationService).
public class Installer {

    public static final String ACTION_STATUS = "com.example.autoact.INSTALL_STATUS";
    public static final String EXTRA_TAG     = "install_tag";

    public static int install(Context ctx, File apk, String tag) throws Exception {
        if (apk == null || !apk.exists())
            throw new IllegalArgumentException("apk not found: " + apk);
        long size = apk.length();
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if (Build.VERSION.SDK_INT >= 31) {
            try { params.setRequireUserAction(
                    PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED); }
            catch (Throwable ignored) {}
        }
        int sessionId = pi.createSession(params);
        PackageInstaller.Session session = null;
        try {
            session = pi.openSession(sessionId);
            OutputStream out = session.openWrite("base.apk", 0, size);
            FileInputStream in = new FileInputStream(apk);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            } finally {
                try { in.close(); } catch (Throwable ignored) {}
                try { out.close(); } catch (Throwable ignored) {}
            }
            session.commit(makeSender(ctx, sessionId, tag).getIntentSender());
        } finally {
            if (session != null) try { session.close(); } catch (Throwable ignored) {}
        }
        return sessionId;
    }

    public static void uninstall(Context ctx, String pkg, String tag) {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        int sessionId = pkg.hashCode();
        pi.uninstall(pkg, makeSender(ctx, sessionId, tag).getIntentSender());
    }

    private static PendingIntent makeSender(Context ctx, int sessionId, String tag) {
        Intent i = new Intent(ACTION_STATUS)
                .setPackage(ctx.getPackageName())
                .putExtra(EXTRA_TAG, tag == null ? "" : tag);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        return PendingIntent.getBroadcast(ctx, sessionId, i, flags);
    }
}
