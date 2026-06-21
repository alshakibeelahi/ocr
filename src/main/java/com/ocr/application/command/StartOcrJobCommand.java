package com.ocr.application.command;

public record StartOcrJobCommand(
        String fileName,
        byte[] pdfBytes
) {
}
