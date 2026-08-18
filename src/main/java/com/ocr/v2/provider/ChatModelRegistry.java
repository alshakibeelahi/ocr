package com.ocr.v2.provider;

import com.ocr.v2.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ocr.v2.config.V2Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds every provider that could be built at startup and resolves the one a request asks for.
 *
 * <p>Resolution order: the request's {@code provider} field, else {@code ai.default-provider}
 * (Ollama out of the box). An unknown or unavailable provider is a caller error - it produces a
 * clear 400, never a silent fallback to a different vendor, because which model saw the document
 * is part of the audit trail.
 */
@V2Component
public class ChatModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(ChatModelRegistry.class);

    private final Map<String, ProviderHandle> handles = new LinkedHashMap<>();
    private final Map<String, String> unavailable = new LinkedHashMap<>();
    private final String defaultProvider;

    public ChatModelRegistry(AiProperties properties, ChatModelFactory factory) {
        this.defaultProvider = properties.defaultProvider();

        properties.providers().forEach((name, config) -> {
            if (!config.enabled()) {
                unavailable.put(name, "disabled (ai.providers." + name + ".enabled=false)");
                return;
            }
            ProviderHandle handle = factory.create(name, config);
            if (handle == null) {
                unavailable.put(name, "enabled but could not be initialised - check credentials and logs");
                return;
            }
            handles.put(name, handle);
            log.info("Chat provider ready: name={}, type={}, model={}", name, config.type(), config.model());
        });

        if (handles.isEmpty()) {
            log.warn("No chat provider is available. v2 extraction requests will fail until one is configured.");
        } else if (!handles.containsKey(defaultProvider)) {
            log.warn("ai.default-provider='{}' is not among the available providers {}. "
                    + "Requests must name a provider explicitly.", defaultProvider, handles.keySet());
        }
    }

    /** @throws ProviderUnavailableException when the named provider is unknown or failed to start */
    public ProviderHandle resolve(String requestedProvider) {
        String name = requestedProvider == null || requestedProvider.isBlank()
                ? defaultProvider
                : requestedProvider.trim().toLowerCase();

        ProviderHandle handle = handles.get(name);
        if (handle != null) {
            return handle;
        }
        String reason = unavailable.get(name);
        throw new ProviderUnavailableException(reason == null
                ? "Unknown provider '" + name + "'. Available: " + handles.keySet()
                : "Provider '" + name + "' is " + reason + ". Available: " + handles.keySet());
    }

    public List<String> availableProviders() {
        return List.copyOf(handles.keySet());
    }

    public Map<String, ProviderHandle> handles() {
        return Map.copyOf(handles);
    }

    public Map<String, String> unavailableProviders() {
        return Map.copyOf(unavailable);
    }

    public String defaultProvider() {
        return defaultProvider;
    }
}
