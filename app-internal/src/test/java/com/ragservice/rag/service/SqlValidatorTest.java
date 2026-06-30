package com.ragservice.rag.service;
import com.ragvault.core.service.*;

import com.ragvault.core.domain.SqlTableConfig;
import com.ragvault.core.repository.SqlTableConfigRepository;
import com.ragvault.core.service.SchemaInspectorService.ColumnInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * SqlValidator 단위 테스트.
 *
 * ADR-0007 Layer 1 검증:
 * - SELECT * 거부
 * - excluded_columns 거부
 * - 화이트리스트 외 테이블 거부
 * - 유효한 SELECT 허용
 * - DML/DDL 거부
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SqlValidatorTest {

    @Mock
    SqlTableConfigRepository repository;

    @InjectMocks
    SqlValidator validator;

    @BeforeEach
    void setUp() {
        SqlTableConfig config = new SqlTableConfig();
        config.setSourceTable("orders");
        config.setExcludedColumns(new String[]{"phone", "ssn"});
        config.setAllowedColumns(null); // null = 전체 허용
        when(repository.findByIsActiveTrue()).thenReturn(List.of(config));
    }

    @Test
    void selectStar_denied() {
        var result = validator.validate("SELECT * FROM orders");
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("SELECT *");
    }

    @Test
    void excludedColumn_phone_denied() {
        var result = validator.validate("SELECT phone, name FROM orders");
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("phone");
    }

    @Test
    void excludedColumn_ssn_denied() {
        var result = validator.validate("SELECT id, ssn FROM orders");
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("ssn");
    }

    @Test
    void unknownTable_denied() {
        var result = validator.validate("SELECT id FROM secret_table");
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("secret_table");
    }

    @Test
    void validSelect_allowed() {
        var result = validator.validate(
                "SELECT id, amount, created_at FROM orders " +
                "WHERE created_at > '2026-01-01' LIMIT 100");
        assertThat(result.allowed()).isTrue();
    }

    @Test
    void dropTable_denied() {
        var result = validator.validate("DROP TABLE orders");
        assertThat(result.allowed()).isFalse();
    }

    @Test
    void deleteStatement_denied() {
        var result = validator.validate("DELETE FROM orders WHERE id = 1");
        assertThat(result.allowed()).isFalse();
    }

    @Test
    void blankSql_denied() {
        var result = validator.validate("   ");
        assertThat(result.allowed()).isFalse();
    }

    @Test
    void nullSql_denied() {
        var result = validator.validate(null);
        assertThat(result.allowed()).isFalse();
    }

    @Test
    void invalidSqlSyntax_denied() {
        var result = validator.validate("SELECT FROM WHERE");
        assertThat(result.allowed()).isFalse();
    }

    // ── P4: 한글 식별자 환각 탐지 ─────────────────────────────────────────────

    @Test
    void koreanAlias_allowed() {
        // AS <alias>는 결과 표시용이라 환각 위험 없음 — SqlValidator 가 의도적으로 허용
        var result = validator.validate("SELECT SUM(amount) AS 총매출 FROM orders WHERE id > 0");
        assertThat(result.allowed()).isTrue();
    }

    @Test
    void koreanColumnName_denied() {
        var result = validator.validate("SELECT 이름, amount FROM orders");
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("한글");
    }

    @Test
    void koreanStringLiteralInWhere_allowed() {
        // WHERE 절의 문자열 리터럴('완료')은 식별자가 아니므로 허용
        var result = validator.validate(
                "SELECT id, amount FROM orders WHERE status = '완료' LIMIT 10");
        assertThat(result.allowed()).isTrue();
    }

    // ── P2: 컬럼 존재성 검증 (스키마 오버로드) ────────────────────────────────

    @Nested
    class ColumnExistenceTest {

        private Map<String, List<ColumnInfo>> schema;

        @BeforeEach
        void setUpSchema() {
            schema = Map.of("orders", List.of(
                    new ColumnInfo("id", "int", false, ""),
                    new ColumnInfo("amount", "decimal", true, "거래 금액"),
                    new ColumnInfo("status", "varchar", false, ""),
                    new ColumnInfo("created_at", "timestamp", false, "")
            ));
        }

        @Test
        void knownColumns_allowed() {
            var result = validator.validate(
                    "SELECT id, amount FROM orders WHERE status = 'completed' LIMIT 10",
                    schema);
            assertThat(result.allowed()).isTrue();
        }

        @Test
        void hallucinated_column_denied() {
            var result = validator.validate(
                    "SELECT id, nonexistent_col FROM orders LIMIT 10", schema);
            assertThat(result.allowed()).isFalse();
            assertThat(result.reason()).contains("nonexistent_col");
        }

        @Test
        void hallucinatedColumn_withTypo_suggestsSimilar() {
            // 'amountt' → 'amount' (편집거리 1)
            var result = validator.validate(
                    "SELECT id, amountt FROM orders LIMIT 10", schema);
            assertThat(result.allowed()).isFalse();
            assertThat(result.reason()).contains("amount");  // 유사 컬럼 hint 포함
        }

        @Test
        void nullSchema_skipsExistenceCheck() {
            // schema=null 이면 기본 검증만 수행
            var result = validator.validate(
                    "SELECT id, amount FROM orders LIMIT 10", (Map<String, List<ColumnInfo>>) null);
            assertThat(result.allowed()).isTrue();
        }

        @Test
        void baseValidationFails_beforeExistenceCheck() {
            // SELECT * 는 schema 전달 여부에 관계없이 기본 검증에서 거부
            var result = validator.validate("SELECT * FROM orders", schema);
            assertThat(result.allowed()).isFalse();
            assertThat(result.reason()).contains("SELECT *");
        }

        // ── 항목 1: 절 커버리지 확장 (GROUP BY/ORDER BY/HAVING/JOIN ON) ──────────

        @Test
        void orderBy_excludedColumn_denied() {
            // ORDER BY 절의 excluded(PII) 컬럼도 차단되어야 함 (이전엔 우회됨)
            var result = validator.validate("SELECT id FROM orders WHERE id > 0 ORDER BY ssn", schema);
            assertThat(result.allowed()).isFalse();
            assertThat(result.reason()).contains("ssn");
        }

        @Test
        void groupBy_hallucinatedColumn_denied() {
            var result = validator.validate(
                    "SELECT COUNT(*) AS cnt FROM orders GROUP BY nonexistent_col", schema);
            assertThat(result.allowed()).isFalse();
            assertThat(result.reason()).contains("nonexistent_col");
        }

        @Test
        void joinOn_hallucinatedColumn_denied() {
            var result = validator.validate(
                    "SELECT o.id FROM orders o JOIN orders b ON o.id = b.bogus_col WHERE o.id > 0",
                    schema);
            assertThat(result.allowed()).isFalse();
            assertThat(result.reason()).contains("bogus_col");
        }

        @Test
        void having_knownColumn_allowed() {
            var result = validator.validate(
                    "SELECT status, SUM(COALESCE(amount,0)) AS total FROM orders WHERE id > 0 " +
                    "GROUP BY status HAVING SUM(COALESCE(amount,0)) > 0", schema);
            assertThat(result.allowed()).isTrue();
        }

        @Test
        void orderBy_byAlias_allowed() {
            // SELECT alias(total)를 ORDER BY 가 참조 — 환각 컬럼으로 오탐하면 안 됨 (오탐 회귀 가드)
            var result = validator.validate(
                    "SELECT SUM(COALESCE(amount,0)) AS total FROM orders WHERE status = 'completed' " +
                    "GROUP BY status ORDER BY total", schema);
            assertThat(result.allowed()).isTrue();
        }
    }

    // ── 항목 2: 정책 게이트 (풀스캔 방지 + NULL 산술 함정) ──────────────────────

    @Nested
    class PolicyGateTest {

        private Map<String, List<ColumnInfo>> schema;

        @BeforeEach
        void setUpSchema() {
            schema = Map.of("orders", List.of(
                    new ColumnInfo("id", "int", false, ""),
                    new ColumnInfo("amount", "decimal", true, "거래 금액"),  // nullable
                    new ColumnInfo("status", "varchar", false, "")
            ));
        }

        @Test
        void fullScan_noWhereNoAggregate_denied() {
            var result = validator.validate("SELECT id, amount FROM orders", schema);
            assertThat(result.allowed()).isFalse();
            assertThat(result.reason()).contains("전체 테이블 스캔");
        }

        @Test
        void fullScan_withAggregate_allowed() {
            var result = validator.validate("SELECT SUM(COALESCE(amount,0)) AS total FROM orders", schema);
            assertThat(result.allowed()).isTrue();
        }

        @Test
        void fullScan_withWhere_allowed() {
            var result = validator.validate("SELECT id FROM orders WHERE status = 'completed'", schema);
            assertThat(result.allowed()).isTrue();
        }

        @Test
        void nullableSum_withoutCoalesce_denied() {
            var result = validator.validate(
                    "SELECT SUM(amount) AS total FROM orders WHERE status = 'completed'", schema);
            assertThat(result.allowed()).isFalse();
            assertThat(result.reason()).contains("COALESCE");
        }

        @Test
        void nullableSum_withCoalesce_allowed() {
            var result = validator.validate(
                    "SELECT SUM(COALESCE(amount,0)) AS total FROM orders WHERE status = 'completed'", schema);
            assertThat(result.allowed()).isTrue();
        }

        @Test
        void nonNullableSum_allowed() {
            var result = validator.validate(
                    "SELECT SUM(id) AS total FROM orders WHERE status = 'completed'", schema);
            assertThat(result.allowed()).isTrue();
        }

        // ── 0 나눗셈 함정 ──────────────────────────────────────────────────────

        @Test
        void divisionByColumn_withoutNullif_denied() {
            var result = validator.validate(
                    "SELECT id / amount AS ratio FROM orders WHERE status = 'completed'", schema);
            assertThat(result.allowed()).isFalse();
            assertThat(result.reason()).contains("NULLIF");
        }

        @Test
        void divisionByColumn_withNullif_allowed() {
            var result = validator.validate(
                    "SELECT id / NULLIF(amount, 0) AS ratio FROM orders WHERE status = 'completed'", schema);
            assertThat(result.allowed()).isTrue();
        }

        @Test
        void divisionByNonZeroConstant_allowed() {
            var result = validator.validate(
                    "SELECT id / 2 AS half FROM orders WHERE status = 'completed'", schema);
            assertThat(result.allowed()).isTrue();
        }
    }
}
