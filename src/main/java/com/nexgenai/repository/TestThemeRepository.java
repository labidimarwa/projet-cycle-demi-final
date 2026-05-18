package com.nexgenai.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.nexgenai.model.TestTheme;

public interface TestThemeRepository extends JpaRepository<TestTheme, String> {
    List<TestTheme> findByAssessmentIdOrderByOrderIndex(String assessmentId);
}
