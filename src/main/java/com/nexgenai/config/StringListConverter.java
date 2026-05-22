package com.nexgenai.config;

import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.persistence.Converter;
import java.util.List;

@Converter
public class StringListConverter extends JsonListConverter<String> {
    @Override
    protected TypeReference<List<String>> typeRef() {
        return new TypeReference<>() {};
    }
}
