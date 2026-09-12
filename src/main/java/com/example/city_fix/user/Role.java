package com.example.city_fix.user;

public enum Role {
    RESIDENT("Resident"),
    STAFF("Office staff"),
    ADMIN("Admin");

    private final String label;

    Role(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
