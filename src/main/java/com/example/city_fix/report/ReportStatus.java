package com.example.city_fix.report;

public enum ReportStatus {
    NEW("New"),
    IN_PROGRESS("In progress"),
    RESOLVED("Resolved"),
    REJECTED("Rejected");

    private final String label;

    ReportStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
