package com.ocr.application.port;

import java.util.List;
import java.util.function.Consumer;

public interface OllamaVisionPort {

    void streamOcr(String base64Png, Consumer<String> onChunk);

    void streamVision(String base64Png, String prompt, Consumer<String> onChunk);

    void streamVision(String base64Png, String prompt, boolean jsonMode, Consumer<String> onChunk);

    void streamVision(List<String> base64Pngs, String prompt, boolean jsonMode, Consumer<String> onChunk);
}
