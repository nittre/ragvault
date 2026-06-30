package com.ragservice.rag.controller;

import com.ragvault.core.service.SchemaInspectorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;


import java.util.List;

/**
 * Admin UI용 MySQL 스키마 탐색 API.
 *
 * RAG·SQL 테이블 등록 폼에서 드롭다운·체크박스 UI를 구성하기 위해
 * 고객사 MySQL의 전체 BASE TABLE 목록과 컬럼 정보를 반환한다.
 *
 * 접근 권한: api:admin scope (SecurityConfig).
 */
@RestController
@RequestMapping("/api/v1/admin/schema")
@RequiredArgsConstructor
public class AdminSchemaController {

    private final SchemaInspectorService schemaInspector;

    /**
     * 고객사 MySQL 전체 테이블 목록 + 컬럼 정보 반환.
     *
     * 응답 예시:
     * [
     *   {
     *     "tableName": "learners",
     *     "tableComment": "수강생",
     *     "columns": [
     *       { "name": "id", "dataType": "int", "nullable": false, "comment": "", "primaryKey": true },
     *       { "name": "name", "dataType": "varchar", "nullable": false, "comment": "이름", "primaryKey": false }
     *     ]
     *   }
     * ]
     */
    @GetMapping("/tables")
    public List<SchemaInspectorService.TableInfo> getTables(
            @RequestParam Integer datasourceId) {
        return schemaInspector.getAllTablesWithSchema(datasourceId);
    }
}
