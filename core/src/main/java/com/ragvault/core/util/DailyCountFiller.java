package com.ragvault.core.util;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 일별 집계 GROUP BY 쿼리는 데이터가 없는 날짜를 아예 반환하지 않는다.
 * 어드민 통계 화면의 "일별 추이" 차트가 항상 고정된 N일 구간을 그리려면
 * 값이 없는 날짜도 0건으로 채워 연속된 날짜 목록을 만들어야 한다.
 *
 * 챗 서비스(app-internal)와 위젯 서비스(app-widget) 어드민이 동일한 형태의
 * 일별 추이 차트를 쓰므로 공통 유틸로 둔다.
 */
public final class DailyCountFiller {

    private DailyCountFiller() {}

    public record DailyCount(String day, long count) {}

    /**
     * @param sparseRows 네이티브 쿼리 결과 (row[0]=날짜, row[1]=건수), 데이터 없는 날짜는 누락됨
     * @param days       채울 구간 길이 (오늘 포함 최근 N일)
     */
    public static List<DailyCount> fill(List<Object[]> sparseRows, int days) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : sparseRows) {
            counts.put(toDateString(row[0]), ((Number) row[1]).longValue());
        }

        LocalDate today = LocalDate.now();
        List<DailyCount> result = new ArrayList<>(days);
        for (int i = days - 1; i >= 0; i--) {
            String day = today.minusDays(i).toString();
            result.add(new DailyCount(day, counts.getOrDefault(day, 0L)));
        }
        return result;
    }

    private static String toDateString(Object dayObj) {
        if (dayObj instanceof Date sqlDate) {
            return sqlDate.toLocalDate().toString();
        }
        if (dayObj instanceof LocalDate ld) {
            return ld.toString();
        }
        return String.valueOf(dayObj);
    }
}
