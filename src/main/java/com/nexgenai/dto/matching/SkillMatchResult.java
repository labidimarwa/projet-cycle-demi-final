package com.nexgenai.dto.matching;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Résultat du matching pour un skill technique requis par le poste.
 * Le poids vient TOUJOURS du job créé par le RH (jamais codé en dur).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillMatchResult {

    /** Nom du skill requis par le poste. */
    private String nom;

    /** Poids défini par le RH lors de la création du poste (0–100). */
    private int poids;

    /** true si le RH a marqué ce skill comme indispensable. */
    private boolean obligatoire;

    /**
     * Meilleure similarité cosinus trouvée entre l'embedding du skill requis
     * et les embeddings des compétences du CV (calculés par Python).
     */
    private double similarite;

    /** MATCHED (≥0.75) | PARTIAL (≥0.50) | MISSING (<0.50). */
    private String statut;

    /** Compétence du CV la plus proche sémantiquement. */
    private String competenceTrouvee;
}
