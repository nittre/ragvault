package com.ragservice.rag.service;
import com.ragvault.core.service.*;

import com.ragservice.rag.repository.*;
import com.ragvault.core.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * WhitelistSyncService.parseDdl() 단위 테스트.
 *
 * 외부 의존성 없이 파싱 로직만 검증.
 * 백틱·스키마 prefix·IF EXISTS 등 edge case 포함.
 */
class WhitelistSyncServiceDdlParseTest {

    private WhitelistSyncService svc;

    @BeforeEach
    void setUp() {
        // parseDdl은 외부 의존성 없는 순수 로직 — mock으로 주입
        svc = new WhitelistSyncService(
                mock(SyncModeConfigRepository.class),
                mock(SqlTableConfigRepository.class),
                mock(RagTableConfigRepository.class),
                mock(DdlEventRepository.class),
                mock(SchemaInspectorService.class),
                mock(SensitivityAnalysisService.class),
                mock(RagColumnSuggestionService.class)
        );
    }

    // ── DROP TABLE ───────────────────────────────────────────────────────────

    static Stream<Arguments> dropTableCases() {
        return Stream.of(
                Arguments.of("DROP TABLE orders",                          "orders"),
                Arguments.of("DROP TABLE IF EXISTS orders",                "orders"),
                Arguments.of("drop table orders",                          "orders"),
                Arguments.of("DROP TABLE `orders`",                        "orders"),
                Arguments.of("DROP TABLE `mydb`.`orders`",                 "orders"),
                Arguments.of("DROP TABLE \"orders\"",                      "orders"),
                Arguments.of("DROP TABLE  orders ;",                       "orders")
        );
    }

    @ParameterizedTest(name = "[DROP] {0}")
    @MethodSource("dropTableCases")
    void parseDdl_dropTable(String sql, String expectedTable) {
        var action = svc.parseDdl(sql);
        assertThat(action.type()).isEqualTo(WhitelistSyncService.DdlType.DROP_TABLE);
        assertThat(action.tableName()).isEqualTo(expectedTable);
    }

    // ── CREATE TABLE ─────────────────────────────────────────────────────────

    static Stream<Arguments> createTableCases() {
        return Stream.of(
                Arguments.of("CREATE TABLE orders",                           "orders"),
                Arguments.of("CREATE TABLE IF NOT EXISTS orders",             "orders"),
                Arguments.of("create table orders",                           "orders"),
                Arguments.of("CREATE TABLE `orders` (id INT PRIMARY KEY)",    "orders"),
                Arguments.of("CREATE TABLE `mydb`.`orders` (id INT)",         "orders")
        );
    }

    @ParameterizedTest(name = "[CREATE] {0}")
    @MethodSource("createTableCases")
    void parseDdl_createTable(String sql, String expectedTable) {
        var action = svc.parseDdl(sql);
        assertThat(action.type()).isEqualTo(WhitelistSyncService.DdlType.CREATE_TABLE);
        assertThat(action.tableName()).isEqualTo(expectedTable);
    }

    // ── RENAME TABLE ─────────────────────────────────────────────────────────

    static Stream<Arguments> renameTableCases() {
        return Stream.of(
                Arguments.of("RENAME TABLE orders TO orders_v2",         "orders",     "orders_v2"),
                Arguments.of("rename table orders to orders_v2",         "orders",     "orders_v2"),
                Arguments.of("RENAME TABLE `orders` TO `orders_v2`",     "orders",     "orders_v2"),
                Arguments.of("RENAME TABLE `mydb`.`orders` TO orders_v2","orders",     "orders_v2")
        );
    }

    @ParameterizedTest(name = "[RENAME] {0}")
    @MethodSource("renameTableCases")
    void parseDdl_renameTable(String sql, String expectedOld, String expectedNew) {
        var action = svc.parseDdl(sql);
        assertThat(action.type()).isEqualTo(WhitelistSyncService.DdlType.RENAME_TABLE);
        assertThat(action.tableName()).isEqualTo(expectedOld);
        assertThat(action.targetName()).isEqualTo(expectedNew);
    }

    // ── TRUNCATE ─────────────────────────────────────────────────────────────

    static Stream<Arguments> truncateCases() {
        return Stream.of(
                Arguments.of("TRUNCATE TABLE orders",  "orders"),
                Arguments.of("TRUNCATE orders",        "orders"),
                Arguments.of("truncate table orders",  "orders")
        );
    }

    @ParameterizedTest(name = "[TRUNCATE] {0}")
    @MethodSource("truncateCases")
    void parseDdl_truncate(String sql, String expectedTable) {
        var action = svc.parseDdl(sql);
        assertThat(action.type()).isEqualTo(WhitelistSyncService.DdlType.TRUNCATE_TABLE);
        assertThat(action.tableName()).isEqualTo(expectedTable);
    }

    // ── ALTER TABLE DROP COLUMN ───────────────────────────────────────────────

    static Stream<Arguments> alterDropColumnCases() {
        return Stream.of(
                Arguments.of("ALTER TABLE orders DROP COLUMN email",                        "orders", "email"),
                Arguments.of("alter table orders drop column email",                        "orders", "email"),
                Arguments.of("ALTER TABLE `orders` DROP COLUMN `email`",                   "orders", "email"),
                Arguments.of("ALTER TABLE `mydb`.`orders` DROP COLUMN `email`",            "orders", "email"),
                Arguments.of("ALTER TABLE orders DROP COLUMN email, ADD COLUMN phone INT", "orders", "email")
        );
    }

    @ParameterizedTest(name = "[ALTER DROP COL] {0}")
    @MethodSource("alterDropColumnCases")
    void parseDdl_alterDropColumn(String sql, String expectedTable, String expectedCol) {
        var action = svc.parseDdl(sql);
        assertThat(action.type()).isEqualTo(WhitelistSyncService.DdlType.ALTER_DROP_COLUMN);
        assertThat(action.tableName()).isEqualTo(expectedTable);
        assertThat(action.columnName()).isEqualTo(expectedCol);
    }

    // ── ALTER TABLE ADD COLUMN ────────────────────────────────────────────────

    static Stream<Arguments> alterAddColumnCases() {
        return Stream.of(
                Arguments.of("ALTER TABLE orders ADD COLUMN phone VARCHAR(20)", "orders", "phone"),
                Arguments.of("alter table orders add column phone varchar(20)", "orders", "phone"),
                Arguments.of("ALTER TABLE `orders` ADD COLUMN `phone` VARCHAR(20)", "orders", "phone"),
                Arguments.of("ALTER TABLE orders ADD phone VARCHAR(20)",             "orders", "phone")
        );
    }

    @ParameterizedTest(name = "[ALTER ADD COL] {0}")
    @MethodSource("alterAddColumnCases")
    void parseDdl_alterAddColumn(String sql, String expectedTable, String expectedCol) {
        var action = svc.parseDdl(sql);
        assertThat(action.type()).isEqualTo(WhitelistSyncService.DdlType.ALTER_ADD_COLUMN);
        assertThat(action.tableName()).isEqualTo(expectedTable);
        assertThat(action.columnName()).isEqualTo(expectedCol);
    }

    // ── ALTER TABLE RENAME COLUMN ─────────────────────────────────────────────

    static Stream<Arguments> alterRenameColumnCases() {
        return Stream.of(
                Arguments.of("ALTER TABLE orders RENAME COLUMN email TO email_address", "orders", "email", "email_address"),
                Arguments.of("alter table orders rename column email to email_address",  "orders", "email", "email_address")
        );
    }

    @ParameterizedTest(name = "[ALTER RENAME COL] {0}")
    @MethodSource("alterRenameColumnCases")
    void parseDdl_alterRenameColumn(String sql, String expectedTable, String expectedOldCol, String expectedNewCol) {
        var action = svc.parseDdl(sql);
        assertThat(action.type()).isEqualTo(WhitelistSyncService.DdlType.ALTER_RENAME_COLUMN);
        assertThat(action.tableName()).isEqualTo(expectedTable);
        assertThat(action.columnName()).isEqualTo(expectedOldCol);
        assertThat(action.targetName()).isEqualTo(expectedNewCol);
    }

    // ── UNKNOWN ───────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "[UNKNOWN] \"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = {
            "SELECT * FROM orders",
            "INSERT INTO orders VALUES (1)",
            "UPDATE orders SET name='x'",
            "   ",
            "BEGIN",
            "COMMIT"
    })
    void parseDdl_unknown(String sql) {
        var action = svc.parseDdl(sql);
        assertThat(action.type()).isEqualTo(WhitelistSyncService.DdlType.UNKNOWN);
    }

    // ── parseDdlAll: 멀티 절 ALTER TABLE ──────────────────────────────────────

    @Test
    void parseDdlAll_alterDropThenAdd_returnsBothActions() {
        var actions = svc.parseDdlAll(
                "ALTER TABLE orders DROP COLUMN email, ADD COLUMN phone VARCHAR(20)");

        assertThat(actions).hasSize(2);
        assertThat(actions.get(0).type()).isEqualTo(WhitelistSyncService.DdlType.ALTER_DROP_COLUMN);
        assertThat(actions.get(0).tableName()).isEqualTo("orders");
        assertThat(actions.get(0).columnName()).isEqualTo("email");

        assertThat(actions.get(1).type()).isEqualTo(WhitelistSyncService.DdlType.ALTER_ADD_COLUMN);
        assertThat(actions.get(1).tableName()).isEqualTo("orders");
        assertThat(actions.get(1).columnName()).isEqualTo("phone");
    }

    @Test
    void parseDdlAll_alterRenameAndDrop_returnsBothActions() {
        var actions = svc.parseDdlAll(
                "ALTER TABLE users RENAME COLUMN email TO email_address, DROP COLUMN phone");

        assertThat(actions).hasSize(2);
        assertThat(actions.get(0).type()).isEqualTo(WhitelistSyncService.DdlType.ALTER_RENAME_COLUMN);
        assertThat(actions.get(0).columnName()).isEqualTo("email");
        assertThat(actions.get(0).targetName()).isEqualTo("email_address");

        assertThat(actions.get(1).type()).isEqualTo(WhitelistSyncService.DdlType.ALTER_DROP_COLUMN);
        assertThat(actions.get(1).columnName()).isEqualTo("phone");
    }

    @Test
    void parseDdlAll_alterWithDecimalType_splitsCorrectly() {
        // DECIMAL(10,2) 안의 쉼표를 절 구분자로 오해하면 안 됨
        var actions = svc.parseDdlAll(
                "ALTER TABLE products ADD COLUMN price DECIMAL(10, 2), ADD COLUMN qty INT");

        assertThat(actions).hasSize(2);
        assertThat(actions.get(0).type()).isEqualTo(WhitelistSyncService.DdlType.ALTER_ADD_COLUMN);
        assertThat(actions.get(0).columnName()).isEqualTo("price");
        assertThat(actions.get(1).type()).isEqualTo(WhitelistSyncService.DdlType.ALTER_ADD_COLUMN);
        assertThat(actions.get(1).columnName()).isEqualTo("qty");
    }

    // ── parseDdlAll: 멀티 쌍 RENAME TABLE ────────────────────────────────────

    @Test
    void parseDdlAll_renameTwoTables_returnsAllPairs() {
        var actions = svc.parseDdlAll("RENAME TABLE orders TO orders_v2, users TO members");

        assertThat(actions).hasSize(2);
        assertThat(actions.get(0).type()).isEqualTo(WhitelistSyncService.DdlType.RENAME_TABLE);
        assertThat(actions.get(0).tableName()).isEqualTo("orders");
        assertThat(actions.get(0).targetName()).isEqualTo("orders_v2");
        assertThat(actions.get(1).tableName()).isEqualTo("users");
        assertThat(actions.get(1).targetName()).isEqualTo("members");
    }

    // ── parseDdlAll: 단일 절 위임 ─────────────────────────────────────────────

    @Test
    void parseDdlAll_singleClause_returnsSingleAction() {
        var actions = svc.parseDdlAll("DROP TABLE orders");
        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).type()).isEqualTo(WhitelistSyncService.DdlType.DROP_TABLE);
    }

    @Test
    void parseDdlAll_unknown_returnsUnknown() {
        var actions = svc.parseDdlAll("SELECT 1");
        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).type()).isEqualTo(WhitelistSyncService.DdlType.UNKNOWN);
    }
}
