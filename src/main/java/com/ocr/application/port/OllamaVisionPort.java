package com.ocr.application.port;

import java.util.function.Consumer;

public interface OllamaVisionPort {

    void streamOcr(String base64Png, Consumer<String> onChunk);
}
