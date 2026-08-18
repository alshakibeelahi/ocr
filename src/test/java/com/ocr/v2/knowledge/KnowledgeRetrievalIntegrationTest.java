package com.ocr.v2.knowledge;

import com.ocr.v2.DeterministicEmbeddingConfig;
import com.ocr.v2.pipeline.DocumentSignature;
import com.ocr.v2.pipeline.KnowledgeRetriever;
import com.ocr.v2.pipeline.RetrievedKnowledge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end wiring of the knowledge base: Flyway migration, JPA entities, seeding from
 * {@code classpath:knowledge/*.yml}, pgvector indexing, and typed retrieval with metadata filters.
 *
 * <p>Runs against a real pgvector container - the filter expressions and the vector column
 * definition are exactly the things a mock would not catch. The embedding model is replaced with a
 * deterministic bag-of-words model so no Ollama server is needed and the test cannot flake on model
 * behaviour; what is under test here is the plumbing, not embedding quality.
 */
@Testcontainers
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@Import(DeterministicEmbeddingConfig.class)
@EnabledIf("dockerAvailable")
class KnowledgeRetrievalIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("ocr")
                    .withUsername("ocr")
                    .withPassword("ocr");

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // The deterministic embedding model below scores lower than a real one; thresholds are
        // relaxed so this test exercises filtering and wiring rather than embedding quality.
        registry.add("ai.rag.layout-pattern-threshold", () -> "0.0");
        registry.add("ai.rag.field-rule-threshold", () -> "0.0");
        registry.add("ai.rag.example-threshold", () -> "0.0");
    }

    @Autowired
    KnowledgeDocumentRepository repository;

    @Autowired
    KnowledgeService knowledgeService;

    @Autowired
    KnowledgeRetriever retriever;

    @Autowired
    KnowledgeSeeder seeder;

    @Test
    @DisplayName("the built-in knowledge files are seeded and indexed on startup")
    void seedsBuiltInKnowledge() {
        assertThat(repository.count()).isPositive();
        assertThat(repository.findByTypeAndEnabledTrue(KnowledgeType.SCHEMA)).isNotEmpty();
        assertThat(repository.findByTypeAndEnabledTrue(KnowledgeType.FIELD_RULE)).isNotEmpty();
        assertThat(repository.findByTypeAndEnabledTrue(KnowledgeType.LAYOUT_PATTERN)).isNotEmpty();
        assertThat(repository.findByAlwaysIncludeTrueAndEnabledTrue()).isNotEmpty();
    }

    @Test
    @DisplayName("re-seeding is idempotent: unchanged entries are not duplicated")
    void seedingIsIdempotent() throws Exception {
        long before = repository.count();
        seeder.seed();  // simulates a restart
        assertThat(repository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("a document resembling nothing still receives every mandatory rule")
    void mandatoryRulesAlwaysApply() {
        DocumentSignature nonsense = new DocumentSignature(
                "zzzz qqqq unrelated gibberish", List.of(), List.of(), List.of(), List.of(),
                DocumentSignature.Source.TEXT_LAYER);

        RetrievedKnowledge knowledge = retriever.retrieve(nonsense, true);

        // Schema documents are also flagged always-include, so the expected set is the union,
        // not the sum: an entry reachable both ways must still be injected exactly once.
        long expected = java.util.stream.Stream.concat(
                        knowledgeService.schemaDocuments().stream(),
                        knowledgeService.mandatoryRules().stream())
                .map(KnowledgeDocumentEntity::getId)
                .distinct()
                .count();

        List<RetrievedKnowledge.Chunk> mandatory = knowledge.chunks().stream()
                .filter(RetrievedKnowledge.Chunk::mandatory)
                .toList();

        assertThat(mandatory).hasSize((int) expected);
        assertThat(mandatory).extracting(RetrievedKnowledge.Chunk::id).doesNotHaveDuplicates();
        assertThat(knowledge.chunks()).anyMatch(chunk -> chunk.type() == KnowledgeType.SCHEMA);
    }

    @Test
    @DisplayName("a matching signature pulls in the relevant layout pattern on top of the mandatory rules")
    void retrievesMatchingLayoutPattern() {
        DocumentSignature signature = new DocumentSignature(
                "Table columns: SL STYLE DESCRIPTION QTY UNIT PRICE AMOUNT. "
                        + "Terms block contains H.S CODE label. No HS code column in the table.",
                List.of(), List.of(), List.of(), List.of(), DocumentSignature.Source.TEXT_LAYER);

        RetrievedKnowledge knowledge = retriever.retrieve(signature, true);

        assertThat(knowledge.chunks())
                .anyMatch(chunk -> chunk.type() == KnowledgeType.LAYOUT_PATTERN && !chunk.mandatory());
        // Matched chunks carry a score; mandatory ones were never scored.
        assertThat(knowledge.chunks())
                .filteredOn(chunk -> !chunk.mandatory())
                .allMatch(chunk -> chunk.score() != null);
    }

    @Test
    @DisplayName("useRag=false falls back to the mandatory rules only - never fewer")
    void ragDisabledStillAppliesMandatoryRules() {
        DocumentSignature signature = new DocumentSignature(
                "PROFORMA INVOICE with an HS CODE terms block", List.of(), List.of(), List.of(), List.of(),
                DocumentSignature.Source.TEXT_LAYER);

        RetrievedKnowledge withoutRag = retriever.retrieve(signature, false);

        assertThat(withoutRag.chunks()).isNotEmpty();
        assertThat(withoutRag.chunks()).allMatch(RetrievedKnowledge.Chunk::mandatory);
        assertThat(retriever.retrieve(signature, true).chunks().size())
                .isGreaterThanOrEqualTo(withoutRag.chunks().size());
    }

    @Test
    @DisplayName("editing a rule bumps the knowledge version, which invalidates cached extractions")
    void editingKnowledgeBumpsVersion() {
        long before = knowledgeService.knowledgeVersion();
        KnowledgeDocumentEntity rule = repository.findByTypeAndEnabledTrue(KnowledgeType.FIELD_RULE).get(0);

        knowledgeService.update(rule.getId(), null, null,
                rule.getBody() + "\nAn extra clarification.", null, null, null, null, null);

        assertThat(knowledgeService.knowledgeVersion()).isGreaterThan(before);
    }

}
