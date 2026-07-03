package com.ragvault.core.service;

import java.awt.image.BufferedImage;

/**
 * Tesseract OCR 서비스 인터페이스.
 * 구현체를 분리해 테스트에서 Mock 대체 가능.
 */
public interface TesseractOcrService {
    /**
     * @param imageBytes 이미지 바이트
     * @return OCR 텍스트. 신뢰도 낮으면 "[이미지: OCR 신뢰도 낮음, 해석 불가]"
     */
    String ocr(byte[] imageBytes);

    /**
     * 이미 디코딩된 이미지에 대해 OCR을 수행한다 (PDF 페이지 렌더링 결과 등,
     * PNG 인코딩/디코딩 왕복 없이 바로 처리하기 위한 오버로드).
     *
     * @param image 이미지
     * @return OCR 텍스트. 신뢰도 낮으면 "[이미지: OCR 신뢰도 낮음, 해석 불가]"
     */
    String ocr(BufferedImage image);
}
