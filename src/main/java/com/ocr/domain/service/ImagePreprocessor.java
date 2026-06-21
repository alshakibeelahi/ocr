package com.ocr.domain.service;

import com.ocr.domain.model.PageMetadata;

import java.awt.image.BufferedImage;

public interface ImagePreprocessor {

    record PreprocessResult(BufferedImage image, PageMetadata metadata) {
    }

    PreprocessResult preprocess(BufferedImage source, int dpi, int maxDimension);
}
