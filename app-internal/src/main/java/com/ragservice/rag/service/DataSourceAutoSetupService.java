package com.ragservice.rag.service;

import com.ragvault.core.service.SchemaInspectorService;
import com.ragvault.core.service.DataSourceConfigService;
import com.ragvault.core.service.RagTableConfigService;
import com.ragvault.core.service.SensitivityAnalysisService;
import com.ragvault.core.service.RagColumnSuggestionService;
import com.ragvault.core.service.SchemaDescriptionService;
import com.ragvault.core.service.SqlGeneratorService;
import com.ragvault.core.service.DataSourceRouterService;
import com.ragvault.core.service.RoutingEmbeddingService;
import com.ragvault.core.service.QueryIntent;



import com.ragvault.core.domain.MaskingRule;
import com.ragvault.core.domain.RagTableConfig;
import com.ragvault.core.domain.SqlTableConfig;
import com.ragvault.core.repository.MaskingRuleRepository;
import com.ragvault.core.repository.SqlTableConfigRepository;
import com.ragvault.core.security.PiiMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 데이터소스 신규 등록 시 SQL 테이블·RAG 테이블·PII 마스킹을 자동 초기 세팅.
 *
 * 흐름:
 * 1. 스키마 전체 조회
 * 2. SQL 테이블 전체 등록 → 백그라운드 LLM 민감도 분류
 * 3. RAG 테이블 전체 등록 → 백그라운드 LLM 컬럼·청킹 추천
 * 4. 컬럼명 기반 PII 마스킹 규칙 자동 등록
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataSourceAutoSetupService {

    private final SchemaInspectorService schemaInspector;
    private final SqlTableConfigRepository sqlTableConfigRepository;
    private final RagTableConfigService ragTableConfigService;
    private final MaskingRuleRepository maskingRuleRepository;
    private final SensitivityAnalysisService sensitivityAnalysisService;
    private final RagColumnSuggestionService ragColumnSuggestionService;
    private final SchemaDescriptionService schemaDescriptionService;
    private final PiiMasker piiMasker;

    private static final Set<String> TITLE_KEYWORDS =
            Set.of("title", "name", "subject", "label", "heading", "caption", "summary");
    private static final Set<String> CONTENT_TYPES =
            Set.of("varchar", "text", "mediumtext", "longtext", "char", "tinytext");

    private record PiiPattern(List<String> keywords, String ruleName, String regexPattern,
                               String replacement, String level) {
        boolean matchesColumn(String col) {
            String lower = col.toLowerCase();
            return keywords.stream().anyMatch(lower::contains);
        }
    }

    private static final List<PiiPattern> PII_PATTERNS = List.of(
            new PiiPattern(List.of("email", "e_mail", "mail_addr"),
                    "이메일 마스킹",
                    "[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}",
                    "[이메일]", "standard"),
            new PiiPattern(List.of("phone", "phone_num", "mobile", "handphone", "cell_phone", "tel", "telephone"),
                    "전화번호 마스킹",
                    "01[016789]-?\\d{3,4}-?\\d{4}",
                    "[전화번호]", "standard"),
            new PiiPattern(List.of("jumin", "rrn", "resident_num", "social_security", "ssn"),
                    "주민등록번호 마스킹",
                    "\\d{6}-[1-4]\\d{6}",
                    "[주민번호]", "standard"),
            new PiiPattern(List.of("card_num", "card_no", "credit_card", "card_number"),
                    "카드번호 마스킹",
                    "\\d{4}[\\s\\-]?\\d{4}[\\s\\-]?\\d{4}[\\s\\-]?\\d{4}",
                    "[카드번호]", "standard"),
            new PiiPattern(List.of("account", "bank_account", "acct_num", "account_no"),
                    "계좌번호 마스킹",
                    "\\d{3,6}-?\\d{2,6}-?\\d{4,8}",
                    "[계좌번호]", "aggressive"),
            new PiiPattern(List.of("passport", "passport_no", "passport_num"),
                    "여권번호 마스킹",
                    "[A-Z]{1,2}\\d{6,9}",
                    "[여권번호]", "aggressive"),
            new PiiPattern(List.of("ip_addr", "ip_address", "client_ip", "remote_ip"),
                    "IP 주소 마스킹",
                    "\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}",
                    "[IP]", "standard"),
            new PiiPattern(List.of("emp_no", "emp_id", "employee_no", "employee_id", "staff_id"),
                    "사번 마스킹",
                    "EMP\\d{4,6}",
                    "[사번]", "standard"),
            new PiiPattern(List.of("biz_no", "business_no", "company_no", "corp_no", "brn"),
                    "사업자번호 마스킹",
                    "\\d{3}-\\d{2}-\\d{5}",
                    "[사업자번호]", "aggressive")
    );

    @Async
    public void setupAsync(Integer dsId, boolean autoDescribe) {
        log.info("Auto setup start: dsId={}, autoDescribe={}", dsId, autoDescribe);
        try {
            List<SchemaInspectorService.TableInfo> tables = schemaInspector.getAllTablesWithSchema(dsId);
            if (tables.isEmpty()) {
                log.info("Auto setup: no tables found, dsId={}", dsId);
                return;
            }

            List<String> tableNames = tables.stream()
                    .map(SchemaInspectorService.TableInfo::tableName)
                    .toList();
            Map<String, SchemaInspectorService.TableInfo> schemaMap = tables.stream()
                    .collect(Collectors.toMap(SchemaInspectorService.TableInfo::tableName, t -> t));

            // 1. SQL 테이블 등록
            List<String> sqlImported = registerSqlTables(dsId, tableNames);
            if (!sqlImported.isEmpty()) {
                schemaInspector.evictSchemaCache(dsId);
                sensitivityAnalysisService.analyzeAndUpdateAsync(dsId, sqlImported, tables);
            }

            // 2. RAG 테이블 등록
            List<String> ragImported = registerRagTables(dsId, tableNames, schemaMap);
            if (!ragImported.isEmpty()) {
                ragColumnSuggestionService.suggestAndUpdateAsync(dsId, ragImported, tables);
            }

            // 3. PII 마스킹 규칙 등록
            int piiCount = registerPiiRules(dsId, tables);

            // 4. 테이블·컬럼 자연어 설명 자동 생성 (옵션 — COMMENT 우선, 없으면 LLM)
            if (autoDescribe) {
                schemaDescriptionService.generateAndStoreAsync(dsId, tables);
            }

            log.info("Auto setup done: dsId={}, sql={}, rag={}, pii={}, autoDescribe={}",
                    dsId, sqlImported.size(), ragImported.size(), piiCount, autoDescribe);

        } catch (Exception e) {
            log.error("Auto setup failed: dsId={}, error={}", dsId, e.getMessage(), e);
        }
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private List<String> registerSqlTables(Integer dsId, List<String> tableNames) {
        List<String> imported = new ArrayList<>();
        for (String tableName : tableNames) {
            try {
                if (sqlTableConfigRepository.findBySourceTableAndDatasourceId(tableName, dsId).isPresent()) {
                    continue;
                }
                SqlTableConfig config = new SqlTableConfig();
                config.setSourceTable(tableName);
                config.setDisplayName(tableName);
                config.setDatasourceId(dsId);
                config.setDataSensitivity("internal");
                config.setAllowedGroups(new String[]{"all"});
                config.setActive(true);
                config.setLlmStatus("pending");
                config.setCreatedAt(LocalDateTime.now());
                config.setUpdatedAt(LocalDateTime.now());
                sqlTableConfigRepository.save(config);
                imported.add(tableName);
            } catch (Exception e) {
                log.warn("Auto setup SQL skip: table={}, dsId={}, reason={}", tableName, dsId, e.getMessage());
            }
        }
        return imported;
    }

    private List<String> registerRagTables(Integer dsId, List<String> tableNames,
                                            Map<String, SchemaInspectorService.TableInfo> schemaMap) {
        List<String> imported = new ArrayList<>();
        for (String tableName : tableNames) {
            try {
                RagTableConfig config = new RagTableConfig();
                config.setSourceTable(tableName);
                config.setSourceType("mysql");
                config.setDatasourceId(dsId);
                config.setDataSensitivity("internal");
                config.setChunkingStrategy("recursive");
                config.setChunkSize(500);
                config.setChunkOverlap(50);
                config.setLlmStatus("pending");
                autoDetectColumns(config, schemaMap.get(tableName));
                ragTableConfigService.register(config);
                imported.add(tableName);
            } catch (Exception e) {
                log.warn("Auto setup RAG skip: table={}, dsId={}, reason={}", tableName, dsId, e.getMessage());
            }
        }
        return imported;
    }

    private void autoDetectColumns(RagTableConfig config, SchemaInspectorService.TableInfo info) {
        if (info == null) {
            config.setPkColumn("id");
            config.setContentColumnsJson("");
            config.setMetadataColumnsJson("");
            return;
        }
        List<SchemaInspectorService.ColumnDetail> cols = info.columns();

        String pk = cols.stream()
                .filter(SchemaInspectorService.ColumnDetail::primaryKey)
                .map(SchemaInspectorService.ColumnDetail::name)
                .findFirst().orElse("id");
        config.setPkColumn(pk);

        config.setTitleColumn(cols.stream()
                .filter(c -> !c.name().equals(pk))
                .filter(c -> TITLE_KEYWORDS.stream().anyMatch(kw -> c.name().toLowerCase().contains(kw)))
                .map(SchemaInspectorService.ColumnDetail::name)
                .findFirst().orElse(null));

        List<String> contentCols = cols.stream()
                .filter(c -> !c.name().equals(pk))
                .filter(c -> CONTENT_TYPES.contains(c.dataType().toLowerCase()))
                .map(SchemaInspectorService.ColumnDetail::name)
                .collect(Collectors.toList());
        config.setContentColumnsJson(String.join(",", contentCols));

        Set<String> contentSet = new HashSet<>(contentCols);
        List<String> metaCols = cols.stream()
                .filter(c -> !c.name().equals(pk) && !contentSet.contains(c.name()))
                .map(SchemaInspectorService.ColumnDetail::name)
                .collect(Collectors.toList());
        config.setMetadataColumnsJson(String.join(",", metaCols));
    }

    private int registerPiiRules(Integer dsId, List<SchemaInspectorService.TableInfo> tables) {
        Set<String> existing = new HashSet<>();
        maskingRuleRepository.findByDatasourceIdIsNullOrderBySortOrderAsc()
                .forEach(r -> existing.add(r.getName()));
        maskingRuleRepository.findByDatasourceIdOrderBySortOrderAsc(dsId)
                .forEach(r -> existing.add(r.getName()));

        // 컬럼명 스캔 → 매칭되는 PII 패턴 수집 (중복 제거, 순서 유지)
        Map<String, PiiPattern> toCreate = new LinkedHashMap<>();
        for (SchemaInspectorService.TableInfo table : tables) {
            for (SchemaInspectorService.ColumnDetail col : table.columns()) {
                for (PiiPattern pii : PII_PATTERNS) {
                    if (!existing.contains(pii.ruleName()) && pii.matchesColumn(col.name())) {
                        toCreate.putIfAbsent(pii.ruleName(), pii);
                    }
                }
            }
        }

        int count = 0;
        for (PiiPattern pii : toCreate.values()) {
            try {
                MaskingRule rule = new MaskingRule();
                rule.setName(pii.ruleName());
                rule.setPattern(pii.regexPattern());
                rule.setReplacement(pii.replacement());
                rule.setLevel(pii.level());
                rule.setDatasourceId(dsId);
                rule.setEnabled(true);
                rule.setSortOrder(100 + count);
                rule.setCreatedAt(LocalDateTime.now());
                rule.setUpdatedAt(LocalDateTime.now());
                maskingRuleRepository.save(rule);
                count++;
            } catch (Exception e) {
                log.warn("Auto setup PII skip: rule={}, dsId={}, reason={}", pii.ruleName(), dsId, e.getMessage());
            }
        }
        if (count > 0) piiMasker.evict();
        return count;
    }
}
