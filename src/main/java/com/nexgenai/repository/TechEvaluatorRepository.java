package com.nexgenai.repository;

import com.nexgenai.model.TechEvaluator;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TechEvaluatorRepository extends JpaRepository<TechEvaluator, String> {

    Optional<TechEvaluator> findByEmail(String email);

    /**
     * Find evaluators whose specialization matches the given department name.
     * Case-insensitive LIKE match so "engineering" matches "ENGINEERING".
     */
    @Query("SELECT t FROM TechEvaluator t WHERE UPPER(t.specialization) = UPPER(:specialization)")
    List<TechEvaluator> findBySpecialization(@Param("specialization") String specialization);

    /**
     * Broader search: evaluators whose specialization CONTAINS the keyword.
     * Useful when department names differ from specialization values.
     */
    @Query("SELECT t FROM TechEvaluator t WHERE UPPER(t.specialization) LIKE UPPER(CONCAT('%', :keyword, '%'))")
    List<TechEvaluator> findBySpecializationContaining(@Param("keyword") String keyword);

    @Query("SELECT t FROM TechEvaluator t WHERE t.expertiseLevel = :level")
    List<TechEvaluator> findByExpertiseLevel(@Param("level") String level);

 

    @Query("SELECT t FROM TechEvaluator t WHERE t.evaluationsToday < t.maxEvaluationsPerDay")
    List<TechEvaluator> findAvailableEvaluators();
}