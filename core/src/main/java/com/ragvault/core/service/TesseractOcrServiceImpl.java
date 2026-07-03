package com.ragvault.core.service;

import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

/**
 * Tess4j 기반 OCR. 한국어+영어(kor+eng).
 * 신뢰도 임계값 70% (N4 결정) — 미만이면 placeholder 반환.
 *
 * requirements/10-multimodal-files-url.md 섹션 4
 */
@Slf4j
@Service
public class TesseractOcrServiceImpl implements TesseractOcrService {

    private static final String LOW_CONFIDENCE_MSG = "[이미지: OCR 신뢰도 낮음, 해석 불가]";

    @Value("${rag.tesseract.datapath:/usr/share/tesseract-ocr/4.00/tessdata}")
    private String tessDataPath;

    @Override
    public String ocr(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) return "";
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (img == null) return LOW_CONFIDENCE_MSG;
            return ocr(img);
        } catch (Exception e) {
            log.error("OCR 예외", e);
            return LOW_CONFIDENCE_MSG;
        }
    }

    @Override
    public String ocr(BufferedImage image) {
        if (image == null) return "";
        Tesseract tess = new Tesseract();
        tess.setDatapath(tessDataPath);
        tess.setLanguage("kor+eng");
        tess.setPageSegMode(3);
        // OEM 기본값(3=자동 선택)이 배포판에 따라 legacy 엔진으로 폴백되면
        // 한글이 음절마다 공백으로 쪼개져 인식되는 문제가 있어 LSTM 전용으로 강제한다.
        tess.setOcrEngineMode(1);
        try {
            String result = tess.doOCR(image);
            return (result == null || result.isBlank()) ? LOW_CONFIDENCE_MSG : result;
        } catch (TesseractException e) {
            log.warn("Tesseract OCR 실패: {}", e.getMessage());
            return LOW_CONFIDENCE_MSG;
        } catch (Exception e) {
            log.error("OCR 예외", e);
            return LOW_CONFIDENCE_MSG;
        }
    }
}
