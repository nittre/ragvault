package com.ragservice.rag.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * binlog_position 테이블 엔티티 (싱글톤, id=1).
 *
 * ADR-0001: GTID 기반 binlog 위치 추적.
 * id=1 고정 row로 관리.
 */
@Entity
@Table(name = "binlog_position")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BinlogPosition {

    @Id
    private Integer id = 1;

    @Column(name = "gtid_set", nullable = false, columnDefinition = "TEXT")
    private String gtidSet = "";

    @Column(name = "last_event_at")
    private Instant lastEventAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
