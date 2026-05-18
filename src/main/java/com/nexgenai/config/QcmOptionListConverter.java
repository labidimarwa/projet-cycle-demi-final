// src/main/java/com/nexgenai/config/QcmOptionListConverter.java
package com.nexgenai.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.nexgenai.model.Question.QcmOption;
import jakarta.persistence.Converter;
import java.util.List;

@Converter
public class QcmOptionListConverter extends JsonListConverter<QcmOption> {
    @Override
    protected TypeReference<List<QcmOption>> typeRef() {
        return new TypeReference<>() {};
    }
}