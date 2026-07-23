package com.ocr.application.command;

public record StartPiDataExtractionJobCommand(
        String fileName,
        byte[] pdfBytes,
        String contentType,
        String callbackUrl
) {
}
