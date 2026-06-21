package com.ocr.infrastructure.persistence;

import com.ocr.application.dto.StreamEvent;
import com.ocr.application.dto.StreamEventType;
import com.ocr.application.port.StreamEventPublisher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Component
public class InMemoryStreamEventPublisher implements StreamEventPublisher {

    private final Map<String, List<Consumer<StreamEvent>>> listeners = new ConcurrentHashMap<>();
    private final Map<String, List<StreamEvent>> eventBuffer = new ConcurrentHashMap<>();

    @Override
    public void subscribe(String jobId, Consumer<StreamEvent> listener) {
        listeners.computeIfAbsent(jobId, key -> new CopyOnWriteArrayList<>()).add(listener);
        replayBufferedEvents(jobId, listener);
    }

    @Override
    public void unsubscribe(String jobId, Consumer<StreamEvent> listener) {
        List<Consumer<StreamEvent>> jobListeners = listeners.get(jobId);
        if (jobListeners != null) {
            jobListeners.remove(listener);
            if (jobListeners.isEmpty()) {
                listeners.remove(jobId);
            }
        }
    }

    @Override
    public void publish(StreamEvent event) {
        eventBuffer.computeIfAbsent(event.jobId(), key -> new CopyOnWriteArrayList<>()).add(event);
        List<Consumer<StreamEvent>> jobListeners = listeners.get(event.jobId());
        if (jobListeners == null) {
            return;
        }
        for (Consumer<StreamEvent> listener : jobListeners) {
            listener.accept(event);
        }
        if (isTerminal(event)) {
            cleanup(event.jobId());
        }
    }

    private void replayBufferedEvents(String jobId, Consumer<StreamEvent> listener) {
        List<StreamEvent> buffered = eventBuffer.get(jobId);
        if (buffered == null) {
            return;
        }
        for (StreamEvent event : new ArrayList<>(buffered)) {
            listener.accept(event);
        }
    }

    private boolean isTerminal(StreamEvent event) {
        return event.type() == StreamEventType.JOB_COMPLETED || event.type() == StreamEventType.ERROR;
    }

    private void cleanup(String jobId) {
        eventBuffer.remove(jobId);
        listeners.remove(jobId);
    }
}
