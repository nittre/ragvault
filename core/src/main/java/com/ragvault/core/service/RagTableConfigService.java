package com.ragvault.core.service;

import com.ragvault.core.domain.RagTableConfig;
import com.ragvault.core.repository.RagTableConfigRepository;
import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RAG 대상 테이블 설정 서비스.
 *
 * 인메모리 캐시로 빠른 조회 제공.
 * data_sensitivity='restricted' → Phase 0 거부 (ADR-0002).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagTableConfigService {

    private final RagTableConfigRepository repository;

    // 키: "datasourceId:sourceTable" — 서로 다른 데이터소스가 같은 테이블명을 쓸 수 있으므로
    // 테이블명만으로 키를 잡으면 서로 다른 데이터소스의 설정이 충돌한다.
    private final ConcurrentHashMap<String, RagTableConfig> cache = new ConcurrentHashMap<>();
    private volatile boolean cacheLoaded = false;

    private static String key(Integer datasourceId, String sourceTable) {
        return datasourceId + ":" + sourceTable;
    }

    @PostConstruct
    public void loadCache() {
        refreshCache();
    }

    public void refreshCache() {
        List<RagTableConfig> all = repository.findAllByIsActiveTrue();
        cache.clear();
        all.forEach(c -> cache.put(key(c.getDatasourceId(), c.getSourceTable()), c));
        cacheLoaded = true;
        log.info("RagTableConfig cache loaded: {} tables", cache.size());
    }

    public Optional<RagTableConfig> findByTable(Integer datasourceId, String tableName) {
        return Optional.ofNullable(cache.get(key(datasourceId, tableName)));
    }

    public List<RagTableConfig> findAllActive() {
        return new ArrayList<>(cache.values());
    }

    /**
     * 새 테이블 등록.
     *
     * data_sensitivity='restricted' → Phase 0 거부 (ADR-0002).
     * data_sensitivity='internal' → 경고 로그.
     */
    @Transactional
    public RagTableConfig register(RagTableConfig config) {
        if ("restricted".equals(config.getDataSensitivity())) {
            throw new IllegalArgumentException(
                    "Phase 0: data_sensitivity='restricted' 테이블 등록 거부. " +
                    "부서별 기밀은 Phase 1+에 지원됩니다.");
        }
        if ("internal".equals(config.getDataSensitivity())) {
            log.warn("내부 데이터 테이블 등록: {} (data_sensitivity=internal). 확인 필요.",
                    config.getSourceTable());
        }
        // 비활성화된 레코드가 남아있으면 먼저 삭제 후 새로 insert (unique constraint 회피)
        if (config.getDatasourceId() != null) {
            repository.findBySourceTableAndDatasourceId(config.getSourceTable(), config.getDatasourceId())
                    .ifPresent(existing -> { repository.delete(existing); repository.flush(); });
        } else {
            repository.findBySourceTable(config.getSourceTable())
                    .ifPresent(existing -> { repository.delete(existing); repository.flush(); });
        }
        RagTableConfig saved = repository.save(config);
        cache.put(key(saved.getDatasourceId(), saved.getSourceTable()), saved);
        return saved;
    }

    /**
     * 어드민 UI 삭제 — hard delete (unique constraint 해제, 재등록 가능).
     */
    @Transactional
    public void deactivate(String sourceTable) {
        repository.findBySourceTable(sourceTable).ifPresent(c -> {
            repository.delete(c);
            cache.remove(key(c.getDatasourceId(), sourceTable));
            log.info("RagTableConfig deleted: {}", sourceTable);
        });
    }

    /**
     * 데이터소스 스코프 삭제.
     */
    @Transactional
    public void deactivate(String sourceTable, Integer datasourceId) {
        repository.findBySourceTableAndDatasourceId(sourceTable, datasourceId).ifPresent(c -> {
            repository.delete(c);
            cache.remove(key(datasourceId, sourceTable));
            log.info("RagTableConfig deleted: table={}, dsId={}", sourceTable, datasourceId);
        });
    }
}
