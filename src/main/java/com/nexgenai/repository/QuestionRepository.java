package com.nexgenai.repository;

import com.nexgenai.model.Question;
import com.nexgenai.model.Question.QuestionKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for the unified {@link Question} entity. Merges the queries
 * previously split between {@code TestQuestionRepository} (psychometric)
 * and {@code SimpleQuestionRepository} (technical QCM + problem solving).
 */
@Repository
public interface QuestionRepository extends JpaRepository<Question, String> {

    // ── By theme-model (RH psychometric questions) ───────────────────────────

    List<Question> findByThemeModelIdOrderByOrderIndex(String themeModelId);

    int countByThemeModelId(String themeModelId);

    // ── By theme (TECHNICAL questions) ───────────────────────────────────────

    List<Question> findByThemeIdOrderByOrderIndex(String themeId);

    int countByThemeId(String themeId);

    @Modifying
    @Query("DELETE FROM Question q WHERE q.theme.id = :themeId")
    void deleteByThemeId(@Param("themeId") String themeId);

    // ── By assessment (direct attachment) ────────────────────────────────────

    List<Question> findByAssessmentIdOrderByOrderIndex(String assessmentId);

    List<Question> findByAssessmentIdAndKind(String assessmentId, QuestionKind kind);

    int countByAssessmentId(String assessmentId);

    // ── Misc ─────────────────────────────────────────────────────────────────

    List<Question> findByIdIn(List<String> ids);
}
