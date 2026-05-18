// Nouveau fichier : src/main/java/com/nexgenai/config/JsonListConverter.java
package com.nexgenai.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import java.util.Collections;
import java.util.List;

public abstract class JsonListConverter<T> implements AttributeConverter<List<T>, String> {

    private static final ObjectMapper mapper = new ObjectMapper();

    protected abstract TypeReference<List<T>> typeRef();

    @Override
    public String convertToDatabaseColumn(List<T> attribute) {
        if (attribute == null || attribute.isEmpty()) return "[]";
        try { return mapper.writeValueAsString(attribute); }
        catch (Exception e) { return "[]"; }
    }

    @Override
    public List<T> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return Collections.emptyList();
        try { return mapper.readValue(dbData, typeRef()); }
        catch (Exception e) { return Collections.emptyList(); }
    }
}