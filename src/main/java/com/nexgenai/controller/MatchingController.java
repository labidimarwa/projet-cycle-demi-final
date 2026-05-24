package com.nexgenai.controller;

import com.nexgenai.dto.matching.MatchingReportDTO;
import com.nexgenai.service.CvMatchingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * Endpoints de matching CV ↔ Offre — réservés aux RH.
 *
 * POST /hr/jobs/{jobId}/candidates/{candidateId}/match
 *   → Lance le matching (upload CV optionnel, sinon utilise le CV stocké)
 *   → Retourne le rapport complet
 *
 * GET  /hr/jobs/{jobId}/candidates/{candidateId}/match
 *   → Récupère le dernier rapport en BDD
 *
 * GET  /hr/jobs/{jobId}/match/all
 *   → Liste tous les candidats scorés pour un poste (tri par score desc)
 */
@RestController
@RequestMapping("/hr/jobs")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "http://localhost:4200")
public class MatchingController {

    private final CvMatchingService matchingService;

    // ─── Lancer le matching ───────────────────────────────────────────────────

    @PostMapping(
        value    = "/{jobId}/candidates/{candidateId}/match",
        consumes = {MediaType.MULTIPART_FORM_DATA_VALUE, MediaType.APPLICATION_JSON_VALUE,
                    MediaType.APPLICATION_OCTET_STREAM_VALUE, "*/*"}
    )
    public ResponseEntity<?> lancerMatching(
            @PathVariable String jobId,
            @PathVariable String candidateId,
            @RequestParam(value = "cv", required = false) MultipartFile cv) {

        try {
            log.info("🎯 Matching lancé — job={} candidat={}", jobId, candidateId);

            byte[] cvBytes   = null;
            String cvFilename = null;

            // CV fourni par le RH dans la requête multipart
            if (cv != null && !cv.isEmpty()) {
                cvBytes   = cv.getBytes();
                cvFilename = cv.getOriginalFilename();
            }
            // Sinon, CvMatchingService lit le CV stocké en BDD/disque

            MatchingReportDTO rapport = matchingService.lancerMatching(
                jobId, candidateId, cvBytes, cvFilename
            );
            return ResponseEntity.ok(rapport);

        } catch (RuntimeException e) {
            log.error("❌ Matching échoué : {}", e.getMessage());
            // Contrainte 4 : retourner une erreur métier claire, jamais NPE
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("❌ Erreur inattendue matching : {}", e.getMessage());
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Erreur interne : " + e.getMessage()));
        }
    }

    // ─── Récupérer le rapport existant ───────────────────────────────────────

    @GetMapping("/{jobId}/candidates/{candidateId}/match")
    public ResponseEntity<?> getReport(
            @PathVariable String jobId,
            @PathVariable String candidateId) {

        return matchingService.getReport(jobId, candidateId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    // ─── Liste de tous les candidats scorés pour un poste ────────────────────

    @GetMapping("/{jobId}/match/all")
    public ResponseEntity<List<MatchingReportDTO>> getAllReports(
            @PathVariable String jobId) {
        return ResponseEntity.ok(matchingService.getReportsByJob(jobId));
    }
}
