package com.nexgenai.repository;

import com.nexgenai.model.Application;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ApplicationRepository extends JpaRepository<Application, String> {

    List<Application> findByJobId(String jobId);

    List<Application> findByCandidateId(String candidateId);

    Optional<Application> findByCandidateIdAndJobId(String candidateId, String jobId);

    boolean existsByCandidateIdAndJobId(String candidateId, String jobId);

    long countByJobId(String jobId);
}