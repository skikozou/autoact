package com.example.autoact;

import android.accessibilityservice.GestureDescription;
import android.graphics.Path;

public class GestureBuilder {

    public static GestureDescription tap(int x, int y, long durMs) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.StrokeDescription s =
                new GestureDescription.StrokeDescription(p, 0, Math.max(60L, durMs));
        return new GestureDescription.Builder().addStroke(s).build();
    }

    public static GestureDescription swipe(int x1, int y1, int x2, int y2, long durMs) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        GestureDescription.StrokeDescription s =
                new GestureDescription.StrokeDescription(p, 0, Math.max(60L, durMs));
        return new GestureDescription.Builder().addStroke(s).build();
    }

    public static GestureDescription curveSwipe(int x1, int y1,
                                                int cx, int cy,
                                                int x2, int y2,
                                                long durMs) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.quadTo(cx, cy, x2, y2);
        GestureDescription.StrokeDescription s =
                new GestureDescription.StrokeDescription(p, 0, Math.max(60L, durMs));
        return new GestureDescription.Builder().addStroke(s).build();
    }

    // Drag = press-and-hold then drag then release, single continuous finger.
    // Returns 3 strokes chained by continueStroke().
    public static GestureDescription drag(int x1, int y1, int x2, int y2,
                                          long holdMs, long durMs) {
        long hold = Math.max(100L, holdMs);
        long drag = Math.max(120L, durMs);

        Path pHold = new Path();
        pHold.moveTo(x1, y1);
        GestureDescription.StrokeDescription hold1 =
                new GestureDescription.StrokeDescription(pHold, 0, hold, true);

        Path pDrag = new Path();
        pDrag.moveTo(x1, y1);
        pDrag.lineTo(x2, y2);
        GestureDescription.StrokeDescription drag1 =
                hold1.continueStroke(pDrag, 0, drag, true);

        Path pRelease = new Path();
        pRelease.moveTo(x2, y2);
        GestureDescription.StrokeDescription release =
                drag1.continueStroke(pRelease, 0, 60L, false);

        return new GestureDescription.Builder()
                .addStroke(hold1)
                .addStroke(drag1)
                .addStroke(release)
                .build();
    }

    // Pinch: two fingers along horizontal axis around (cx,cy).
    // startSpan > endSpan → pinch-in; startSpan < endSpan → spread.
    public static GestureDescription pinch(int cx, int cy,
                                           int startSpan, int endSpan,
                                           long durMs) {
        long dur = Math.max(80L, durMs);
        int hs = Math.max(1, startSpan / 2);
        int he = Math.max(1, endSpan / 2);

        Path leftPath = new Path();
        leftPath.moveTo(cx - hs, cy);
        leftPath.lineTo(cx - he, cy);
        GestureDescription.StrokeDescription left =
                new GestureDescription.StrokeDescription(leftPath, 0, dur);

        Path rightPath = new Path();
        rightPath.moveTo(cx + hs, cy);
        rightPath.lineTo(cx + he, cy);
        GestureDescription.StrokeDescription right =
                new GestureDescription.StrokeDescription(rightPath, 0, dur);

        return new GestureDescription.Builder()
                .addStroke(left)
                .addStroke(right)
                .build();
    }

    // Parallel two-finger swipe (multiSwipe): both fingers move from (x1..x2, y1..y2)
    // with a lateral offset `offset` between the two fingers (perpendicular to motion).
    public static GestureDescription multiSwipe(int x1, int y1,
                                                int x2, int y2,
                                                int offset,
                                                long durMs) {
        long dur = Math.max(80L, durMs);
        int off = Math.max(20, offset);

        // Perpendicular unit direction (for a horizontal-ish motion, offset in y)
        int dx = x2 - x1;
        int dy = y2 - y1;
        double len = Math.hypot(dx, dy);
        double nx = len == 0 ? 0 : -dy / len;
        double ny = len == 0 ? 1 :  dx / len;
        int ox = (int) Math.round(nx * off * 0.5);
        int oy = (int) Math.round(ny * off * 0.5);

        Path a = new Path();
        a.moveTo(x1 + ox, y1 + oy);
        a.lineTo(x2 + ox, y2 + oy);

        Path b = new Path();
        b.moveTo(x1 - ox, y1 - oy);
        b.lineTo(x2 - ox, y2 - oy);

        return new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(a, 0, dur))
                .addStroke(new GestureDescription.StrokeDescription(b, 0, dur))
                .build();
    }
}
