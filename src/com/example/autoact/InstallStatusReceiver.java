package com.example.autoact;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

// Receives PackageInstaller status callbacks. On STATUS_PENDING_USER_ACTION,
// launches the system confirmation UI (which a11y can then auto-tap).
public class InstallStatusReceiver extends BroadcastReceiver {

    // Immutable snapshot swapped atomically per event, so readers never see
    // a half-updated state across the 4 fields.
    public static final class Snapshot {
        public final String status;
        public final int    code;
        public final String pkg;
        public final String tag;
        Snapshot(String status, int code, String pkg, String tag) {
            this.status = status;
            this.code   = code;
            this.pkg    = pkg;
            this.tag    = tag;
        }
    }

    private static final Snapshot EMPTY = new Snapshot("none", -1, null, null);
    private static volatile Snapshot current = EMPTY;

    public static Snapshot current()   { return current; }
    public static String  lastStatus() { return current.status; }
    public static int     lastCode()   { return current.code; }
    public static String  lastPackage(){ return current.pkg; }
    public static String  lastTag()    { return current.tag; }

    @Override
    public void onReceive(Context ctx, Intent i) {
        if (i == null) return;
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
        String pkg = i.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);
        String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        String tag = i.getStringExtra(Installer.EXTRA_TAG);

        String statusName;
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                statusName = "pending_user_action";
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { ctx.startActivity(confirm); }
                    catch (Throwable t) { Log.e(AutomationService.TAG, "confirm launch: " + t); }
                }
                Logger.write("install[" + tag + "] pending user action pkg=" + pkg);
                break;
            case PackageInstaller.STATUS_SUCCESS:
                statusName = "success";
                Logger.write("install[" + tag + "] SUCCESS pkg=" + pkg);
                break;
            default:
                statusName = "failure(" + status + ")";
                Logger.write("install[" + tag + "] status=" + status
                        + " pkg=" + pkg + " msg=" + msg);
        }
        // Single atomic publish — all 4 fields become visible together.
        current = new Snapshot(statusName, status, pkg, tag);
    }
}
