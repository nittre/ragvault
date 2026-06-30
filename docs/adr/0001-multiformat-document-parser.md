# ADR-0001: 멀티포맷 문서 파서 전략 — Apache Tika + opendataloader-pdf

- **상태**: Accepted
- **결정일**: 2026-06-30
- **결정자**: 개발팀
- **관련 ADR**: ADR-0002
- **영향 받는 코드**: `core/src/.../service/parser/`, `app-widget/src/.../service/KnowledgeIngestionService.java`

## 컨텍스트 (Why)

위젯 RAG가 마크다운 파일만 지원했다. 고객이 업무에 사용하는 지식문서는
docx·xlsx·pptx·pdf 형식이 대부분이므로, 이를 직접 업로드해 벡터화할 수 있어야 한다.

- 기존: `knowledge/` 디렉토리의 `.md` 파일만 인입
- 요구: Office 문서·PDF의 텍스트·표·이미지를 모두 벡터화해 RAG 검색에 활용

파서 선택 기준:
1. Apache 2.0 라이선스
2. Spring Boot 3.x 생태계와 충돌 없음
3. 표(Table) 구조 보존 — 표를 평탄 텍스트로 추출 가능
4. 이미지 추출 — 임베디드 이미지 바이트를 꺼낼 수 있어야 함

## 결정 (What)

```
Office(docx/xlsx/pptx/doc/ppt/xls/csv): Apache Tika 2.9.2 AutoDetectParser 사용.
PDF: opendataloader-pdf-core 1.11.0 사용 — Markdown + 이미지 파일 출력.
마크다운/TXT: 파싱 없이 패스스루.

모든 파서는 ParsedDocument(markdown, images, metadata)로 결과를 정규화한다.
정규화된 마크다운이 기존 청킹·임베딩 파이프라인에 그대로 합류한다.
```

## 결과 (Consequences)

### 장점
- **기존 파이프라인 무변경**: 파서가 마크다운으로 정규화하므로 청킹·임베딩 로직 변경 불필요.
- **확장 용이**: 새 포맷은 `DocumentParser` 인터페이스 구현체만 추가하면 됨.
- **재사용성**: 파서 계층이 `core`에 있어 `app-internal`도 향후 재사용 가능.

### 단점·트레이드오프
- **verapdf 의존성**: opendataloader-pdf-core가 vera-dev Artifactory(비 Maven Central)에서만 받을 수 있는 verapdf를 요구한다. `core`를 참조하는 모든 앱(`app-widget`, `app-internal`)의 build.gradle에 저장소를 추가해야 한다.
- **JAR 중복**: Tika가 JAXB 등을 끌어와 `bootJar` 패키징 시 중복 발생 → `duplicatesStrategy = EXCLUDE`로 처리.
- **PDF 이미지 품질**: opendataloader-pdf의 이미지 추출 품질은 PDF 생성 방식에 따라 달라진다.

### 후속 작업
- PDF 렌더링 품질 검증 (표 구조 복잡한 문서 테스트)
- app-internal에서 동일 파서 재사용 여부 검토

## 대안 (검토했으나 채택 안 한 옵션)

### 옵션 A — Apache PDFBox 직접 사용
- Tika 내부적으로 PDFBox를 사용하므로 Tika로 통합 가능. PDFBox 단독 사용은 표/이미지 추출 코드를 직접 구현해야 해 복잡도 증가.
- **채택 안 한 이유**: opendataloader-pdf가 마크다운+표 구조 보존을 기본 제공.

### 옵션 B — Apache POI 직접 사용 (Tika 없이)
- Office 문서 파싱에 충분하지만 PDF를 별도로 처리해야 하고 MIME 감지 로직을 직접 구현해야 함.
- **채택 안 한 이유**: Tika의 `AutoDetectParser`가 POI를 내부적으로 사용하며 MIME 감지까지 처리.

### 옵션 C — LangChain4j DocumentLoader
- Spring AI 생태계 연동이 자연스럽지만 1.0.0 GA 기준 포맷 지원이 제한적.
- **채택 안 한 이유**: 지원 포맷·이미지 추출 기능이 Tika+opendataloader 조합에 미치지 못함.

## 참고

- [Apache Tika](https://tika.apache.org/)
- [opendataloader-pdf](https://github.com/opendataloader-project/opendataloader-pdf)
- [vera-dev Artifactory](https://artifactory.openpreservation.org/artifactory/vera-dev)
