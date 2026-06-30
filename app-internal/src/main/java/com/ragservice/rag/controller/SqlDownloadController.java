package com.ragservice.rag.controller;

import com.ragservice.rag.service.CsvExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * SQL 조회 결과 CSV 다운로드 엔드포인트.
 *
 * GET /v1/sql/download/{token}
 * - JWT 인증 필수 (SecurityConfig .anyRequest().authenticated() 적용)
 * - 토큰 유효 시: text/csv 파일 반환 (UTF-8 BOM 포함, Excel 한글 호환)
 * - 토큰 만료/없음: 404
 */
@RestController
@RequestMapping("/v1/sql")
@RequiredArgsConstructor
public class SqlDownloadController {

    private final CsvExportService csvExportService;

    @GetMapping("/download/{token}")
    public ResponseEntity<?> download(@PathVariable String token) {
        return csvExportService.retrieve(token)
                .map(csv -> {
                    // UTF-8 BOM: Excel이 한글을 올바르게 인식
                    byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
                    byte[] body = csv.getBytes(StandardCharsets.UTF_8);
                    byte[] payload = new byte[bom.length + body.length];
                    System.arraycopy(bom, 0, payload, 0, bom.length);
                    System.arraycopy(body, 0, payload, bom.length, body.length);

                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
                    headers.set(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"query_result_" + token + ".csv\"");
                    return ResponseEntity.ok()
                            .headers(headers)
                            .body(payload);
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("error", "다운로드 링크가 만료되었거나 존재하지 않습니다.").toString().getBytes(StandardCharsets.UTF_8)));
    }
}
