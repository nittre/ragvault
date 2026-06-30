package com.ragservice.rag.controller;

import com.ragvault.core.domain.MaskingRule;
import com.ragvault.core.repository.MaskingRuleRepository;
import com.ragvault.core.security.PiiMasker;
import com.ragvault.core.service.SchemaInspectorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * 데이터소스별 PII 마스킹 규칙 관리 + 스키마 기반 PII 자동 탐색 API.
 * /api/v1/admin/datasources/{dsId}/masking-rules
 *
 * - GET /        → 전역 규칙 + 해당 DS 규칙 목록
 * - POST /       → DS 스코프 규칙 생성
 * - DELETE /{id} → 삭제
 * - POST /bulk   → 일괄 생성
 * - GET /suggest → 스키마 컬럼 분석 → PII 규칙 제안
 */
@RestController
@RequestMapping("/api/v1/admin/datasources/{dsId}/masking-rules")
@RequiredArgsConstructor
public class AdminDsMaskingRuleController {

    private final MaskingRuleRepository repository;
    private final PiiMasker piiMasker;
    private final SchemaInspectorService schemaInspector;

    // ── PII 컬럼명 패턴 정의 ──────────────────────────────────────────────────

    private record PiiPattern(
            List<String> keywords,
            String ruleName,
            String regexPattern,
            String replacement,
            String level
    ) {
        boolean matchesColumn(String columnName) {
            String lower = columnName.toLowerCase();
            return keywords.stream().anyMatch(lower::contains);
        }
    }

    private static final List<PiiPattern> PII_PATTERNS = List.of(
            new PiiPattern(
                    List.of("email", "e_mail", "mail_addr"),
                    "이메일 마스킹",
                    "[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}",
                    "[이메일]", "standard"),
            new PiiPattern(
                    List.of("phone", "phone_num", "mobile", "handphone", "cell_phone", "tel", "telephone"),
                    "전화번호 마스킹",
                    "01[016789]-?\\d{3,4}-?\\d{4}",
                    "[전화번호]", "standard"),
            new PiiPattern(
                    List.of("jumin", "rrn", "resident_num", "social_security", "ssn"),
                    "주민등록번호 마스킹",
                    "\\d{6}-[1-4]\\d{6}",
                    "[주민번호]", "standard"),
            new PiiPattern(
                    List.of("card_num", "card_no", "credit_card", "card_number"),
                    "카드번호 마스킹",
                    "\\d{4}[\\s\\-]?\\d{4}[\\s\\-]?\\d{4}[\\s\\-]?\\d{4}",
                    "[카드번호]", "standard"),
            new PiiPattern(
                    List.of("account", "bank_account", "acct_num", "account_no"),
                    "계좌번호 마스킹",
                    "\\d{3,6}-?\\d{2,6}-?\\d{4,8}",
                    "[계좌번호]", "aggressive"),
            new PiiPattern(
                    List.of("passport", "passport_no", "passport_num"),
                    "여권번호 마스킹",
                    "[A-Z]{1,2}\\d{6,9}",
                    "[여권번호]", "aggressive"),
            new PiiPattern(
                    List.of("ip_addr", "ip_address", "client_ip", "remote_ip"),
                    "IP 주소 마스킹",
                    "\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}",
                    "[IP]", "standard"),
            new PiiPattern(
                    List.of("emp_no", "emp_id", "employee_no", "employee_id", "staff_id"),
                    "사번 마스킹",
                    "EMP\\d{4,6}",
                    "[사번]", "standard"),
            new PiiPattern(
                    List.of("biz_no", "business_no", "company_no", "corp_no", "brn"),
                    "사업자번호 마스킹",
                    "\\d{3}-\\d{2}-\\d{5}",
                    "[사업자번호]", "aggressive")
    );

    // ── API ──────────────────────────────────────────────────────────────────

    /** 전역 규칙 + 해당 DS 스코프 규칙 합산 반환 */
    @GetMapping
    public List<MaskingRule> list(@PathVariable Integer dsId) {
        List<MaskingRule> result = new ArrayList<>();
        result.addAll(repository.findByDatasourceIdIsNullOrderBySortOrderAsc());
        result.addAll(repository.findByDatasourceIdOrderBySortOrderAsc(dsId));
        result.sort(Comparator.comparingInt(MaskingRule::getSortOrder));
        return result;
    }

    @PostMapping
    public MaskingRule create(@PathVariable Integer dsId, @RequestBody MaskingRule rule) {
        validate(rule);
        rule.setId(null);
        rule.setDatasourceId(dsId);
        rule.setCreatedAt(LocalDateTime.now());
        rule.setUpdatedAt(LocalDateTime.now());
        MaskingRule saved = repository.save(rule);
        piiMasker.evict();
        return saved;
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Integer dsId, @PathVariable Long id) {
        repository.findById(id).ifPresent(rule -> {
            if (rule.getDatasourceId() != null && !dsId.equals(rule.getDatasourceId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "다른 데이터소스의 규칙입니다");
            }
            repository.delete(rule);
            piiMasker.evict();
        });
        return ResponseEntity.noContent().build();
    }

    /** 일괄 생성 (PII 탐색 결과 선택 후 등록) */
    @PostMapping("/bulk")
    public ResponseEntity<List<MaskingRule>> bulkCreate(
            @PathVariable Integer dsId,
            @RequestBody List<MaskingRule> rules) {
        List<MaskingRule> created = new ArrayList<>();
        for (MaskingRule rule : rules) {
            validate(rule);
            rule.setId(null);
            rule.setDatasourceId(dsId);
            rule.setCreatedAt(LocalDateTime.now());
            rule.setUpdatedAt(LocalDateTime.now());
            try {
                created.add(repository.save(rule));
            } catch (Exception e) {
                // 이름 중복 등 → skip
            }
        }
        if (!created.isEmpty()) piiMasker.evict();
        return ResponseEntity.ok(created);
    }

    /**
     * 스키마 컬럼명 분석 → PII 마스킹 규칙 제안.
     * 이미 존재하는 규칙(전역 or DS 스코프)은 제외.
     */
    @GetMapping("/suggest")
    public List<SuggestedRule> suggest(@PathVariable Integer dsId) {
        List<SchemaInspectorService.TableInfo> tables = schemaInspector.getAllTablesWithSchema(dsId);

        // 이미 존재하는 규칙명 수집 (전역 + 해당 DS)
        Set<String> existingNames = new HashSet<>();
        repository.findByDatasourceIdIsNullOrderBySortOrderAsc()
                .forEach(r -> existingNames.add(r.getName()));
        repository.findByDatasourceIdOrderBySortOrderAsc(dsId)
                .forEach(r -> existingNames.add(r.getName()));

        // 컬럼명 → PII 패턴 매핑 (ruleName 기준으로 중복 제거)
        Map<String, SuggestedRule.Builder> builderMap = new LinkedHashMap<>();

        for (SchemaInspectorService.TableInfo table : tables) {
            for (SchemaInspectorService.ColumnDetail col : table.columns()) {
                for (PiiPattern pii : PII_PATTERNS) {
                    if (!existingNames.contains(pii.ruleName()) && pii.matchesColumn(col.name())) {
                        builderMap
                                .computeIfAbsent(pii.ruleName(), k -> new SuggestedRule.Builder(pii))
                                .addColumn(table.tableName() + "." + col.name());
                    }
                }
            }
        }

        return builderMap.values().stream()
                .map(SuggestedRule.Builder::build)
                .collect(Collectors.toList());
    }

    // ── inner types ──────────────────────────────────────────────────────────

    public record SuggestedRule(
            String name,
            String pattern,
            String replacement,
            String level,
            List<String> detectedInColumns
    ) {
        static class Builder {
            private final PiiPattern pii;
            private final List<String> columns = new ArrayList<>();

            Builder(PiiPattern pii) { this.pii = pii; }

            Builder addColumn(String col) { columns.add(col); return this; }

            SuggestedRule build() {
                return new SuggestedRule(pii.ruleName(), pii.regexPattern(),
                        pii.replacement(), pii.level(), List.copyOf(columns));
            }
        }
    }

    // ── validation ───────────────────────────────────────────────────────────

    private void validate(MaskingRule rule) {
        if (rule.getName() == null || rule.getName().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "규칙 이름은 필수입니다");
        if (rule.getPattern() == null || rule.getPattern().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "정규식 패턴은 필수입니다");
        if (rule.getReplacement() == null || rule.getReplacement().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "치환 토큰은 필수입니다");
        String level = rule.getLevel();
        if (level == null || !(level.equals("standard") || level.equals("aggressive")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "level 은 standard 또는 aggressive 여야 합니다");
        try {
            Pattern.compile(rule.getPattern());
        } catch (PatternSyntaxException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "잘못된 정규식입니다: " + e.getDescription());
        }
    }
}
