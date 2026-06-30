package com.ragservice.rag.service;

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
}
