// src/main/java/com/nexgenai/config/TestCaseListConverter.java
package com.nexgenai.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.nexgenai.model.Question.TestCase;
import jakarta.persistence.Converter;
import java.util.List;

@Converter
public class TestCaseListConverter extends JsonListConverter<TestCase> {
    @Override
    protected TypeReference<List<TestCase>> typeRef() {
        return new TypeReference<>() {};
    }
}