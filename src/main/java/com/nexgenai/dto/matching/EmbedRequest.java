package com.nexgenai.dto.matching;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * Corps de la requête vers POST /embed du microservice Python.
 * Contient les noms des skills du poste à encoder.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class EmbedRequest {
    /** Noms des compétences requises par le poste (définis par le RH). */
    private List<String> textes;
}
