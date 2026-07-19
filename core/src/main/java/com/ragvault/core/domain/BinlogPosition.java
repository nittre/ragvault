package com.ragvault.core.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * binlog_position 테이블 엔티티.
 *
 * ADR-0001: GTID 기반 binlog 위치 추적.
 * 데이터소스별로 한 행씩 관리 — id는 DB가 자동 생성한다(과거 id=1 고정값 사용 시
 * 두 번째 이후 데이터소스가 첫 데이터소스의 위치 행을 덮어쓰는 버그가 있었음).
 */
@Entity
@Table(name = "binlog_position")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BinlogPosition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "gtid_set", nullable = false, columnDefinition = "TEXT")
    private String gtidSet = "";

    @Column(name = "last_event_at")
    private Instant lastEventAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    /**
     * 멀티 데이터소스 식별자.
     * null = legacy rag.mysql.* 환경변수 경로 (기존 id=1 싱글톤 레코드 호환).
     */
    @Column(name = "datasource_id")
    private Integer datasourceId;
}
