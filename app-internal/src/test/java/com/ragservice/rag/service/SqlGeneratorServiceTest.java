package com.ragservice.rag.service;
import com.ragvault.core.service.*;

import com.ragvault.core.service.SqlGeneratorService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SqlGeneratorService.extractQueries 단위 테스트.
 *
 * 모델이 'SQL만 출력' 지시를 어기고 SQL 뒤에 설명을 덧붙이면
 * 설명까지 SQL로 넘어가 SqlValidator 파싱이 실패(denied/SYNTAX_ERROR)했다.
 * 다양한 설명 부착 패턴에서 순수 SQL만 추출되는지 검증한다.
 */
class SqlGeneratorServiceTest {

    // extractQueries 는 ChatClient 를 사용하지 않으므로 null 주입으로 충분하다.
    private final SqlGeneratorService service = new SqlGeneratorService(null);

    @Test
    void 세미콜론_뒤_마크다운_헤더_설명을_제거한다() {
        String response = """
                SELECT id, name, created_at
                FROM goods_auction
                ORDER BY created_at DESC
                LIMIT 1;
                ### 🔍 설명
                1. 목적: 가장 최근 경매 건 조회
                """;

        List<String> result = service.extractQueries(response);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).doesNotContain("설명", "###", ";");
        assertThat(result.get(0)).endsWith("LIMIT 1");
    }

    @Test
    void 빈줄_뒤_볼드_설명을_제거한다() {
        String response = """
                SELECT goods_auction_idx, created_at FROM goods_auction ORDER BY created_at DESC LIMIT 1

                **설명:**
                1. SELECT * 대신 구체적인 컬럼명을 명시했습니다.
                """;

        List<String> result = service.extractQueries(response);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).doesNotContain("설명", "**");
        assertThat(result.get(0)).isEqualTo(
                "SELECT goods_auction_idx, created_at FROM goods_auction ORDER BY created_at DESC LIMIT 1");
    }

    @Test
    void 코드블록과_설명을_함께_제거한다() {
        String response = """
                ```sql
                SELECT id FROM users WHERE id = 1
                ```
                위 쿼리는 사용자를 조회합니다.
                """;

        List<String> result = service.extractQueries(response);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isEqualTo("SELECT id FROM users WHERE id = 1");
    }

    @Test
    void NEXT_구분자로_다중_쿼리를_각각_추출한다() {
        String response = """
                SELECT id FROM posts LIMIT 1000
                ---NEXT---
                SELECT id FROM comments LIMIT 1000
                """;

        List<String> result = service.extractQueries(response);

        assertThat(result).containsExactly(
                "SELECT id FROM posts LIMIT 1000",
                "SELECT id FROM comments LIMIT 1000");
    }

    @Test
    void 설명없는_정상_멀티라인_SQL은_그대로_유지한다() {
        String response = """
                SELECT a, b
                FROM t
                WHERE x = 1""";

        List<String> result = service.extractQueries(response);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isEqualTo("SELECT a, b\nFROM t\nWHERE x = 1");
    }

    @Test
    void null_응답은_빈_리스트를_반환한다() {
        assertThat(service.extractQueries(null)).isEmpty();
    }
}
