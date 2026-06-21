package com.ocr.application.port;

import com.ocr.application.dto.StreamEvent;

import java.util.function.Consumer;

public interface StreamEventPublisher {

    void subscribe(String jobId, Consumer<StreamEvent> listener);

    void unsubscribe(String jobId, Consumer<StreamEvent> listener);

    void publish(StreamEvent event);
}
