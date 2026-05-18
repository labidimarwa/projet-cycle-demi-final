package com.nexgenai.model.enums;

public enum ContractType {
    FULL_TIME("Full-time"),
    PART_TIME("Part-time"),
    CONTRACT("Contract"),
    FREELANCE("Freelance");

    private String value;
    ContractType(String value) { this.value = value; }
    public String getValue() { return value; }
}