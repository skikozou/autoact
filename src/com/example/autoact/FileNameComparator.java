package com.example.autoact;

import java.io.File;
import java.util.Comparator;

// Raw type to dodge d8 3.3 generic-interface NPE (see STATE.md).
@SuppressWarnings({"rawtypes", "unchecked"})
public class FileNameComparator implements Comparator {
    @Override
    public int compare(Object a, Object b) {
        File fa = (File) a;
        File fb = (File) b;
        return fa.getName().compareToIgnoreCase(fb.getName());
    }
}
