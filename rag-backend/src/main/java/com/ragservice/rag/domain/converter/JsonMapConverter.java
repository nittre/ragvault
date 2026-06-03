package com.ragservice.rag.domain.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Collections;
import java.util.Map;

/**
 * Map<String, Object> ↔ PostgreSQL JSONB (TEXT) 변환기.
 *
 * 기존 프로젝트의 @JdbcTypeCode(SqlTypes.JSON) + String 패턴 대신
 * Map 타입이 필요한 엔티티에서 사용. (hypersistence-utils 미사용)
 *
 * 사용:
 *   @Convert(converter = JsonMapConverter.class)
 *   @Column(columnDefinition = "jsonb")
 *   private Map<String, Object> params;
 */
@Converter
public class JsonMapConverter implements AttributeConverter<Map<String, Object>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> TYPE_REF =
            new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(Map<String, Object> attribute) {
        if (attribute == null) {
            return "{}";
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSONB 직렬화 실패: " + e.getMessage(), e);
        }
    }

    @Override
    public Map<String, Object> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return MAPPER.readValue(dbData, TYPE_REF);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSONB 역직렬화 실패: " + e.getMessage(), e);
        }
    }
}
