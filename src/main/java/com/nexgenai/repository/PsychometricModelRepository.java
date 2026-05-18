package com.nexgenai.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.nexgenai.model.PsychometricModel;

public interface PsychometricModelRepository extends JpaRepository<PsychometricModel, String> {
    List<PsychometricModel> findAllByOrderByBuiltInDescNameAsc();
    boolean existsByName(String name);
}
