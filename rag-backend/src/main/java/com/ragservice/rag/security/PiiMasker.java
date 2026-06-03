package com.ragservice.rag.security;

import com.ragservice.rag.domain.MaskingRule;
import com.ragservice.rag.repository.MaskingRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * PII 마스킹 컴포넌트.
 *
 * ADR-0008: 모든 LLM 응답 경로에 적용.
 * ADR-0007: SQL 결과 PII 마스킹 — Layer 1 + Layer 3.
 * M2: masking_level (NONE / STANDARD / AGGRESSIVE) 지원.
 * M6: 마스킹 규칙을 masking_rule DB 테이블로 이관 (Admin UI 관리).
 *     - enabled=true 규칙만 sort_order 순으로 적용 (긴 패턴 먼저 — 오탐 방지).
 *     - level=standard 는 STANDARD/AGGRESSIVE 모두에서 적용.
 *     - level=aggressive 는 AGGRESSIVE 에서만 적용.
 *     - 60초 인메모리 캐시 + evict(). DB 비어있거나 조회 실패 시 DEFAULT_RULES fallback.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PiiMasker {

    public enum MaskingLevel { NONE, STANDARD, AGGRESSIVE }

    private final MaskingRuleRepository maskingRuleRepository;

    /** 캐시 TTL (ms) — DB 변경이 빠르게 반영되되 매 호출 DB 조회는 피한다. */
    private static final long CACHE_TTL_MS = 60_000;

    /**
     * DB 가 비어있거나 조회 실패 시 사용하는 하드코딩 fallback 규칙.
     *
     * requirements/07-auth-security.md 기준 8개 PII 패턴 중 운영 결정 제외 항목:
     *   - 이메일: 운영 결정(2026-05)으로 기본 비활성 — fallback 에도 미포함.
     *   - 이름:   honorific 컨텍스트(씨/님/귀하) + "이름:" 라벨 기반 매칭 — 오탐 최소화.
     *   - 주소:   행정 단위(시/도/구/로/길) 포함 패턴 — 구조적 주소만 마스킹.
     *
     * W-2 (security-checklist.md): DB 장애 시 이름/주소 누락 방지를 위해 fallback 에 추가.
     */
    private static final List<CompiledRule> DEFAULT_RULES = List.of(
            // ── STANDARD 패턴 ───────────────────────────────────────────────────────
            new CompiledRule("\\d{6}-[1-4]\\d{6}", "[주민번호]", false),
            new CompiledRule("01[016789]-?\\d{3,4}-?\\d{4}", "[전화번호]", false),
            new CompiledRule("\\d{4}[\\s\\-]?\\d{4}[\\s\\-]?\\d{4}[\\s\\-]?\\d{4}", "[카드번호]", false),
            new CompiledRule("EMP\\d{4,6}", "[사번]", false),
            // 이름: honorific(씨/님/귀하) 또는 "이름:" 라벨 뒤 한글 2~4자.
            // 단독 한글 이름은 오탐이 많아 컨텍스트 기반으로만 매칭.
            new CompiledRule("[가-힣]{2,4}(?:씨|님|귀하)", "[이름]", false),
            new CompiledRule("(?<=이름[:\\s]{0,3})[가-힣]{2,4}", "[이름]", false),
            // 주소: 광역자치단체(시/도) + 기초자치단체(시/군/구) + 도로명/지번 포함 패턴.
            // aggressive=false — 구조적 주소만 매칭하므로 STANDARD 에서도 안전.
            new CompiledRule(
                "[가-힣]+(?:특별시|광역시|특별자치시|도|특별자치도)\\s+[가-힣]+(?:시|군|구)\\s+[가-힣0-9\\s]+(?:로|길|동|읍|면)\\s+\\d+",
                "[주소]", false),
            // ── AGGRESSIVE 패턴 ─────────────────────────────────────────────────────
            new CompiledRule("\\d{3,6}-?\\d{2,6}-?\\d{4,8}", "[계좌번호]", true),
            new CompiledRule("\\d{3}-\\d{2}-\\d{5}", "[사업자번호]", true)
    );

    private volatile List<CompiledRule> cachedRules = null;
    private final AtomicLong cacheLoadedAt = new AtomicLong(0);

    /**
     * 캐시 무효화 — 규칙 CUD 후 호출.
     */
    public void evict() {
        cachedRules = null;
        cacheLoadedAt.set(0);
        log.info("PiiMasker rule cache evicted");
    }

    private List<CompiledRule> rules() {
        long now = System.currentTimeMillis();
        List<CompiledRule> local = cachedRules;
        if (local != null && (now - cacheLoadedAt.get()) < CACHE_TTL_MS) {
            return local;
        }
        synchronized (this) {
            if (cachedRules != null && (now - cacheLoadedAt.get()) < CACHE_TTL_MS) {
                return cachedRules;
            }
            List<CompiledRule> loaded = loadFromDb();
            cachedRules = loaded;
            cacheLoadedAt.set(System.currentTimeMillis());
            return loaded;
        }
    }

    private List<CompiledRule> loadFromDb() {
        try {
            List<MaskingRule> dbRules = maskingRuleRepository.findByEnabledTrueOrderBySortOrderAsc();
            if (dbRules.isEmpty()) {
                log.warn("masking_rule table empty — using DEFAULT_RULES fallback");
                return DEFAULT_RULES;
            }
            List<CompiledRule> compiled = new ArrayList<>(dbRules.size());
            for (MaskingRule r : dbRules) {
                try {
                    boolean aggressive = "aggressive".equalsIgnoreCase(r.getLevel());
                    compiled.add(new CompiledRule(r.getPattern(), r.getReplacement(), aggressive));
                } catch (Exception e) {
                    // 잘못된 정규식은 등록 단계에서 막지만, 방어적으로 스킵.
                    log.error("Skipping invalid masking rule '{}': {}", r.getName(), e.getMessage());
                }
            }
            return compiled.isEmpty() ? DEFAULT_RULES : compiled;
        } catch (Exception e) {
            log.error("Failed to load masking rules from DB — using fallback: {}", e.getMessage());
            return DEFAULT_RULES;
        }
    }

    /**
     * masking_level 문자열로 마스킹 (rag_table_config.pii_masking_level).
     */
    public String mask(String text, String level) {
        if (text == null) return null;
        MaskingLevel ml = switch (level == null ? "standard" : level.toLowerCase()) {
            case "aggressive" -> MaskingLevel.AGGRESSIVE;
            case "none" -> MaskingLevel.NONE;
            default -> MaskingLevel.STANDARD;
        };
        return mask(text, ml);
    }

    /**
     * 모든 LLM 응답 경로 (ADR-0008) — STANDARD 마스킹.
     */
    public String mask(String text) {
        return mask(text, MaskingLevel.STANDARD);
    }

    public String mask(String text, MaskingLevel level) {
        if (text == null) return null;
        if (level == MaskingLevel.NONE) return text;

        String result = text;
        for (CompiledRule rule : rules()) {
            // aggressive 규칙은 AGGRESSIVE 레벨에서만 적용.
            if (rule.aggressive() && level != MaskingLevel.AGGRESSIVE) continue;
            result = rule.pattern().matcher(result).replaceAll(rule.replacement());
        }
        return result;
    }

    /**
     * 컴파일된 마스킹 규칙. aggressive=true 면 AGGRESSIVE 레벨에서만 적용.
     */
    record CompiledRule(String regex, String replacement, boolean aggressive) {
        private static final ConcurrentHashMap<String, Pattern> CACHE = new ConcurrentHashMap<>();

        Pattern pattern() {
            return CACHE.computeIfAbsent(regex, Pattern::compile);
        }
    }
}
