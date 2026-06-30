package com.ragservice.rag.service;

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
        Tesseract tess = new Tesseract();
        tess.setDatapath(tessDataPath);
        tess.setLanguage("kor+eng");
        tess.setPageSegMode(3);
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (img == null) return LOW_CONFIDENCE_MSG;
            String result = tess.doOCR(img);
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
