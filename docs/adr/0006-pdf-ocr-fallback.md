# ADR-0006: PDF 텍스트 추출 실패 시 Tesseract OCR 폴백

- **상태**: Accepted
- **결정일**: 2026-07-03
- **결정자**: 개발팀
- **관련 ADR**: ADR-0001, ADR-0002, ADR-0004
- **영향 받는 코드**: `core/src/.../service/parser/OpenDataLoaderPdfParser.java`, `core/src/.../service/parser/PdfOcrFallbackService.java`(신규), `core/src/.../service/TesseractOcrService.java`/`TesseractOcrServiceImpl.java`, `app-internal/Dockerfile`, `app-widget/Dockerfile`

## 컨텍스트 (Why)

일부 PDF는 본문 텍스트가 실제 폰트(글리프)가 아니라 벡터 윤곽선(outline)으로만
그려져 있다. 실사례로 확인된 문서는 4페이지 전체에 PDF 폰트 리소스가 0개였고,
순수 PDFBox `PDFTextStripper`로 4자, opendataloader-pdf-core(ADR-0001)로도 70자
(이미지 마크다운 참조뿐)밖에 추출되지 않았다. opendataloader-pdf-core의 필터/reading
order 등 Config 옵션 11가지 조합을 전부 시도해도 결과가 전혀 바뀌지 않아, 이는
파서 설정으로 해결 가능한 문제가 아니라 **폰트 기반 텍스트 추출의 근본적 한계**로
확인됐다. 이 상태로 업로드하면 사실상 빈 텍스트가 벡터화되어, 문서 내용에 대한
어떤 질문에도 "관련된 정보를 자료에서 찾을 수 없습니다"만 반환된다.

## 결정 (What)

```
1. OpenDataLoaderPdfParser.parse()에서 파싱 직후 텍스트 추출 실패 여부를 판정한다.
   - 이미지 마크다운(![...](...)) 제거 후 순수 텍스트 길이 < 20자, 또는
     (페이지당 평균 문자수 < 30 AND 전체 < 500자)면 실패로 판정.
2. 실패 판정 시 PdfOcrFallbackService(신규, core) 호출:
   - PDFRenderer(PDFBox 3.x, opendataloader-pdf-core의 전이 의존성으로 이미 포함)로
     페이지를 200 DPI 이미지로 렌더링
   - Tesseract(kor+eng, OEM=1 LSTM 전용)로 각 페이지 OCR → 마크다운으로 합침
   - 페이지 상한 20장, 전체 타임아웃 180초로 대용량 PDF 지연을 제한
   - 실패해도 원본(빈약한) markdown으로 폴백 — 업로드 자체는 막지 않음
3. TesseractOcrService/Impl을 app-internal 전용(미사용 코드였음)에서 core로 이동해
   app-widget/app-internal 양쪽이 공유. BufferedImage 오버로드 추가.
4. 양쪽 Dockerfile 런타임 스테이지(eclipse-temurin:21-jre-alpine)에
   `apk add tesseract-ocr tesseract-ocr-data-eng tesseract-ocr-data-kor` 추가
   (기존엔 설치 자체가 없어 이 기능이 배포 환경에서 아예 동작 불가능했음).
```

### Alpine 컨테이너에서 발견된 추가 이슈 — OEM 강제

로컬(macOS, Homebrew tesseract 5.5.2)에서는 OCR 결과가 정상이었으나, 실제
Docker(Alpine, tesseract 5.5.1) 컨테이너에서 재현했을 때 한글이 음절마다 공백으로
쪼개져 인식됐다 (`관리시스템` → `관 리 시 스 템`). 컨테이너에 렌더링된 페이지
이미지를 넣고 `tesseract --oem` 값을 바꿔가며 CLI로 직접 비교한 결과, 기본값
(OEM=3, 자동 선택)이 legacy 엔진으로 폴백되면서 발생하는 문제였고, `--oem 1`
(LSTM 전용)로 명시하면 완전히 정상화됨을 확인했다. `TesseractOcrServiceImpl`에
`tess.setOcrEngineMode(1)`을 명시적으로 고정했다.

## 결과 (Consequences)

### 장점
- 벡터 윤곽선 텍스트 PDF도 검색 가능한 콘텐츠로 복구된다 (실사례: 4페이지 문서
  70자 → OCR 후 5876자, 9개 청크로 정상 임베딩·검색 확인).
- OCR 실패/타임아웃 시에도 업로드 자체가 죽지 않고 원본 결과로 관대하게 폴백한다
  (기존의 "실패 시 명확히 실패 처리" 방침과 공존 — 전체 파이프라인이 완전히
  빈손일 때만 예외를 던짐).
- `core`로 이동/공유되어 app-widget도 동일 기능을 별도 구현 없이 획득.

### 단점·트레이드오프
- OCR 결과는 원문 대비 품질이 낮다 (오탈자, 일부 기호 오인식). 검색·요약 품질은
  원본 텍스트 추출보다 떨어진다.
- 페이지당 처리 시간이 늘어난다 (실사례 4페이지 기준 약 9~18초). 업로드 API가
  동기 처리라 대형 문서에서 체감 지연이 커질 수 있음 — 페이지 상한/타임아웃으로
  제한했으나 근본적으로는 비동기화 검토 여지가 있음.
- Docker 이미지 용량 증가 (tesseract-ocr 엔진 + kor/eng traineddata, 두 서비스
  이미지에 중복 설치).
- **로컬 개발환경(Homebrew)과 배포환경(Alpine apk)의 tesseract 동작이 다를 수
  있다** — 이번 OEM 이슈가 대표 사례. 로컬에서 정상이어도 컨테이너 재검증이
  필요하다.

### 후속 작업
- 실 운영 로그(`stripped/pages/avg/failed`) 기반으로 판정 임계값 튜닝.
- OCR 인식률 개선 여지 검토 (DPI 상향, 전처리 이진화 등) — 현재 200 DPI는
  속도-정확도 균형점으로 잠정 채택.
- 업로드 API 비동기화 여부 검토 (대형/다페이지 PDF 대응).

## 대안 (검토했으나 채택 안 한 옵션)

### 옵션 A — 다른 PDF 파서로 교체
Java 생태계에서 opendataloader-pdf-core/PDFBox 계열을 대체할 만한 동급 옵션이
없고, 근본 원인이 "폰트 없이 그려진 텍스트"라는 PDF 구조 자체의 한계라 파서를
바꿔도 동일하게 실패한다 (Tika 등 다른 PDFBox 기반 파서도 동일 한계 공유).
**채택 안 한 이유**: 근본 원인 해결이 안 됨.

### 옵션 B — 실패 시 사용자에게 재변환 안내만 하고 서버는 실패 처리
**채택 안 한 이유**: 사용자가 원본 마크다운/워드 파일을 갖고 있지 않은 경우가
많고, PDF만 가진 사용자에게 실질적 해결책이 되지 못함.

### 옵션 C — Tesseract OEM을 legacy(0)로 고정
**채택 안 한 이유**: 정확히 문제를 일으키는 방향이라 기각. LSTM 엔진(OEM=1)이
CJK 인식 품질이 훨씬 높다는 것을 CLI 비교로 직접 확인했다.

## 참고

- [Tesseract OCR Engine Modes](https://tesseract-ocr.github.io/tessdoc/ImproveQuality.html)
- ADR-0001 (opendataloader-pdf-core 도입 배경)
- ADR-0002 (이미지 캡셔닝 — 비전 모델 캡션과 별개 경로임에 유의)
