package com.nexgenai.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.nexgenai.model.QuestionOption;

public interface QuestionOptionRepository extends JpaRepository<QuestionOption, String> {
    List<QuestionOption> findByQuestionIdOrderByOrderIndex(String questionId);
    void deleteByQuestionId(String questionId);
}