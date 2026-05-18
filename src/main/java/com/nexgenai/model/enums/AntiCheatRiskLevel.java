package com.nexgenai.model.enums;

import lombok.Getter;

@Getter
public enum AntiCheatRiskLevel {
    LOW("Faible"),
    MEDIUM("Moyen"),
    HIGH("Élevé");

    private final String label;

    AntiCheatRiskLevel(String label) {
        this.label = label;
    }
}