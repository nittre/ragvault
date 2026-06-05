# 데이터 동기화 파이프라인 상세 설계

> 회사 MySQL → pgvector 동기화 메커니즘.
> binlog 기반 변경 감지 + 가변적 RAG 테이블 관리.

관련 문서:
- [01-architecture.md](01-architecture.md) — 전체 아키텍처
- [02-stack-reference.md](02-stack-reference.md) — 기술 스택 레퍼런스

---

## 목차

1. [개요](#1-개요)
2. [전체 흐름](#2-전체-흐름)
3. [RAG 대상 테이블 동적 관리](#3-rag-대상-테이블-동적-관리)
4. [청킹 전략](#4-청킹-전략)
5. [PII 마스킹](#5-pii-마스킹)
6. [binlog 이벤트 처리](#6-binlog-이벤트-처리)
7. [DDL 이벤트 하이브리드 처리](#7-ddl-이벤트-하이브리드-처리)
8. [실패 처리 및 재시도](#8-실패-처리-및-재시도)
9. [초기 동기화 (Initial Sync)](#9-초기-동기화-initial-sync)
10. [동기화 스케줄 및 수동 API](#10-동기화-스케줄-및-수동-api)
11. [DB 스키마 (동기화 관련)](#11-db-스키마-동기화-관련)
12. [관리자 워크플로우](#12-관리자-워크플로우)

---

## 1. 개요

### 핵심 결정사항

| 항목 | 결정 |
|------|------|
| 데이터 소스 | 회사 MySQL 직접 연결 (binlog 기반) |
| 변경 감지 | MySQL binlog (mysql-binlog-connector-java) |
| 우리 측 미러 DB | 없음 (직접 binlog 읽기) |
| 네트워크 | VPC Peering 또는 Site-to-Site VPN |
| 스케줄 | **30분 주기 배치** (cron `0 */30 * * * *`) + 수동 트리거 — 데이터 신선도 ≤ 30분 |
| binlog 위치 추적 | **GTID 전용** (file/position 컬럼은 deprecated, 사용 안 함) |
| RAG 대상 테이블 | 가변적 (rag_table_config 테이블) |
| 트랜잭션/집계 데이터 | **Text-to-SQL 경로** ([08-text-to-sql.md](08-text-to-sql.md) 참고) |
| 청킹 도구 | LangChain4j RecursiveCharacterTextSplitter |
| 기본 청크 크기 | 500 토큰, 오버랩 50 |
| PII 마스킹 | 정규식 기반 (Phase 0), NER 도입 (Phase 1+) |
| 데이터 분류(`data_sensitivity`) | Phase 0 스키마 도입 (public/internal/restricted), 등록 가드로 활용 |
| 청크/테이블 접근 그룹(`allowed_groups`/`access_groups`) | **Phase 0 스키마만**, 모든 값 `['all']` 기본 / **Phase 1+ 사용자 그룹 필터 활성화** |
| **Admin UI 등록 흐름 (ADR-0009)** | `/admin/rag-tables` 화면 — MySQL 자동 조회 + 컬럼 체크박스 + content_columns 순서 드래그 + 등록 후 모달 ("지금 동기화 시작?"). [admin-journeys.md A3](../docs/ux/admin-journeys.md) |
| **DDL 처리 admin UI (ADR-0009)** | `/admin/ddl-events` — 자동 영향 분석 패널 + 위험도별 wizard + Discord deep link. [admin-journeys.md A8](../docs/ux/admin-journeys.md) |
| UPDATE 처리 | 청크 전체 재생성 |
| DDL 처리 | **하이브리드** (LOW 자동 / MEDIUM 7일 자동 / HIGH 수동) |
| 재시도 | 3회 (1초 → 5초 → 25초) |
| 실패 시 binlog 위치 | 실패해도 전진 (격리) |
| 초기 동기화 | 풀 스냅샷 + binlog 시작 위치 기록 |
| 초기 동기화 병렬 | 8 스레드 |

### 회사 MySQL 사전 조건

```
- log-bin 활성화
- binlog_format = ROW
- binlog_row_image = FULL
- gtid_mode = ON
- enforce_gtid_consistency = ON
- binlog 보존 기간 최소 7일 (30분 주기 운영의 실패 회복 마진)
- 우리에게 부여할 권한:
  - REPLICATION SLAVE
  - REPLICATION CLIENT
  - SELECT (RAG 대상 테이블)
```

> 옵션 B 결정 — 30분 주기 배치 + GTID-only 추적.
> file/position은 master 페일오버 시 binlog 파일명이 바뀌어 fragile 하므로 사용하지 않는다.

---

## 2. 전체 흐름

### 정기 동기화 (30분 주기 배치)

```
[1] Spring @Scheduled 실행 (cron: 0 */30 * * * *)
    @SchedulerLock(name = "binlogSync", lockAtMostFor = "20m") ← Redis 분산 락
    → 다중 Pod 환경에서 한 Pod만 실행
    → 작업이 20분 안에 끝나도록 설계 (30분 다음 cron 전에 종료)
        ↓
[2] binlog_position 테이블에서 마지막 GTID set 조회
    {gtid_set: "uuid-1:1-12345"}
        ↓
[3] 회사 MySQL에 binlog 클라이언트 연결
    BinaryLogClient (mysql-binlog-connector-java)
    setGtidSet(lastGtidSet)    ← GTID 기반 위치 지정
    VPC Peering 또는 VPN 경유
        ↓
[4] 마지막 GTID 이후 binlog 이벤트 스트림 읽기
    이벤트 종류:
    - TABLE_MAP     (스키마 매핑, 자동)
    - WRITE_ROWS    (INSERT)
    - UPDATE_ROWS   (UPDATE)
    - DELETE_ROWS   (DELETE)
    - QUERY         (DDL: CREATE/ALTER/DROP)
    - GTID          (GTID 경계, 위치 추적용)
        ↓
[5] 각 이벤트별 처리
    ├── 데이터 이벤트:
    │   ├── rag_table_config에서 대상 여부 확인
    │   ├── 대상 아님 → 무시 (단, GTID 위치는 전진)
    │   ├── 대상임 → 청킹 + 임베딩 + pgvector
    │   └── PII 마스킹 (정규식)
    │
    └── DDL 이벤트:
        ├── ddl_events 테이블에 기록
        ├── Discord 알람
        └── 데이터 변경 처리는 그대로 계속
        ↓
[6] 실패한 이벤트는 binlog_events 테이블 기록
    → Spring Retry 3회 재시도 (1→5→25초)
    → 3회 누적 실패 시 Discord 알람
    → binlog_position 의 GTID set 은 그대로 전진 (실패 격리)
        ↓
[7] binlog_position.gtid_set 업데이트 (다음 cron 호출을 위해)
    → 다음 30분 후 cron 은 이 GTID 이후만 읽기
    → 정상 30분 주기 처리량은 보통 수백~수천 이벤트
        ↓
[8] sync_jobs 테이블에 완료 기록
    → 처리 건수, 성공/실패 통계, lag(초)
```

### 데이터 신선도와 신뢰성

```
[데이터 신선도]
- 정상 운영: ≤ 30분 (cron 주기)
- 실패 1회: ≤ 60분
- binlog 보존 기간 7일 ⇒ 최악 336회 연속 실패까지 회복 가능

[lag 알람 임계값]
- > 60분  → #alerts-warning
- > 4시간 → #alerts-critical
- 06-error-handling.md 섹션 9 와 일치

[GTID 사용 이유]
- 회사 MySQL 페일오버 시 binlog 파일명·position 이 깨질 수 있음
- GTID 는 트랜잭션 단위 globally unique → 페일오버에 안전
- 초기 동기화 시점에 SHOW MASTER STATUS 로 현재 gtid_executed 기록
```

---

## 3. RAG 대상 테이블 동적 관리

### 핵심 원칙

```
RAG 대상 테이블 목록은 코드에 하드코딩하지 않음.
DB 테이블 rag_table_config에 저장 → 운영 중 변경 가능.
관리자 API로 추가/제거 가능, 재배포 불필요.
```

### rag_table_config 스키마

```sql
CREATE TABLE rag_table_config (
    id                  SERIAL PRIMARY KEY,
    source_table        VARCHAR(100) NOT NULL UNIQUE,
    source_type         VARCHAR(50) NOT NULL,

    -- 청킹 설정
    chunking_strategy   VARCHAR(50) NOT NULL,           -- 'recursive', 'per-record'
    chunk_size          INT NOT NULL DEFAULT 500,
    chunk_overlap       INT NOT NULL DEFAULT 50,

    -- 컬럼 매핑
    title_column        VARCHAR(100),
    content_columns     TEXT[] NOT NULL,                -- 임베딩 대상 컬럼
    metadata_columns    TEXT[],                         -- 메타데이터로 저장할 컬럼
    pk_column           VARCHAR(100) NOT NULL,

    -- PII 마스킹 강도
    pii_masking_level   VARCHAR(20) DEFAULT 'standard', -- 'none', 'standard', 'aggressive'

    -- 데이터 분류 (Phase 0 도입, 등록 가드용)
    -- 'public'     : 공개 가능 데이터 (상품 카탈로그 등)
    -- 'internal'   : 사내 일반 (계약 약관 등)
    -- 'restricted' : 제한 — Phase 0 등록 시 admin 경고 필요
    data_sensitivity    VARCHAR(20) NOT NULL DEFAULT 'internal',

    -- 접근 그룹 (Phase 0: 스키마만, 항상 ['all']로 동작)
    -- Phase 1+: 사용자 그룹 모델 도입 시 활성화됨
    allowed_groups      TEXT[] NOT NULL DEFAULT ARRAY['all'],

    -- 상태
    is_active           BOOLEAN DEFAULT true,
    created_at          TIMESTAMP DEFAULT NOW(),
    updated_at          TIMESTAMP DEFAULT NOW(),

    CONSTRAINT chk_data_sensitivity
      CHECK (data_sensitivity IN ('public', 'internal', 'restricted'))
);
```

### 등록 가드 — Phase 0 운영 정책

```
[정책 — 고객사 admin과 합의서에 명시]
1. 전 직원 공개 가능한 데이터만 RAG 등록 대상
2. 부서별 기밀 (HR/임원/법무/영업비밀)은 Phase 0에 등록 금지
3. 부서별 권한 분리는 Phase 1 (예정 2026-08)에 제공

[운영 가드]
- data_sensitivity = 'restricted' 로 등록 요청 시 Spring Boot가 거부 (Phase 0)
- 'internal' 등록 시 admin Discord 알람 (#alerts-warning)
- 'public' 등록은 무알람

[합의서 (고객사 온보딩 시)]
"본 시스템은 사내 공개·일반 데이터를 대상으로 한다.
 부서별 기밀, 개인정보 1급, 영업비밀, 법무 hold 데이터의
 RAG 등록은 금지된다. 등록 책임은 고객사 admin에게 있다."
```

### 등록 예시

```sql
-- 상품 테이블
INSERT INTO rag_table_config (
    source_table, source_type, chunking_strategy,
    chunk_size, chunk_overlap,
    title_column, content_columns, metadata_columns, pk_column,
    pii_masking_level
) VALUES (
    'products', 'product', 'per-record',
    300, 0,
    'name', ARRAY['description', 'spec'], ARRAY['category', 'price'], 'id',
    'none'
);

-- 계약서 테이블
INSERT INTO rag_table_config (
    source_table, source_type, chunking_strategy,
    chunk_size, chunk_overlap,
    title_column, content_columns, metadata_columns, pk_column,
    pii_masking_level
) VALUES (
    'contracts', 'contract', 'recursive',
    500, 50,
    'title', ARRAY['content'], ARRAY['contract_date', 'parties'], 'id',
    'standard'
);

-- 고객 테이블
INSERT INTO rag_table_config (
    source_table, source_type, chunking_strategy,
    chunk_size, chunk_overlap,
    title_column, content_columns, metadata_columns, pk_column,
    pii_masking_level
) VALUES (
    'customers', 'customer', 'per-record',
    150, 0,
    'name', ARRAY['notes', 'preferences'], ARRAY['signup_date', 'tier'], 'id',
    'aggressive'
);
```

### 동적 적용 방식

```
[Spring Boot 시작 시]
1. rag_table_config 전체 로드
2. 메모리 캐시에 저장 (Redis 또는 ConcurrentHashMap)

[binlog 이벤트 수신 시]
1. event.getTable()로 테이블명 확인
2. 캐시에서 rag_table_config 조회
3. 없음 또는 is_active=false → 무시
4. 있음 → 설정대로 청킹/임베딩

[설정 변경 시]
1. 관리자 API 호출
2. DB 업데이트
3. 캐시 무효화 (즉시 반영)
4. 또는 N분마다 자동 갱신
```

---

## 4. 청킹 전략

### 도구

```
LangChain4j RecursiveCharacterTextSplitter
- 재귀적으로 큰 단위 → 작은 단위로 분리
- 문단 → 문장 → 단어 순으로 분할 시도
- 자연스러운 경계 유지
```

### 전략별 상세

#### 4-1. recursive (재귀 분할)

```
[적합 데이터]
- 계약서, 매뉴얼, 설명서, 정책 문서
- 긴 자유 형식 텍스트

[설정]
chunk_size: 500 토큰
chunk_overlap: 50 토큰 (10%)

[동작]
1. 전체 텍스트를 문단으로 분할
2. 문단이 500토큰 초과 시 → 문장으로 재분할
3. 문장도 500토큰 초과 시 → 단어로 재분할
4. 각 청크 끝에 50토큰을 다음 청크 시작에 중복 포함
   → 청크 경계의 문맥 보존

[예시]
원본: 10,000 토큰 계약서
→ 약 22개 청크 생성 (오버랩 포함)
```

#### 4-2. per-record (레코드 단위)

```
[적합 데이터]
- 상품 카탈로그, 고객 정보
- 구조화된 짧은 레코드

[설정]
chunk_size: 200~400 토큰
chunk_overlap: 0

[동작]
1. DB 행 1개 = 청크 1개
2. content_columns 값을 합쳐서 텍스트 생성
3. title_column으로 제목 부여
4. 토큰 한도 초과 시 절단 (드물게 발생)

[예시]
상품 1개:
{
  "name": "노트북 모델 A",
  "description": "고성능 그래픽카드 탑재...",
  "spec": "RAM 16GB, SSD 512GB..."
}
→ "노트북 모델 A\n고성능 그래픽카드 탑재...\nRAM 16GB, SSD 512GB..."
→ 1개 청크
```

### 청킹 후 컨텐츠 구조

```
청크 1개에 저장되는 텍스트:
[제목] {title_column 값}

[본문]
{content_columns 값들을 결합}

[메타데이터]
- source_table: 'contracts'
- source_id: '12345'
- chunk_index: 3
- {metadata_columns 값들}
```

---

## 5. PII 마스킹

### 마스킹 대상 (8개)

```
1. 이름
2. 주민등록번호
3. 전화번호
4. 이메일
5. 주소
6. 계좌번호
7. 카드번호
8. 사번/부서번호 (회사 내부 코드)
```

### 마스킹 레벨

```
[none] — 마스킹 안 함
- 공개 정보 (상품 정보 등)

[standard] — 명백한 PII만 마스킹
- 정규식 패턴 매칭
- 주민번호, 카드번호, 이메일, 전화번호

[aggressive] — 모든 PII 마스킹
- 정규식 + 이름/주소 패턴
- 고객 정보 테이블에 적용
```

### 정규식 패턴 (Phase 0)

```java
public class PiiMasker {
    
    private static final Map<String, Pattern> PATTERNS = Map.of(
        "주민등록번호", Pattern.compile("\\d{6}-?\\d{7}"),
        "전화번호", Pattern.compile("01[016789]-?\\d{3,4}-?\\d{4}"),
        "이메일", Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+"),
        "카드번호", Pattern.compile("\\d{4}-?\\d{4}-?\\d{4}-?\\d{4}"),
        "계좌번호", Pattern.compile("\\d{3,6}-?\\d{2,6}-?\\d{4,8}"),
        "사번", Pattern.compile("EMP\\d{4,6}"),  // 회사마다 다름
        // ... 등
    );
    
    public String mask(String text, PiiMaskingLevel level) {
        if (level == NONE) return text;
        
        String result = text;
        for (var entry : PATTERNS.entrySet()) {
            result = entry.getValue().matcher(result)
                .replaceAll("[" + entry.getKey() + "]");
        }
        
        if (level == AGGRESSIVE) {
            // 이름, 주소 추가 패턴 (한국어 이름 패턴 등)
            // Phase 1+ NER 모델로 대체
        }
        
        return result;
    }
}
```

### 마스킹 흐름

```
MySQL 원본:
"홍길동 고객(010-1234-5678)이 2024년 계약 체결"
        ↓
정규식 마스킹:
"홍길동 고객([전화번호])이 2024년 계약 체결"
        ↓
청킹 + 임베딩:
pgvector에 저장된 텍스트:
"홍길동 고객([전화번호])이 2024년 계약 체결"

※ 이름 "홍길동"은 Phase 0에서 마스킹 안 됨 (이름 정규식 없음)
※ Phase 1+에서 NER 도입 시 자동 마스킹
```

### Phase 1+ NER 도입 계획

```
[옵션 1] Llama Guard
- Ollama로 별도 실행
- PII 분류 전용 모델

[옵션 2] Korean NER 모델
- 한국어 특화 (KoBERT-NER 등)
- HuggingFace에서 다운로드

[옵션 3] LLM 호출
- Ollama qwen2.5에 "이 텍스트에서 PII를 [태그]로 마스킹해줘"
- 느림, 비용 (LLM 호출량 증가)
```

---

## 6. binlog 이벤트 처리

### 이벤트 종류별 처리

#### WRITE_ROWS (INSERT)

```
1. event.getRows()에서 새 행 데이터 추출
2. event.getTableMap()에서 테이블명 확인
3. rag_table_config 조회
   → 대상 아님: 무시
4. content_columns 추출 + 결합
5. PII 마스킹
6. 청킹 (rag_table_config 설정대로)
7. 각 청크에 대해 Ollama 임베딩 호출
8. document_chunks 테이블에 저장
   - source_type, source_table, source_id, chunk_index
   - content, embedding, metadata
```

#### UPDATE_ROWS (UPDATE)

```
1. event에서 old/new 행 추출
2. rag_table_config 조회
3. 기존 청크 모두 삭제
   DELETE FROM document_chunks
   WHERE source_table = ? AND source_id = ?
4. 새 행으로 청킹 + 임베딩 + 저장
   (INSERT와 동일 로직)

※ 부분 갱신 안 함 (단순성 우선)
※ 같은 source_id의 모든 청크 재생성
```

#### DELETE_ROWS (DELETE)

```
1. event에서 삭제된 행의 PK 추출
2. document_chunks에서 해당 source_id 청크 모두 삭제
   DELETE FROM document_chunks
   WHERE source_table = ? AND source_id = ?
3. 임베딩 호출 없음
```

#### TABLE_MAP

```
binlog의 내부 메타데이터 이벤트
- 테이블 ID ↔ 테이블명 매핑
- mysql-binlog-connector-java가 자동 처리
- 우리 코드에서 직접 다룰 필요 없음
```

#### QUERY (DDL)

```
ALTER, CREATE, DROP 등 DDL 문장
→ 섹션 7 참고 (반자동 처리)
```

### 멱등성 보장

```
같은 binlog 이벤트가 2번 처리될 가능성 (재시도 등)

대응:
1. content_hash 컬럼으로 중복 감지
   - 청크 텍스트의 SHA-256
   - 같은 hash면 INSERT 안 함

2. UPSERT 패턴
   INSERT ... ON CONFLICT (source_table, source_id, chunk_index)
   DO UPDATE SET content = EXCLUDED.content, ...

3. 결과적으로:
   - 같은 이벤트 N번 처리해도 최종 상태 동일
```

---

## 7. DDL 이벤트 하이브리드 처리

### 핵심 원칙

```
위험도에 따라 자동 / 반자동 / 수동을 다르게 적용:

LOW    → 자동 처리 (즉시 적용 또는 무시)
MEDIUM → 반자동 (알람 후 7일 무응답 시 자동 적용)
HIGH   → 수동만 (사람이 명시적으로 적용해야 함)

→ 안전한 변경은 빠르게 흐르고,
   위험한 변경은 반드시 사람이 검토
```

### 위험도 분류 (3단계)

```
🟢 LOW — 자동 처리 (즉시)
- CREATE TABLE (RAG 미등록 → 무시, 등록 대기열에만 기록)
- CREATE INDEX (RAG 영향 없음 → 로그만)
- ADD COLUMN (NULLABLE) (RAG 사용 컬럼 아니면 무시)
- COMMENT 변경
- ANALYZE / OPTIMIZE TABLE

→ ddl_events에 기록 + Info 알람 (#alerts-info)
→ action_taken = 'auto-applied' 또는 'ignored'

🟡 MEDIUM — 반자동 (7일 자동 fallback)
- ADD COLUMN (NOT NULL, default 있음)
- ALTER COLUMN (타입 호환: INT → BIGINT 등)
- ADD INDEX on RAG 컬럼

→ ddl_events에 기록 + Warning 알람 (#alerts-warning)
→ 관리자 7일 내 결정 시: 결정대로 적용
→ 7일 무응답 시: 안전한 기본 동작 자동 적용
   - ADD COLUMN: rag_table_config에 자동 포함 (선택)
   - ALTER COLUMN: 스키마 캐시 무효화만

🔴 HIGH — 수동만 (자동 적용 금지)
- DROP COLUMN (RAG 사용 컬럼 여부 무관)
- DROP TABLE
- RENAME TABLE / COLUMN (binlog 추적 끊김)
- ALTER COLUMN (타입 비호환: VARCHAR → INT)
- TRUNCATE TABLE (대량 삭제)

→ ddl_events에 기록 + Critical 알람 (#alerts-critical)
→ 관리자가 명시적으로 적용 명령 필요
→ 7일 후에도 자동 적용 안 함 (영구 대기)
```

### 처리 흐름

```
[1] binlog QUERY 이벤트 감지
        ↓
[2] DDL 여부 + 위험도 판별
    CREATE/ALTER/DROP/RENAME/TRUNCATE
    + 영향받는 테이블이 rag_table_config에 등록됐는지
    + 영향받는 컬럼이 content_columns에 포함됐는지
        ↓
[3] ddl_events 테이블에 기록
    {
      sql_query: "ALTER TABLE products ADD COLUMN ...",
      table_name: "products",
      event_type: "ALTER",
      risk_level: "MEDIUM",
      auto_apply_at: "2026-05-19 02:00",  ← 7일 후
      processed_at: null
    }
        ↓
[4] 위험도별 분기
    ├── LOW: 즉시 자동 적용 → processed_at 즉시 설정
    │        Discord Info 알람
    │
    ├── MEDIUM: 알람 + 7일 대기
    │           Discord Warning 알람
    │           관리자 7일 내 결정
    │           무응답 시 → 자동 fallback 적용
    │
    └── HIGH: 알람만, 자동 적용 안 함
              Discord Critical 알람
              관리자 명시 결정 필요
        ↓
[5] 데이터 이벤트 처리는 그대로 계속
    (DDL 이벤트가 데이터 흐름 막지 않음)
        ↓
[6] (MEDIUM/HIGH인 경우) 관리자 조치
    - 무시 / 설정 업데이트 / 재동기화 중 선택
    - processed_at, processed_by, action_taken 기록
        ↓
[7] (MEDIUM만) 7일 cron이 무응답 항목 자동 처리
    - 안전한 기본 동작 적용
    - action_taken = 'auto-applied-after-timeout'
```

### "자동 적용"의 정확한 정의

```
[중요 — 오해 방지]
"자동 적용" 은 "RAG 시스템이 알아서 영리하게 대응" 이 아니라
"우리 시스템에 영향 없음을 확인하고 ddl_events 에 기록만" 이라는 뜻.

LOW 자동:
- ddl_events 에 기록 + action_taken = 'auto-applied' 또는 'ignored'
- rag_table_config / document_chunks 데이터 변경 없음
- Info 알람으로 운영자에게는 알림 (사후 검토 가능)

MEDIUM 7일 자동 fallback:
- 스키마 캐시 무효화 (Redis schema:* 키 삭제)
- rag_table_config 변경 없음 (content_columns 등 그대로)
- ddl_events 처리됨 표시 (큐 청소 효과)
- → 운영자가 잊어버린 ddl_events 가 영원히 쌓이는 것을 막는 청소 장치

HIGH:
- 영구 대기. 자동 적용 절대 금지.
- 데이터 동기화는 멈추지 않음 (BinaryLogClient 그대로 진행)
```

### 30분 주기 binlog 와 DDL 순서 보장 (옵션 B 정합)

```
[순서]
- binlog 는 GTID 순서로 도착 → 우리는 그 순서대로 처리
- 한 cron 실행 안에서: DML → DDL → DML 섞여 와도 GTID 순서 그대로 처리
- DDL 이벤트는 ddl_events 에 기록만 하고 곧바로 다음 이벤트로 진행
- DDL이 DML 흐름을 막거나 lock 잡지 않음 (Spring 트랜잭션 범위는 DML 1건 단위)

[시간 lag 고려]
- DDL 발생 시각 vs ddl_events.created_at: 최대 30분 lag (cron 주기)
- MEDIUM 의 auto_apply_at = created_at + 7일
  → 실제 DDL 발생부터는 최대 7일 + 30분
  → 7일 timeout 정책에 누적 영향 없음 (무시 가능 수준)

[운영자가 알아야 할 점]
- DDL 알람이 30분 늦게 올 수 있음
- 긴급 변경(HIGH 위험도) 인 경우 고객사가 사전 협의해야 함
  (binlog 도착 전에 우리 시스템이 알 방법 없음)
```

### 7일 자동 fallback cron

```sql
-- 매일 새벽 4시 실행
UPDATE ddl_events
SET 
    processed_at = NOW(),
    processed_by = 'system-auto-timeout',
    action_taken = 'auto-applied-after-timeout',
    notes = '7일간 관리자 무응답으로 시스템 자동 처리'
WHERE risk_level = 'MEDIUM'
  AND processed_at IS NULL
  AND auto_apply_at < NOW();

-- 동시에 안전한 동작 수행:
-- ADD COLUMN → 스키마 캐시 무효화
-- ALTER COLUMN 호환 → 스키마 캐시 무효화
-- (자동으로 rag_table_config 변경은 안 함, 캐시만 갱신)
```

### 핵심: 자동 적용해도 안전한 이유

```
[자동 적용 = "RAG 시스템이 알아서 대응"이 아니라
            "RAG 시스템에 영향 없음을 확인"]

LOW 자동 처리:
- CREATE TABLE → 미등록 테이블이므로 RAG 무관
- CREATE INDEX → RAG는 인덱스 직접 사용 안 함
- ADD COLUMN NULLABLE → RAG가 컬럼 매핑에서 사용 안 하면 무시

MEDIUM 자동 fallback:
- ADD COLUMN NOT NULL with default → 새 컬럼 무시 (사용 안 함)
- ALTER COLUMN 호환 → 스키마 캐시만 갱신, 동기화 계속

HIGH 수동:
- DROP COLUMN: 사람이 영향 분석 필요
- 자동 적용하면 RAG 응답 품질 갑자기 저하 가능
```

### 관리자 조치 옵션

```
[옵션 A] 무시 (Dismiss)
- "이 DDL은 RAG에 영향 없음"
- ddl_events.action_taken = 'ignored'
- 추가 작업 없음

[옵션 B] 설정 업데이트 (Update Config)
- rag_table_config.content_columns 변경
- ddl_events.action_taken = 'config-updated'
- 향후 이벤트부터 새 설정 적용

[옵션 C] 강제 재동기화 (Resync)
- 해당 테이블의 모든 청크 삭제
- 풀 스냅샷 다시 실행
- ddl_events.action_taken = 'resynced'
```

---

## 8. 실패 처리 및 재시도

### 실패 케이스

```
1. Ollama 임베딩 실패
   - 네트워크 끊김
   - GPU OOM
   - Spot 인스턴스 회수

2. pgvector 저장 실패
   - RDS 일시 다운
   - 디스크 풀

3. 청킹 실패
   - 깨진 텍스트 (인코딩)
   - 너무 큰 텍스트

4. binlog 읽기 실패
   - 회사 MySQL 다운
   - binlog 파일 회전 중
   - 네트워크 끊김
```

### 재시도 전략 (Spring Retry)

```java
@Service
public class ChunkProcessor {
    
    @Retryable(
        value = {EmbeddingException.class, DataAccessException.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 1000, multiplier = 5, maxDelay = 30000)
    )
    public void processChunk(Chunk chunk) {
        float[] embedding = ollamaClient.embed(chunk.getContent());
        chunkRepository.save(chunk.withEmbedding(embedding));
    }
    
    @Recover
    public void recover(EmbeddingException ex, Chunk chunk) {
        // 3회 실패 후 호출됨
        binlogEventRepository.markFailed(chunk, ex.getMessage());
        discordNotifier.alert("Chunk processing failed: " + chunk.getId());
    }
}
```

### binlog 위치 정책 — 실패해도 전진

```
[정책]
실패한 이벤트가 있어도 binlog_position을 전진시킴.
이유: 같은 위치에서 무한 반복 방지.

[실패 이벤트 추적]
binlog_events 테이블에 기록 (error_message 포함)

[복구]
관리자가 다음 중 선택:
1. 무시 (트랜잭션 로그 등 비중요)
2. 수동 재처리:
   POST /api/v1/admin/sync/replay
   body: { "binlog_event_id": 12345 }
```

### binlog_events 테이블

```sql
CREATE TABLE binlog_events (
    id              BIGSERIAL PRIMARY KEY,
    binlog_file     VARCHAR(200),
    binlog_position BIGINT,
    event_type      VARCHAR(50),
    table_name      VARCHAR(100),
    source_id       VARCHAR(200),
    row_count       INT,
    processed       BOOLEAN DEFAULT false,
    attempt         INT DEFAULT 0,
    error_message   TEXT,
    created_at      TIMESTAMP DEFAULT NOW(),
    processed_at    TIMESTAMP
);

CREATE INDEX idx_binlog_events_unprocessed
    ON binlog_events (processed, created_at) WHERE NOT processed;
```

---

## 9. 초기 동기화 (Initial Sync)

### 신규 고객 온보딩 시점

```
신규 고객사 인프라 배포 완료
        ↓
초기 동기화 실행 (1회성)
        ↓
이후 30분 주기 binlog 동기화 (cron `0 */30 * * * *`)
```

### 초기 동기화 흐름

```
[1] 현재 GTID 위치 기록 (스냅샷 시작 직전)
    SELECT @@global.gtid_executed
    → "uuid-1:1-12345"
        ↓
[2] rag_table_config에 등록된 테이블들 풀 스냅샷
    각 테이블마다:
    SELECT * FROM products
    SELECT * FROM contracts
    SELECT * FROM customers
        ↓
[3] 병렬 처리 (8 스레드)
    각 행에 대해:
    ├── PII 마스킹
    ├── 청킹
    ├── Ollama 임베딩
    └── pgvector 저장
        ↓
[4] 모든 행 처리 완료 후
    binlog_position.gtid_set 에 [1]에서 기록한 GTID 저장
        ↓
[5] 이후 30분마다 cron 이 이 GTID 이후 binlog 만 읽기 시작
```

### 소요 시간 추정

```
[가정]
- 10만 행 (계약 + 상품 + 고객)
- 청킹 후 약 100만 청크
- Ollama 임베딩: 청크당 50ms

[단일 스레드]
100만 × 50ms = 50,000초 = 약 14시간

[8 스레드 병렬]
약 1.75시간

[16 스레드 병렬]
약 1시간 (Ollama 부하 한계 가까움)
```

### 초기 동기화 API

```
POST /api/v1/admin/sync/initial
body: {
  "tables": ["products", "contracts", "customers"]  // null이면 전체
}

→ 비동기 작업 시작
→ sync_jobs 테이블에 진행 상황 저장
→ Discord에 진행률 알람
```

---

## 10. 동기화 스케줄 및 수동 API

### 자동 스케줄

```java
@Scheduled(cron = "0 */30 * * * *")  // 매 30분 (00:00, 00:30, 01:00, ...)
@SchedulerLock(name = "binlogSync", lockAtMostFor = "20m")
public void runBinlogSync() {
    binlogSyncService.syncSinceLastGtid();
}
```

> 옵션 B — 30분 주기. 24시간 1회 배치 대비 데이터 신선도 ≤ 30분 보장.
> 처리량은 분산되므로 새벽 2시 폭증 위험이 사라진다.

### 관리자 API

```
[전체 즉시 동기화]
POST   /api/v1/admin/sync/trigger
body:  {}
response: { "job_id": "uuid", "status": "running" }

[진행 상황 조회]
GET    /api/v1/admin/sync/status
       /api/v1/admin/sync/status/{job_id}
response: {
  "job_id": "uuid",
  "started_at": "2026-05-12T02:00:00",
  "status": "running",
  "records_total": 10000,
  "records_success": 8500,
  "records_failed": 12,
  "last_binlog_position": "mysql-bin.000123:4096"
}

[초기 동기화]
POST   /api/v1/admin/sync/initial
body:  { "tables": ["products"] }  // 선택적

[실패 이벤트 재처리]
POST   /api/v1/admin/sync/replay
body:  { "binlog_event_id": 12345 }

[RAG 테이블 강제 재동기화]
POST   /api/v1/admin/rag-tables/{id}/resync
→ 해당 테이블의 모든 청크 삭제 후 풀 재생성
```

---

## 11. DB 스키마 (동기화 관련)

### PostgreSQL 테이블 전체 목록

```sql
-- 1. RAG 대상 테이블 동적 관리
rag_table_config

-- 2. 벡터 청크 (메인)
document_chunks

-- 3. binlog 위치 추적 (싱글톤)
binlog_position

-- 4. binlog 이벤트 추적 (디버깅/재처리)
binlog_events

-- 5. DDL 이벤트 (하이브리드 처리 대기열 — LOW/MEDIUM/HIGH)
ddl_events

-- 6. 동기화 작업 단위
sync_jobs

-- 7. 동기화 상세 로그
sync_log

-- 8. 감사 로그
audit_logs

-- 9. API Key (Open WebUI 인증)
api_keys
```

### 신규/수정 테이블 정의

```sql
-- RAG 대상 테이블 (동적 관리)
CREATE TABLE rag_table_config (
    id                  SERIAL PRIMARY KEY,
    source_table        VARCHAR(100) NOT NULL UNIQUE,
    source_type         VARCHAR(50) NOT NULL,
    chunking_strategy   VARCHAR(50) NOT NULL,
    chunk_size          INT NOT NULL DEFAULT 500,
    chunk_overlap       INT NOT NULL DEFAULT 50,
    title_column        VARCHAR(100),
    content_columns     TEXT[] NOT NULL,
    metadata_columns    TEXT[],
    pk_column           VARCHAR(100) NOT NULL,
    pii_masking_level   VARCHAR(20) DEFAULT 'standard',

    -- 데이터 분류 + 접근 그룹 (옵션 D)
    data_sensitivity    VARCHAR(20) NOT NULL DEFAULT 'internal'
        CHECK (data_sensitivity IN ('public', 'internal', 'restricted')),
    allowed_groups      TEXT[] NOT NULL DEFAULT ARRAY['all'],

    is_active           BOOLEAN DEFAULT true,
    created_at          TIMESTAMP DEFAULT NOW(),
    updated_at          TIMESTAMP DEFAULT NOW()
);

-- 벡터 청크 (document_chunks)
-- 04-rag-search-strategy.md / 05-prompt-design.md 에서 검색·인용 대상.
-- Phase 0: access_groups 항상 ['all'], Phase 1+ 활성화.
CREATE TABLE document_chunks (
    id                  BIGSERIAL PRIMARY KEY,
    source_table        VARCHAR(100) NOT NULL,
    source_id           VARCHAR(200) NOT NULL,
    source_type         VARCHAR(50)  NOT NULL,
    chunk_index         INT          NOT NULL,
    content             TEXT         NOT NULL,
    content_hash        VARCHAR(64)  NOT NULL,           -- SHA-256, 멱등성용
    token_count         INT          NOT NULL,           -- Ollama /api/tokenize 결과 (옵션 A)
                                                         -- 동기화 시 1회 계산, Context Builder 가 이 값 사용
    embedding           VECTOR(768)  NOT NULL,           -- pgvector
    embedding_model     VARCHAR(100) NOT NULL,           -- 마이그레이션용
    tokenizer_model     VARCHAR(100) NOT NULL,           -- token_count 계산에 쓴 모델 (예: 'qwen2.5:14b-instruct-q4_K_M')
    metadata            JSONB,

    -- 접근 그룹: rag_table_config.allowed_groups에서 상속
    -- Phase 0: 항상 ['all']
    -- Phase 1+: 행/문서 단위로 다른 값 가능
    access_groups       TEXT[]       NOT NULL DEFAULT ARRAY['all'],

    created_at          TIMESTAMP DEFAULT NOW(),
    updated_at          TIMESTAMP DEFAULT NOW(),

    UNIQUE (source_table, source_id, chunk_index, embedding_model)
);

CREATE INDEX idx_chunks_hnsw_cosine
    ON document_chunks USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_chunks_access_groups
    ON document_chunks USING GIN (access_groups);
CREATE INDEX idx_chunks_source
    ON document_chunks (source_table, source_id);

-- binlog 위치 (싱글톤) — Phase 0 옵션 B: GTID 전용
CREATE TABLE binlog_position (
    id              SERIAL PRIMARY KEY,
    gtid_set        TEXT NOT NULL,          -- 'uuid-1:1-12345,uuid-2:1-67' 형식
    last_event_at   TIMESTAMP,              -- 마지막 처리한 binlog 이벤트의 원본 시각 (lag 계산용)
    updated_at      TIMESTAMP DEFAULT NOW(),

    -- 이전 설계 잔재 (deprecated, 사용 안 함):
    -- binlog_file, binlog_position 컬럼은 의도적으로 제외.
    -- master 페일오버 시 fragile 하므로 GTID 만 사용.

    CHECK (id = 1)
);

INSERT INTO binlog_position (id, gtid_set, last_event_at)
VALUES (1, '', NULL);

-- binlog 이벤트 (디버깅 + 재처리)
CREATE TABLE binlog_events (
    id              BIGSERIAL PRIMARY KEY,
    binlog_file     VARCHAR(200),
    binlog_position BIGINT,
    event_type      VARCHAR(50),
    table_name      VARCHAR(100),
    source_id       VARCHAR(200),
    row_count       INT,
    processed       BOOLEAN DEFAULT false,
    attempt         INT DEFAULT 0,
    error_message   TEXT,
    created_at      TIMESTAMP DEFAULT NOW(),
    processed_at    TIMESTAMP
);

CREATE INDEX idx_binlog_events_unprocessed
    ON binlog_events (processed, created_at) WHERE NOT processed;

-- DDL 이벤트
CREATE TABLE ddl_events (
    id              BIGSERIAL PRIMARY KEY,
    binlog_file     VARCHAR(200),
    binlog_position BIGINT,
    sql_query       TEXT NOT NULL,
    table_name      VARCHAR(100),
    event_type      VARCHAR(50),                -- 'CREATE', 'ALTER', 'DROP', 'RENAME'
    risk_level      VARCHAR(20),                -- 'LOW', 'MEDIUM', 'HIGH'
    
    -- 하이브리드 처리 정책
    auto_apply_at   TIMESTAMP,                  -- MEDIUM: 7일 후 자동 적용 시각
                                                -- LOW: 즉시 (= created_at)
                                                -- HIGH: NULL (자동 적용 안 함)
    
    processed_at    TIMESTAMP,
    processed_by    VARCHAR(200),               -- 'system-auto', 'system-auto-timeout', 사용자 email
    action_taken    VARCHAR(50),                -- 'auto-applied', 'auto-applied-after-timeout',
                                                -- 'ignored', 'config-updated', 'resynced'
    notes           TEXT,
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_ddl_events_pending
    ON ddl_events (processed_at) WHERE processed_at IS NULL;

CREATE INDEX idx_ddl_events_auto_apply
    ON ddl_events (auto_apply_at) 
    WHERE processed_at IS NULL AND auto_apply_at IS NOT NULL;

-- 동기화 작업 (한 번의 실행 단위)
CREATE TABLE sync_jobs (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trigger_type        VARCHAR(20) NOT NULL,    -- 'scheduled', 'manual', 'initial'
    triggered_by        VARCHAR(200),
    started_at          TIMESTAMP DEFAULT NOW(),
    completed_at        TIMESTAMP,
    status              VARCHAR(20) NOT NULL,    -- 'running', 'success', 'failed', 'partial'
    records_total       INT DEFAULT 0,
    records_success     INT DEFAULT 0,
    records_failed      INT DEFAULT 0,
    error_message       TEXT,
    start_binlog_pos    VARCHAR(200),
    end_binlog_pos      VARCHAR(200)
);

-- 동기화 상세 로그
CREATE TABLE sync_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id          UUID REFERENCES sync_jobs(id),
    source_table    VARCHAR(100) NOT NULL,
    source_id       VARCHAR(200),
    operation       VARCHAR(20) NOT NULL,       -- 'insert', 'update', 'delete'
    status          VARCHAR(20) NOT NULL,
    attempt         INT DEFAULT 0,
    error_message   TEXT,
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_sync_log_job ON sync_log (job_id);
```

---

## 12. 관리자 워크플로우

### 시나리오 1: 신규 테이블을 RAG에 추가

```
[목표]
"reviews" 테이블을 RAG 대상에 추가하고 싶음

[절차]
1. POST /api/v1/admin/rag-tables
   body: {
     "source_table": "reviews",
     "source_type": "review",
     "chunking_strategy": "per-record",
     "chunk_size": 200,
     "title_column": "title",
     "content_columns": ["content"],
     "pk_column": "id"
   }

2. (선택) 풀 스냅샷 실행
   POST /api/v1/admin/rag-tables/{id}/resync
   → 기존 reviews 행을 모두 임베딩

3. 이후 binlog 이벤트는 자동으로 reviews도 처리
```

### 시나리오 2: DDL 이벤트 검토

```
[Discord 알람 수신]
🟡 ALTER TABLE products ADD COLUMN warranty_period

[관리자 조치]
1. GET /api/v1/admin/ddl-events?status=pending
   → 처리 대기 목록 확인

2. 결정:
   - 무시: POST /api/v1/admin/ddl-events/{id}/dismiss
   - 포함: rag_table_config.content_columns 업데이트
          + (선택) resync

3. ddl_events.action_taken 자동 업데이트
```

### 시나리오 3: 실패 이벤트 재처리

```
[Discord 알람 수신]
🚨 Embedding failed 3 times: chunk_id=xxx

[관리자 조치]
1. GET /api/v1/admin/sync/failed-events
   → 실패한 binlog_events 목록

2. 원인 파악:
   - Ollama 일시 다운? → 자동 복구 후 재시도
   - 깨진 텍스트? → 데이터 수정 후 재시도

3. POST /api/v1/admin/sync/replay
   body: { "binlog_event_id": 12345 }
```

### 시나리오 4: RAG 품질 저하 → 특정 테이블 재동기화

```
[증상]
사용자: "최근 상품 정보가 정확하지 않은데?"

[관리자 조치]
1. 진단:
   GET /api/v1/admin/sync/status
   → 최근 동기화 성공 여부 확인

2. 강제 재동기화:
   POST /api/v1/admin/rag-tables/products/resync
   → products 테이블 모든 청크 삭제 + 풀 재생성

3. 약 30분 ~ 2시간 후 완료 (데이터량에 따라)
```

---

## 다음 단계

이 파이프라인 설계 기반으로:
- **D. RAG 검색 전략** (다음 진행)
- **E. 프롬프트 설계**
- **F. 에러 처리/장애 대응**
- **C. 인증/인가**
