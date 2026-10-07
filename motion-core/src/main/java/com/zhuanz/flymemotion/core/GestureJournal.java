package com.zhuanz.flymemotion.core;

import java.util.ArrayDeque;

/** Bounded metadata only. Retains previous gestures so a later successful swipe cannot hide a failure. */
public final class GestureJournal {
    private final ArrayDeque<String> records = new ArrayDeque<>();
    private int size;
    public void add(long gesture, String event) {
        String record = "g" + gesture + ":" + event.replace('\n', ' ').replace('\r', ' ');
        if (record.length() > 384) record = record.substring(0, 384);
        records.addLast(record); size += record.length() + 1;
        while (records.size() > 48 || size > 8192) size -= records.removeFirst().length() + 1;
    }
    public String snapshot() { return String.join(";", records); }
}
