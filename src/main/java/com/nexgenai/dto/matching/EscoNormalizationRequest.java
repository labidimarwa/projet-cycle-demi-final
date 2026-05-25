package com.nexgenai.dto.matching;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Corps de la requête POST /normalize envoyée au microservice Python.
 * Contient la liste brute des noms de compétences à normaliser contre ESCO v1.2.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class EscoNormalizationRequest {
    private List<String> skills;
}
