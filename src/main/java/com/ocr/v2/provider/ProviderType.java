package com.ocr.v2.provider;

/**
 * Client implementation backing a configured provider.
 *
 * <p>Several named providers can share one type: {@code groq}, {@code deepseek} and {@code mistral}
 * are all {@link #OPENAI} entries pointed at a different {@code base-url}.
 */
public enum ProviderType {

    /** Local/self-hosted Ollama. The default - no API key, no egress. */
    OLLAMA,

    /** OpenAI, and any OpenAI-compatible endpoint (Groq, DeepSeek, Mistral, vLLM, LM Studio...). */
    OPENAI,

    /** Anthropic Claude. */
    ANTHROPIC,

    /** Azure-hosted OpenAI deployments. */
    AZURE_OPENAI,

    /** Google Vertex AI Gemini. */
    VERTEX_GEMINI,

    /** AWS Bedrock via the Converse API. */
    BEDROCK
}
