package com.nexgenai.repository;

import com.nexgenai.model.Job;
import com.nexgenai.model.enums.JobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface JobRepository extends JpaRepository<Job, String> {
    
    List<Job> findByStatus(JobStatus status);
    
    List<Job> findByDepartment(String department);
    
    @Query("SELECT j FROM Job j WHERE j.title LIKE %:keyword% OR j.description LIKE %:keyword%")
    List<Job> searchByKeyword(@Param("keyword") String keyword);
    
    @Query("SELECT j FROM Job j WHERE j.status = :status ORDER BY j.createdAt DESC")
    List<Job> findLatestJobsByStatus(@Param("status") JobStatus status);
    
    @Query("SELECT COUNT(j) FROM Job j WHERE j.status = :status")
    long countByStatus(@Param("status") JobStatus status);
    
    
    

    // Trouver les jobs par statut et les trier par date de création (du plus récent au plus ancien)
    List<Job> findByStatusOrderByCreatedAtDesc(JobStatus status);
    
    // Recherche par mot-clé dans le titre ou la description
    @Query("SELECT j FROM Job j WHERE j.status = 'ACTIVE' AND (j.title LIKE %:keyword% OR j.description LIKE %:keyword%)")
    List<Job> searchActiveJobsByKeyword(@Param("keyword") String keyword);
    

    // Trouver les jobs récents (30 derniers jours)
    @Query("SELECT j FROM Job j WHERE j.createdAt >= CURRENT_DATE - 30 ORDER BY j.createdAt DESC")
    List<Job> findRecentJobs();
    
    // Trouver les jobs avec le plus de candidatures
   // @Query("SELECT j, COUNT(a) as applicationCount FROM Job j LEFT JOIN Application a ON j.id = a.job.id GROUP BY j ORDER BY applicationCount DESC")
   // List<Object[]> findMostAppliedJobs();
    
    // Vérifier si un job existe par son ID
    boolean existsById(String id);
    
    
    
 // JobRepository.java
    @Query("SELECT j FROM Job j " +
           "LEFT JOIN FETCH j.technicalSkills " +
           "LEFT JOIN FETCH j.prerequisites " +
           "WHERE j.id = :id")
    Optional<Job> findByIdWithDetails(@Param("id") String id);
    
    
 // JobRepository.java

 // Fetch avec technicalSkills seulement
 @Query("SELECT j FROM Job j LEFT JOIN FETCH j.technicalSkills WHERE j.id = :id")
 Optional<Job> findByIdWithSkills(@Param("id") String id);

 // Fetch avec prerequisites seulement  
 @Query("SELECT j FROM Job j LEFT JOIN FETCH j.prerequisites WHERE j.id = :id")
 Optional<Job> findByIdWithPrerequisites(@Param("id") String id);
}