package com.ocr.v2.provider;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.time.Duration;
import java.util.function.BiFunction;

/**
 * A ready-to-use chat provider: the built {@link ChatModel} plus everything the pipeline needs to
 * know about it without switching on {@link ProviderType} again.
 *
 * @param optionsFactory builds per-request options; arguments are the model id override (may be
 *                       null, meaning "use the configured default") and whether JSON mode is wanted
 */
public record ProviderHandle(
        String name,
        ProviderType type,
        String defaultModel,
        int maxImages,
        boolean nativeJsonMode,
        /** Hard cap for one extraction call against this provider. */
        Duration timeout,
        ChatModel chatModel,
        BiFunction<String, Boolean, ChatOptions> optionsFactory
) {

    public ChatOptions options(String modelOverride, boolean jsonMode) {
        return optionsFactory.apply(modelOverride, jsonMode && nativeJsonMode);
    }

    public String modelOr(String modelOverride) {
        return modelOverride == null || modelOverride.isBlank() ? defaultModel : modelOverride;
    }
}
