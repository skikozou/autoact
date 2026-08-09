package com.example.autoact;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

// Receives PackageInstaller status callbacks. On STATUS_PENDING_USER_ACTION,
// launches the system confirmation UI (which a11y can then auto-tap).
public class InstallStatusReceiver extends BroadcastReceiver {

    private static volatile String lastStatus = "none";
    private static volatile int    lastCode   = -1;
    private static volatile String lastPkg    = null;
    private static volatile String lastTag    = null;

    public static String  lastStatus()  { return lastStatus; }
    public static int     lastCode()    { return lastCode; }
    public static String  lastPackage() { return lastPkg; }
    public static String  lastTag()     { return lastTag; }

    @Override
    public void onReceive(Context ctx, Intent i) {
        if (i == null) return;
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
        String pkg = i.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);
        String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        String tag = i.getStringExtra(Installer.EXTRA_TAG);
        lastCode = status;
        lastPkg  = pkg;
        lastTag  = tag;

        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                lastStatus = "pending_user_action";
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { ctx.startActivity(confirm); }
                    catch (Throwable t) { Log.e(AutomationService.TAG, "confirm launch: " + t); }
                }
                Logger.write("install[" + tag + "] pending user action pkg=" + pkg);
                return;
            case PackageInstaller.STATUS_SUCCESS:
                lastStatus = "success";
                Logger.write("install[" + tag + "] SUCCESS pkg=" + pkg);
                return;
            default:
                lastStatus = "failure(" + status + ")";
                Logger.write("install[" + tag + "] status=" + status
                        + " pkg=" + pkg + " msg=" + msg);
        }
    }
}
