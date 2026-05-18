package com.nexgenai.repository;

import com.nexgenai.model.Candidate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CandidateRepository extends JpaRepository<Candidate, String> {
    
    Optional<Candidate> findByEmail(String email);
    
    @Query("SELECT c FROM Candidate c WHERE c.yearsOfExperience >= :minExperience")
    List<Candidate> findByMinimumExperience(@Param("minExperience") Integer minExperience);

    @Query("SELECT c FROM Candidate c WHERE c.currentPosition LIKE %:position%")
    List<Candidate> findByCurrentPositionContaining(@Param("position") String position);
    
    @Query("SELECT c FROM Candidate c WHERE c.educationLevel = :educationLevel")
    List<Candidate> findByEducationLevel(@Param("educationLevel") String educationLevel);

}