# ADR-0001: binlog 동기화 — 30분 주기 + GTID 전용 위치 추적

- **상태**: Accepted
- **결정일**: 2026-05-19
- **결정자**: 시니어 백엔드 엔지니어 (사용자 옵션 B 채택)
- **관련 ADR**: —
- **영향 받는 문서**: `requirements/03-data-sync-pipeline.md`, `requirements/01-architecture.md` 섹션 5-1, `requirements/02-stack-reference.md`, `requirements/06-error-handling.md` 섹션 9, `requirements/TEAM-OVERVIEW.md` 섹션 7-1

## 컨텍스트

문서 간 세 가지 모순이 누적되어 있었다.
1. "매일 새벽 2시 1회 배치" — `TEAM-OVERVIEW`, `01`, `03`
2. "binlog 지연 1시간이면 Warning" — `06` (24시간 배치에서는 평상시 알람이 됨)
3. `binlog_position` 스키마에 `binlog_file`, `binlog_position`, `gtid_set` 셋 다 존재 — 어느 방식을 쓰는지 미정

근본 문제: `mysql-binlog-connector-java` 는 실시간 스트리밍 도구인데 24시간에 1회 배치로 운영하면 (a) 데이터 24시간 stale, (b) 새벽 2시 처리량 폭증, (c) binlog 보존 7일 한도에 fragile, (d) lag 메트릭이 거짓말한다.

## 결정

1. **주기**: 30분 cron — `@Scheduled(cron = "0 */30 * * * *")`, `@SchedulerLock(name = "binlogSync", lockAtMostFor = "20m")`.
2. **위치 추적**: GTID 전용. `binlog_position` 스키마에서 `binlog_file`, `binlog_position` 컬럼은 제거하고 `gtid_set` 만 사용.
3. **고객사 MySQL 사전 조건 추가**: `gtid_mode = ON`, `enforce_gtid_consistency = ON`.
4. **초기 동기화** 위치 기록: `SELECT @@global.gtid_executed`.
5. **알람 임계값 정합**: binlog lag > 60분 → Warning, > 4시간 → Critical.

## 결과

### 장점
- 데이터 신선도 ≤ 30분 (정상 운영)
- 새벽 2시 처리량 폭증 위험 제거 (분산 처리)
- 7일 binlog 보존 한도 안에 ≈ 336회 시도 가능 → 실패 회복 마진 큼
- GTID 는 master 페일오버에 안전 (file/position 은 fragile)
- lag 메트릭이 의미 있어짐

### 단점·트레이드오프
- 고객사 MySQL 에 GTID 활성화 요청 필요 (사전 조건 추가)
- 30분 간격 cron 이 BinaryLogClient connect/disconnect 반복 (MySQL 측 미미한 부하)

### 후속 작업
- Phase 1+ 검토: 24/7 실시간 streaming 모드 (트래픽 증가 시점)
- Spring Boot 코드에서 `binlog_file`/`binlog_position` 사용 금지 (코드 리뷰 시 verifier 회귀 검증)

## 대안

### 옵션 A — 실시간 스트리밍 (24/7 connected)
binlog 도구 본질에 가장 맞음. 그러나 Phase 0 MVP 단계에 Leader Election, in-memory back-pressure 큐, 재연결 로직 등 buggy 영역이 커서 부적합.

### 옵션 C — 현재(24시간 배치) 유지 + 메트릭 정합
모순은 표면적으로 해소되지만 데이터 24시간 stale 이라는 본질 문제는 그대로. 비즈니스 가치 손실.

### 옵션 D — Debezium + Kafka/RabbitMQ
업계 표준이나 Phase 0 에 과한 운영 부담. Phase 2+ 로드맵에 이미 있음.

## 참고

- `requirements/03-data-sync-pipeline.md` 섹션 1·2·7·9·11
- `requirements/02-stack-reference.md` `mysql-binlog-connector-java`
