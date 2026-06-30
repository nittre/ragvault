package com.ragvault.core.repository;

import com.ragvault.core.domain.DataSourceConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * datasource_config 리포지토리.
 *
 * 멀티 MySQL/MariaDB 데이터소스 조회.
 * is_active=true 인 데이터소스만 동기화·라우팅에 사용.
 */
public interface DataSourceConfigRepository extends JpaRepository<DataSourceConfig, Integer> {

    List<DataSourceConfig> findByIsActiveTrue();

    boolean existsByName(String name);
}
