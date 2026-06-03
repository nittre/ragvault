package com.ragservice.rag.config;

import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Configuration;

/**
 * 동기화 스케줄러 설정.
 *
 * @EnableSchedulerLock: ShedLock 활성화 (Redis 저장소, shedlock-provider-redis-spring).
 * BinlogSyncService의 @SchedulerLock이 작동하려면 이 어노테이션이 필요하다.
 * ADR-0001: 30분 주기 GTID 기반 동기화.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "20m")
public class SyncConfig {
    // ShedLock Redis provider는 shedlock-provider-redis-spring이 auto-configure
}
