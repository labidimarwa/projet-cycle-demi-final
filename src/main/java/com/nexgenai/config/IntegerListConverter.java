// src/main/java/com/nexgenai/config/IntegerListConverter.java
package com.nexgenai.config;

import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.persistence.Converter;
import java.util.List;

@Converter
public class IntegerListConverter extends JsonListConverter<Integer> {
    @Override
    protected TypeReference<List<Integer>> typeRef() {
        return new TypeReference<>() {};
    }
}