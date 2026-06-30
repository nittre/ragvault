USE board_db;

-- ─────────────────────────────────────────────
-- 사용자 (password: bcrypt of "password123")
-- ─────────────────────────────────────────────
INSERT INTO users (username, email, password, nickname, role) VALUES
  ('admin',    'admin@example.com',   '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '관리자',     'ADMIN'),
  ('kim_dev',  'kim@example.com',     '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '김개발',     'MEMBER'),
  ('lee_ux',   'lee@example.com',     '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '이유엑스',   'MEMBER'),
  ('park_ops', 'park@example.com',    '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '박운영',     'MEMBER'),
  ('choi_qa',  'choi@example.com',    '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '최큐에이',   'MEMBER'),
  ('jung_be',  'jung@example.com',    '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '정백엔드',   'MEMBER'),
  ('yoon_fe',  'yoon@example.com',    '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '윤프론트',   'MEMBER'),
  ('han_data', 'han@example.com',     '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '한데이터',   'MEMBER');

-- ─────────────────────────────────────────────
-- 메뉴 (상위 카테고리)
-- ─────────────────────────────────────────────
INSERT INTO menus (id, parent_id, name, slug, description, sort_order) VALUES
  (1, NULL, '공지사항',   'notice',      '전사 공지 및 중요 안내',           1),
  (2, NULL, '자유게시판', 'free',        '자유롭게 소통하는 공간',           2),
  (3, NULL, '기술게시판', 'tech',        '기술 공유 및 스터디 자료',         3),
  (4, NULL, 'QnA',        'qna',         '질문과 답변',                      4);

-- 하위 메뉴
INSERT INTO menus (id, parent_id, name, slug, description, sort_order) VALUES
  (5, 3, '백엔드',    'tech-backend',  'Java / Spring / DB 관련 기술 공유', 1),
  (6, 3, '프론트엔드','tech-frontend', 'React / Vue / CSS 관련',            2),
  (7, 3, 'DevOps',    'tech-devops',   'Docker / K8s / CI-CD',              3);

-- ─────────────────────────────────────────────
-- 게시글
-- ─────────────────────────────────────────────
INSERT INTO posts (menu_id, author_id, title, content, view_count, is_pinned) VALUES
  -- 공지사항
  (1, 1, '[필독] 2026년 상반기 보안 정책 변경 안내',
   '안녕하세요. 보안팀입니다.\n\n2026년 7월부터 시행되는 보안 정책 주요 변경 사항을 공지드립니다.\n\n1. 비밀번호 90일 주기 변경 의무화\n2. 외부망 접속 시 MFA 필수 적용\n3. 개인정보 처리 방침 개정\n\n자세한 내용은 첨부 문서를 참고해 주세요.',
   312, 1),
  (1, 1, '[안내] 사내 Wiki 신규 도메인 이전 완료',
   '사내 Wiki 주소가 변경되었습니다.\n\n기존: http://wiki.internal\n변경: https://wiki.corp.example.com\n\n기존 주소는 2026년 8월까지 리다이렉트 됩니다.',
   189, 0),

  -- 자유게시판
  (2, 3, '점심 추천 맛집 공유해요!',
   '사무실 근처 맛집 추천 받습니다 :)\n\n저는 요즘 1층 건물 뒷골목 순대국 자주 가고 있어요. 국물이 진하고 가격도 착해요!',
   77, 0),
  (2, 4, '오늘 배포 성공 축하합니다 🎉',
   '어젯밤 야근하며 준비한 v2.3.0 배포가 무사히 완료됐습니다.\n\n팀원들 모두 고생하셨습니다. 오늘 저녁은 회식으로 달려봅시다!',
   143, 0),
  (2, 7, '재택근무 셋업 공유',
   '제 재택 셋업 공유합니다.\n- 모니터: LG 27인치 4K\n- 키보드: HHKB Professional\n- 마우스: Logitech MX Master 3\n- 의자: 허먼밀러 에어론\n\n제일 투자 잘한 건 역시 의자였어요.',
   256, 0),

  -- 백엔드 기술게시판
  (5, 2, 'Spring Boot 3.3 Virtual Thread 도입 후기',
   '안녕하세요, 김개발입니다.\n\n이번 스프린트에서 Spring Boot 3.3 + Virtual Thread(JDK 21)를 실제 서비스에 적용해봤습니다.\n\n## 결과 요약\n- 평균 응답시간: 320ms → 180ms (44% 개선)\n- 최대 동시 처리: 200 TPS → 480 TPS\n\n## 주의사항\n- ThreadLocal 사용 코드 점검 필수\n- synchronized 블록은 pinning 주의\n\n궁금한 점 댓글로 남겨주세요!',
   412, 0),
  (5, 6, 'pgvector HNSW 인덱스 튜닝 경험 공유',
   'RAG 서비스에서 pgvector HNSW 인덱스 파라미터 튜닝을 해봤습니다.\n\nm=16, ef_construction=64 → m=32, ef_construction=128 로 변경 후\nRecall@10 이 0.82 → 0.94 로 향상됐습니다.\n\n대신 인덱스 빌드 시간이 약 2.3배 증가했으니 트레이드오프 고려 필요합니다.',
   298, 0),

  -- 프론트엔드 기술게시판
  (6, 7, 'React 19 use() 훅 실전 적용기',
   'React 19에서 추가된 use() 훅을 프로젝트에 실제로 적용해봤습니다.\n\nPromise를 직접 컴포넌트에서 unwrap 할 수 있어서 Suspense 조합이 훨씬 자연스러워졌습니다.\n\n```tsx\nconst user = use(fetchUser(id));\n```\n\n서버 컴포넌트 없이도 데이터 로딩 흐름이 깔끔해졌어요.',
   187, 0),

  -- DevOps 기술게시판
  (7, 4, 'Docker BuildKit 캐시 레이어 최적화',
   'CI 빌드 시간이 8분 → 2분으로 줄었습니다.\n\n핵심은 COPY 순서 재배치와 --mount=type=cache 활용입니다.\n\n```dockerfile\nRUN --mount=type=cache,target=/root/.gradle \\\n    ./gradlew build -x test\n```\n\nJenkins 파이프라인에서도 동일하게 적용 가능합니다.',
   334, 0),

  -- QnA
  (4, 5, 'JPA N+1 문제 해결 방법 질문',
   '안녕하세요. 최근 JPA를 공부하고 있는데 N+1 문제로 고민 중입니다.\n\nFetch Join과 @BatchSize 중 어떤 걸 선택해야 할지 기준이 있을까요?\n실무에서는 어떻게 쓰시는지 경험 공유 부탁드립니다.',
   95, 0),
  (4, 8, 'binlog 이벤트 누락 디버깅 방법',
   'MySQL binlog 스트림을 구독하고 있는데 간헐적으로 이벤트가 누락되는 것 같습니다.\n\nGTID 기반으로 구현했는데 어디서 확인해야 할까요?',
   62, 0);

-- ─────────────────────────────────────────────
-- 댓글 & 대댓글
-- ─────────────────────────────────────────────
INSERT INTO comments (post_id, author_id, parent_id, content) VALUES
  -- Virtual Thread 게시글(6번) 댓글
  (6, 3, NULL, '실제 적용 후기 감사합니다! ThreadLocal 점검은 어떤 방식으로 하셨나요?'),
  (6, 5, NULL, '480 TPS라니 인상적이네요. 혹시 DB 커넥션 풀 설정도 바꾸셨나요?'),
  (6, 2, NULL, 'Kotlin 코루틴과 비교했을 때 체감 차이가 있을까요?'),

  -- Virtual Thread 게시글 대댓글
  (6, 2,    1, '@이유엑스 InheritableThreadLocal 위주로 검색해서 하나씩 제거했습니다. IDE 플러그인은 없어서 grep으로 찾았어요.'),
  (6, 2,    2, '@최큐에이 HikariCP는 기존 max=20에서 max=50으로 올렸습니다. Virtual Thread 개수가 많아지니 커넥션이 병목이 되더라고요.'),

  -- pgvector 게시글(7번) 댓글
  (7, 2, NULL, '저도 비슷한 튜닝을 했는데 ef_search 값도 중요했어요. 100 → 200으로 올리니 Recall 더 올라갔습니다.'),
  (7, 8, NULL, '인덱스 빌드 시간이 부담스럽다면 IVFFlat도 고려해볼 만합니다. 다만 Recall은 좀 낮아요.'),

  -- N+1 질문(10번) 댓글
  (10, 2, NULL, 'Fetch Join은 컬렉션 페이징과 함께 쓸 수 없어서 저는 주로 @BatchSize를 기본으로, 단건 조회에만 Fetch Join 씁니다.'),
  (10, 6, NULL, '실무에선 QueryDSL + fetchJoin() 조합을 많이 써요. 복잡한 조건이 있을 때 가독성이 좋더라고요.'),
  (10, 5,    8, '@김개발 아 그렇군요! BatchSize를 기본값으로 두는 게 안전하겠네요. 감사합니다.'),

  -- binlog 질문(11번) 댓글
  (11, 2, NULL, 'SHOW MASTER STATUS 로 현재 GTID_EXECUTED 확인하시고, 커넥터 로그에서 마지막 처리된 GTID와 비교해보세요.'),
  (11, 4, NULL, 'mysql-binlog-connector-java 쓰신다면 BinaryLogClient에 EventListener 달아서 수신 이벤트 카운트 로깅해보시면 빠르게 파악됩니다.'),

  -- 점심 맛집 게시글(3번) 댓글
  (3, 2, NULL, '저는 근처 돈까스집 추천합니다! 웨이팅 있지만 값어치 해요.'),
  (3, 6, NULL, '매일 편의점 도시락이라 맛집 정보 감사합니다 😂'),
  (3, 3, 13,   '@김개발 어디 돈까스집인가요? 저도 가보고 싶어요!');

-- ─────────────────────────────────────────────
-- 좋아요
-- ─────────────────────────────────────────────
INSERT INTO likes (user_id, target_type, target_id) VALUES
  -- 게시글 좋아요
  (2, 'POST', 6), (3, 'POST', 6), (4, 'POST', 6), (5, 'POST', 6), (7, 'POST', 6), (8, 'POST', 6),
  (2, 'POST', 7), (5, 'POST', 7), (6, 'POST', 7), (8, 'POST', 7),
  (1, 'POST', 9), (2, 'POST', 9), (3, 'POST', 9), (4, 'POST', 9), (5, 'POST', 9),
  (3, 'POST', 4), (5, 'POST', 4), (7, 'POST', 4),
  (2, 'POST', 5), (4, 'POST', 5), (6, 'POST', 5), (8, 'POST', 5),
  -- 댓글 좋아요
  (3, 'COMMENT', 4), (5, 'COMMENT', 4), (7, 'COMMENT', 4),
  (2, 'COMMENT', 5), (6, 'COMMENT', 5),
  (5, 'COMMENT', 8), (7, 'COMMENT', 8), (3, 'COMMENT', 8);
