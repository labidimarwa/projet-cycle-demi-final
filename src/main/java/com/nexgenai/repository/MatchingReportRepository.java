package com.nexgenai.repository;

import com.nexgenai.model.MatchingReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface MatchingReportRepository extends JpaRepository<MatchingReport, String> {

    /** Dernier rapport pour un candidat sur un poste donné. */
    Optional<MatchingReport> findByJobIdAndCandidateId(String jobId, String candidateId);

    /** Vérification cache : même CV (même hash) → pas de recalcul. */
    Optional<MatchingReport> findByJobIdAndCandidateIdAndCvHash(
            String jobId, String candidateId, String cvHash);

    /** Tous les rapports d'un poste (vue RH : liste des candidats avec scores). */
    List<MatchingReport> findByJobIdOrderByScoreGlobalDesc(String jobId);

    /** Tous les rapports d'un candidat. */
    List<MatchingReport> findByCandidateId(String candidateId);
}
