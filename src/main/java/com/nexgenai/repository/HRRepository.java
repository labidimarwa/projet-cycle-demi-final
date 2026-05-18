package com.nexgenai.repository;

import com.nexgenai.model.HR;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface HRRepository extends JpaRepository<HR, String> {
    
    Optional<HR> findByEmail(String email);
    
    @Query("SELECT h FROM HR h WHERE h.department = :department")
    List<HR> findByDepartment(@Param("department") String department);
    
 
}