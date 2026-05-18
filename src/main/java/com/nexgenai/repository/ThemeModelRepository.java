package com.nexgenai.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.nexgenai.model.ThemeModel;

public interface ThemeModelRepository extends JpaRepository<ThemeModel, String> {
    List<ThemeModel> findByThemeIdOrderByOrderIndex(String themeId);
    boolean existsByThemeIdAndModelId(String themeId, String modelId);
}
