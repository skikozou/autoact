package com.example.autoact;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.List;

public class UiDumper {

    // Returns the file path written, or null on failure.
    public static File dumpActive(AutomationService svc, String tag) {
        if (svc == null) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("# AutoAct UI dump @ ").append(Logger.fileStamp())
          .append(tag == null ? "" : (" tag=" + tag)).append('\n');

        try {
            List<AccessibilityWindowInfo> windows = svc.getWindows();
            if (windows == null || windows.isEmpty()) {
                sb.append("(no windows visible)\n");
            } else {
                for (AccessibilityWindowInfo w : windows) {
                    if (w == null) continue;
                    sb.append(String.format(
                            "== window id=%d type=%s active=%s focus=%s title=%s ==%n",
                            w.getId(), winTypeName(w.getType()),
                            String.valueOf(w.isActive()),
                            String.valueOf(w.isFocused()),
                            String.valueOf(w.getTitle())));
                    AccessibilityNodeInfo root = w.getRoot();
                    if (root == null) {
                        sb.append("  (root=null)\n");
                    } else {
                        dumpNode(root, 1, sb);
                        try { root.recycle(); } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable t) {
            sb.append("!! getWindows failed: ").append(t).append('\n');
            AccessibilityNodeInfo root = svc.getRootInActiveWindow();
            if (root == null) {
                sb.append("(rootInActiveWindow=null)\n");
            } else {
                sb.append("== active window (fallback) ==\n");
                dumpNode(root, 1, sb);
                try { root.recycle(); } catch (Throwable ignored) {}
            }
        }

        String safeTag = tag == null ? "" : "_" + tag.replaceAll("[^A-Za-z0-9_.-]", "_");
        File out = new File(Storage.dumpsDir(),
                "dump_" + Logger.fileStamp() + safeTag + ".txt");
        FileOutputStream fos = null;
        OutputStreamWriter w = null;
        try {
            fos = new FileOutputStream(out, false);
            w = new OutputStreamWriter(fos, "UTF-8");
            w.write(sb.toString());
            w.flush();
        } catch (IOException e) {
            Logger.write("dump write failed: " + e);
            return null;
        } finally {
            try { if (w != null) w.close(); } catch (IOException ignored) {}
            try { if (fos != null) fos.close(); } catch (IOException ignored) {}
        }
        Logger.write("dump written: " + out.getAbsolutePath());
        return out;
    }

    private static void dumpNode(AccessibilityNodeInfo n, int depth, StringBuilder sb) {
        if (n == null) return;
        pad(sb, depth);
        String cls = String.valueOf(n.getClassName());
        String vid = String.valueOf(n.getViewIdResourceName());
        String txt = strOrDash(n.getText());
        String desc = strOrDash(n.getContentDescription());
        Rect b = new Rect();
        try { n.getBoundsInScreen(b); } catch (Throwable ignored) {}
        sb.append('[').append(cls).append(']')
          .append(" id=").append(vid)
          .append(" text=\"").append(escape(txt)).append('"')
          .append(" desc=\"").append(escape(desc)).append('"')
          .append(" bounds=[").append(b.left).append(',').append(b.top)
          .append(',').append(b.right).append(',').append(b.bottom).append(']')
          .append(" C=").append(n.isClickable() ? 1 : 0)
          .append(" L=").append(n.isLongClickable() ? 1 : 0)
          .append(" S=").append(n.isScrollable() ? 1 : 0)
          .append(" E=").append(n.isEnabled() ? 1 : 0)
          .append(" F=").append(n.isFocused() ? 1 : 0)
          .append('\n');

        int nc = n.getChildCount();
        for (int i = 0; i < nc; i++) {
            AccessibilityNodeInfo c = null;
            try { c = n.getChild(i); } catch (Throwable ignored) {}
            if (c == null) continue;
            dumpNode(c, depth + 1, sb);
            try { c.recycle(); } catch (Throwable ignored) {}
        }
    }

    private static void pad(StringBuilder sb, int d) {
        for (int i = 0; i < d; i++) sb.append("  ");
    }

    private static String strOrDash(CharSequence cs) {
        if (cs == null) return "";
        return cs.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }

    private static String winTypeName(int t) {
        if (t == AccessibilityWindowInfo.TYPE_APPLICATION) return "APP";
        if (t == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return "IME";
        if (t == AccessibilityWindowInfo.TYPE_SYSTEM) return "SYS";
        if (t == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return "A11Y_OVL";
        if (t == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER) return "SPLIT";
        return "T(" + t + ")";
    }
}
