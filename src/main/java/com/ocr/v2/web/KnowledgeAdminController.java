package com.ocr.v2.web;

import com.ocr.v2.knowledge.KnowledgeDocumentEntity;
import com.ocr.v2.knowledge.KnowledgeIndexer;
import com.ocr.v2.knowledge.KnowledgeService;
import com.ocr.v2.knowledge.KnowledgeType;
import com.ocr.v2.web.dto.KnowledgeDto;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.annotation.Profile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manage what the extractor knows.
 *
 * <p>This is the endpoint that replaces "remember to add that rule to the prompt": a new supplier
 * template or a newly discovered edge case becomes a knowledge entry here, takes effect on the next
 * request, and is visible in the {@code knowledgeUsed} block of every extraction it influences.
 */
@CrossOrigin(origins = "*")
@RestController
@Profile("!lite")
@RequestMapping("/api/v2/knowledge")
public class KnowledgeAdminController {

    private final KnowledgeService knowledgeService;
    private final VectorStore vectorStore;

    public KnowledgeAdminController(KnowledgeService knowledgeService, VectorStore vectorStore) {
        this.knowledgeService = knowledgeService;
        this.vectorStore = vectorStore;
    }

    @GetMapping
    public List<KnowledgeDto> list(@RequestParam(value = "type", required = false) KnowledgeType type,
                                   @RequestParam(value = "enabledOnly", defaultValue = "false") boolean enabledOnly) {
        return knowledgeService.findAll().stream()
                .filter(entity -> type == null || entity.getType() == type)
                .filter(entity -> !enabledOnly || entity.isEnabled())
                .map(KnowledgeDto::from)
                .toList();
    }

    @GetMapping("/{id}")
    public KnowledgeDto get(@PathVariable UUID id) {
        return KnowledgeDto.from(knowledgeService.get(id));
    }

    @PostMapping
    public ResponseEntity<KnowledgeDto> create(@RequestBody KnowledgeDto.UpsertRequest request) {
        require(request.type() != null, "type is required");
        require(request.title() != null && !request.title().isBlank(), "title is required");
        require(request.body() != null && !request.body().isBlank(), "body is required");

        KnowledgeDocumentEntity created = knowledgeService.create(
                request.type(),
                request.title(),
                request.body(),
                request.fieldName(),
                request.issuer(),
                request.joinedTags(),
                Boolean.TRUE.equals(request.alwaysInclude()),
                request.enabled() == null || request.enabled());
        return ResponseEntity.status(201).body(KnowledgeDto.from(created));
    }

    @PutMapping("/{id}")
    public KnowledgeDto update(@PathVariable UUID id, @RequestBody KnowledgeDto.UpsertRequest request) {
        return KnowledgeDto.from(knowledgeService.update(
                id,
                request.type(),
                request.title(),
                request.body(),
                request.fieldName(),
                request.issuer(),
                request.joinedTags(),
                request.alwaysInclude(),
                request.enabled()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        knowledgeService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Retrieval debugging: run a query through the same vector search the pipeline uses, and see
     * what would have been injected. Use a document's header text as the query.
     */
    @GetMapping("/search")
    public List<Map<String, Object>> search(@RequestParam("q") String query,
                                            @RequestParam(value = "type", required = false) KnowledgeType type,
                                            @RequestParam(value = "topK", defaultValue = "5") int topK,
                                            @RequestParam(value = "threshold", defaultValue = "0.0") double threshold) {
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(threshold);
        if (type != null) {
            builder.filterExpression(KnowledgeIndexer.META_TYPE + " == '" + type.name() + "'");
        }

        List<Document> documents = vectorStore.similaritySearch(builder.build());
        if (documents == null) {
            return List.of();
        }
        return documents.stream().map(document -> {
            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("id", document.getId());
            hit.put("score", document.getScore());
            hit.put("type", document.getMetadata().get(KnowledgeIndexer.META_TYPE));
            hit.put("title", document.getMetadata().get(KnowledgeIndexer.META_TITLE));
            hit.put("alwaysInclude", document.getMetadata().get(KnowledgeIndexer.META_ALWAYS_INCLUDE));
            hit.put("text", document.getText());
            return hit;
        }).toList();
    }

    /**
     * Rebuild every embedding. Required after changing {@code ai.embedding.model} - the old vectors
     * live in a different space and would silently return nonsense neighbours.
     */
    @PostMapping("/reindex")
    public Map<String, Object> reindex() {
        int indexed = knowledgeService.reindexAll();
        return Map.of(
                "indexed", indexed,
                "knowledgeVersion", knowledgeService.knowledgeVersion());
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
