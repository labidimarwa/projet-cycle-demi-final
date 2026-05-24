package com.nexgenai.dto.matching;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Résultat du matching pour un prérequis du poste
 * (Degree, Experience, Language, Certification, Skill).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrerequisiteMatchResult {

    /** Type de prérequis : Degree | Experience | Language | Certification | Skill. */
    private String type;

    /** Valeur attendue définie par le RH (ex : "Master", "3 ans", "Anglais"). */
    private String requis;

    /** Valeur détectée dans le CV du candidat. */
    private String detecte;

    /** true si le RH a marqué ce prérequis comme obligatoire. */
    private boolean obligatoire;

    /** Score de satisfaction du prérequis (0.0 – 1.0). */
    private double scoreMatch;

    /** true si scoreMatch ≥ 0.40 (seuil de satisfaction). */
    private boolean satisfait;
}
