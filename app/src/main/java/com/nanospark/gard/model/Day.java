package com.nanospark.gard.model;

import java.util.Calendar;

/**
 * Created by Leandro on 31/10/2015.
 */
public enum Day {

    SUNDAY, MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY;

    public static Day fromCalendar(int day) {
        return values()[day - 1];
    }

    @Override
    public String toString() {
        return name().substring(0, 1) + name().substring(1, 4).toLowerCase();
    }

    public static Day today() {
        return fromCalendar(Calendar.getInstance().get(Calendar.DAY_OF_WEEK));
    }

    public String abbr() {
        switch (this) {
            case SUNDAY:
            case SATURDAY:
            case TUESDAY:
            case THURSDAY:
                return name().substring(0, 1) + name().substring(1, 2).toLowerCase();
            default:
                return name().substring(0, 1);
        }
    }

    public String abbr(int length) {
        String abbr = abbr();
        if (length > abbr.length()) {
            abbr = abbr + name().substring(abbr.length(), name().length() < length ? name().length() : length);
        }
        return abbr;
    }
}
