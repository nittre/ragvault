package com.ragservice.rag.service;

import com.ragservice.rag.domain.SqlTableConfig;
import com.ragservice.rag.repository.SqlTableConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

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
}
