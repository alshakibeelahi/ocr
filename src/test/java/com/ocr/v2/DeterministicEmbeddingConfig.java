package com.ocr.v2;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;

/**
 * A deterministic stand-in for the Ollama embedding model.
 *
 * <p>Hashes tokens into buckets and L2-normalises, so cosine similarity tracks token overlap. That
 * keeps retrieval meaningful enough to test ranking and filtering, while removing the need for a
 * model server and any chance of the suite flaking on model behaviour.
 *
 * <p>The dimension matches the production default (768) on purpose: the pgvector column definition
 * in the Flyway migration is then genuinely exercised.
 *
 * <p>Registered under its own name and marked {@code @Primary} rather than overriding the
 * production bean by name: {@code @Primary} beats both by-type ambiguity and by-name matching
 * during autowiring, so which model the vector store gets does not depend on configuration-class
 * processing order.
 */
@TestConfiguration
public class DeterministicEmbeddingConfig {

    public static final int DIMENSIONS = 768;

    @Bean
    @Primary
    public EmbeddingModel testEmbeddingModel() {
        return new EmbeddingModel() {

            @Override
            public float[] embed(String text) {
                float[] vector = new float[DIMENSIONS];
                for (String token : text.toLowerCase().split("[^a-z0-9]+")) {
                    if (token.isBlank()) {
                        continue;
                    }
                    vector[Math.abs(token.hashCode()) % DIMENSIONS] += 1f;
                }
                double norm = 0;
                for (float value : vector) {
                    norm += value * value;
                }
                norm = Math.sqrt(norm);
                if (norm > 0) {
                    for (int i = 0; i < vector.length; i++) {
                        vector[i] /= (float) norm;
                    }
                }
                return vector;
            }

            @Override
            public float[] embed(Document document) {
                return embed(document.getText() == null ? "" : document.getText());
            }

            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                List<Embedding> embeddings = new ArrayList<>();
                List<String> inputs = request.getInstructions();
                for (int i = 0; i < inputs.size(); i++) {
                    embeddings.add(new Embedding(embed(inputs.get(i)), i));
                }
                return new EmbeddingResponse(embeddings);
            }

            @Override
            public int dimensions() {
                return DIMENSIONS;
            }
        };
    }
}
