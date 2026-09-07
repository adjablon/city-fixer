package com.example.city_fix.report;

public enum Category {
    POTHOLE("Pothole"),
    STREETLIGHT("Streetlight"),
    GRAFFITI("Graffiti"),
    TRASH("Trash"),
    SIGN("Sign"),
    OTHER("Other");

    private final String label;

    Category(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
