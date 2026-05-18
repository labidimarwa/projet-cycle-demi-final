package com.nexgenai.model.enums;

public enum ExperienceLevel {
    JUNIOR("Junior (1-2 years)"),
    MID_LEVEL("Mid-Level (3-5 years)"),
    SENIOR("Senior (5-8 years)"),
    LEAD("Lead (8+ years)");

    private String value;
    ExperienceLevel(String value) { this.value = value; }
    public String getValue() { return value; }
}