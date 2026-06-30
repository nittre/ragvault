package com.ragservice.rag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL 조회 결과를 CSV로 직렬화하여 Redis에 단기 저장한다.
 *
 * 키 형식: sql_csv:{16자 UUID hex}
 * TTL: 30분 (ADR-0010 패턴과 동일)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CsvExportService {

    private final RedisTemplate<String, String> redisTemplate;

    private static final Duration TTL = Duration.ofMinutes(30);
    private static final String KEY_PREFIX = "sql_csv:";

    /**
     * 결과를 CSV로 직렬화 후 Redis에 저장하고 토큰 반환.
     *
     * @return 16자 hex 토큰 (다운로드 URL에 사용)
     */
    public String store(List<Map<String, Object>> rows) {
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        try {
            String csv = toCsv(rows);
            redisTemplate.opsForValue().set(KEY_PREFIX + token, csv, TTL);
        } catch (Exception e) {
            log.error("CsvExportService store failed for token={}", token, e);
        }
        return token;
    }

    /**
     * 토큰으로 CSV 문자열 조회. 만료되었거나 없으면 empty.
     */
    public Optional<String> retrieve(String token) {
        try {
            String val = redisTemplate.opsForValue().get(KEY_PREFIX + token);
            return Optional.ofNullable(val);
        } catch (Exception e) {
            log.error("CsvExportService retrieve failed for token={}", token, e);
            return Optional.empty();
        }
    }

    private String toCsv(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();

        // 헤더
        List<String> headers = List.copyOf(rows.get(0).keySet());
        sb.append(String.join(",", headers.stream().map(this::escape).toList())).append("\n");

        // 데이터 행
        for (Map<String, Object> row : rows) {
            sb.append(
                headers.stream()
                    .map(h -> escape(String.valueOf(row.getOrDefault(h, ""))))
                    .reduce((a, b) -> a + "," + b)
                    .orElse("")
            ).append("\n");
        }
        return sb.toString();
    }

    private String escape(String value) {
        if (value == null) return "\"\"";
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
