package com.ocr.application.port;

import com.ocr.application.dto.OcrJobResponse;

public interface JobCallbackPort {

    void postJobResult(String callbackUrl, OcrJobResponse jobResponse);
}
