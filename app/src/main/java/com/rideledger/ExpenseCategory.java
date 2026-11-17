package com.rideledger;

import java.util.Arrays;
import java.util.List;

/** The optional category at the start of an ordinary expense note. */
enum ExpenseCategory {
    TRANSPORT("交通"),
    LODGING("住宿"),
    MEALS("餐食"),
    OTHER("未分类");

    static final List<ExpenseCategory> SHORTCUTS = Arrays.asList(TRANSPORT, LODGING, MEALS);
    final String label;
    ExpenseCategory(String label) {
        this.label = label;
    }

    private String tag() { return "【" + label + "】"; }

    String applyTo(String note) {
        return tag() + " " + note.trim();
    }

    static String details(String note) {
        String text = note.trim();
        ExpenseCategory category = explicit(text);
        return category == OTHER ? text : text.substring(category.tag().length()).trim();
    }

    static ExpenseCategory explicit(String note) {
        String text = note.trim();
        for (ExpenseCategory category : SHORTCUTS) if (text.startsWith(category.tag())) return category;
        return OTHER;
    }

    static String visibleNote(String note) {
        ExpenseCategory category = explicit(note);
        if (category == OTHER) return note;
        String detail = details(note);
        return detail.isEmpty() ? category.label : category.label + " · " + detail;
    }

    static ExpenseCategory fromNote(String note) {
        return explicit(note);
    }
}
