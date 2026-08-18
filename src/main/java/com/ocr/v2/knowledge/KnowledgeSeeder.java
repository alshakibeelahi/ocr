package com.ocr.v2.knowledge;

import com.ocr.v2.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import com.ocr.v2.config.V2Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Loads the built-in knowledge base from {@code classpath:knowledge/*.yml} on startup.
 *
 * <p>These files are the rules that used to live inside one giant prompt string in
 * {@code application.yml}. Splitting them into addressable entries is the point of the exercise:
 * a rule can now be edited, disabled or added at runtime through the admin API without a redeploy,
 * and only the rules relevant to the document in hand are sent to the model.
 *
 * <p>Seeding is an upsert keyed by {@code key} and guarded by a content checksum, so restarting the
 * application does not churn the vector index, and an operator's edits to a seeded entry survive
 * until the seed file itself changes.
 */
@V2Component
public class KnowledgeSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSeeder.class);
    private static final String LOCATION = "classpath:knowledge/*.yml";

    private final KnowledgeDocumentRepository repository;
    private final KnowledgeIndexer indexer;
    private final AiProperties properties;

    public KnowledgeSeeder(KnowledgeDocumentRepository repository,
                           KnowledgeIndexer indexer,
                           AiProperties properties) {
        this.repository = repository;
        this.indexer = indexer;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.rag().seedOnStartup()) {
            log.info("Knowledge seeding disabled (ai.rag.seed-on-startup=false)");
            return;
        }
        try {
            seed();
        } catch (Exception e) {
            // A failed seed must not stop the application: v1 has to keep serving, and the v2
            // health endpoint reports knowledgeIndexed so the gap is visible rather than silent.
            // The usual cause is the embedding model not being pulled yet.
            log.error("Knowledge seeding failed; entries that could not be indexed were NOT saved "
                    + "and will be retried on the next start. Check that the embedding model "
                    + "'{}' is available at {}.",
                    properties.embedding().model(), properties.embedding().baseUrl(), e);
        }
    }

    /**
     * Not {@code @Transactional}: this is called from {@link #run} on the same bean, so the proxy
     * would be bypassed and the annotation would be a lie. Each repository call carries its own
     * transaction instead, which is what makes a partial seed safe - the upsert is keyed by content
     * checksum, so the next start simply finishes the job.
     */
    public void seed() throws Exception {
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources(LOCATION);
        if (resources.length == 0) {
            log.warn("No knowledge seed files found at {}", LOCATION);
            return;
        }

        int changed = 0;
        int unchanged = 0;

        for (Resource resource : resources) {
            for (SeedEntry entry : parse(resource)) {
                Optional<KnowledgeDocumentEntity> existing = repository.findBySourceKey(entry.key());
                String checksum = KnowledgeService.checksum(entry.title(), entry.body());

                if (existing.isPresent() && checksum.equals(existing.get().getChecksum())) {
                    unchanged++;
                    continue;
                }

                KnowledgeDocumentEntity entity = existing.orElseGet(() -> {
                    KnowledgeDocumentEntity fresh = new KnowledgeDocumentEntity(
                            UUID.randomUUID(), entry.type(), entry.title(), entry.body());
                    fresh.setSourceKey(entry.key());
                    fresh.setEnabled(true);
                    return fresh;
                });

                entity.setType(entry.type());
                entity.setTitle(entry.title());
                entity.setBody(entry.body());
                entity.setFieldName(entry.fieldName());
                entity.setIssuer(entry.issuer());
                entity.setTags(entry.tags());
                entity.setAlwaysInclude(entry.alwaysInclude());
                if (existing.isPresent()) {
                    entity.setRevision(entity.getRevision() + 1);
                }
                entity.setChecksum(checksum);

                // Index BEFORE saving. The checksum on a saved row is what makes the next start
                // skip this entry, so persisting it after a failed embedding call would strand the
                // entry: present in the table, absent from the index, and never retried. Indexing
                // first means a failure here simply leaves the row unsaved for the next start.
                indexer.index(entity);
                repository.save(entity);
                changed++;
            }
        }

        if (changed == 0) {
            log.info("Knowledge base up to date: {} seeded entries unchanged", unchanged);
            return;
        }
        log.info("Knowledge base seeded: {} entries created/updated, {} unchanged", changed, unchanged);
    }

    @SuppressWarnings("unchecked")
    private List<SeedEntry> parse(Resource resource) throws Exception {
        try (InputStream in = resource.getInputStream()) {
            Object loaded = new Yaml().load(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            if (!(loaded instanceof Collection<?> entries)) {
                log.warn("Skipping knowledge seed file {}: expected a YAML list at the root", resource.getFilename());
                return List.of();
            }
            List<SeedEntry> parsed = new ArrayList<>();
            for (Object raw : entries) {
                if (raw instanceof Map<?, ?> map) {
                    parsed.add(SeedEntry.from((Map<String, Object>) map, resource.getFilename()));
                }
            }
            return parsed;
        }
    }

    /** One entry in a seed file. */
    private record SeedEntry(String key, KnowledgeType type, String title, String body,
                             String fieldName, String issuer, String tags, boolean alwaysInclude) {

        static SeedEntry from(Map<String, Object> map, String fileName) {
            String key = required(map, "key", fileName);
            String typeName = required(map, "type", fileName);
            String title = required(map, "title", fileName);
            String body = required(map, "body", fileName).strip();

            Object tags = map.get("tags");
            String joinedTags = switch (tags) {
                case null -> null;
                case Collection<?> collection -> collection.stream().map(String::valueOf)
                        .reduce((a, b) -> a + "," + b).orElse(null);
                default -> String.valueOf(tags);
            };

            return new SeedEntry(
                    key,
                    parseType(typeName, key),
                    title,
                    body,
                    (String) map.get("fieldName"),
                    (String) map.get("issuer"),
                    joinedTags,
                    Boolean.TRUE.equals(map.get("alwaysInclude")));
        }

        private static KnowledgeType parseType(String typeName, String key) {
            try {
                return KnowledgeType.valueOf(typeName.trim().toUpperCase().replace('-', '_'));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Knowledge entry '" + key + "' has unknown type '" + typeName
                        + "'. Expected one of " + Arrays.toString(KnowledgeType.values()));
            }
        }

        private static String required(Map<String, Object> map, String field, String fileName) {
            Object value = map.get(field);
            if (value == null || String.valueOf(value).isBlank()) {
                throw new IllegalArgumentException(
                        "Knowledge seed file " + fileName + " has an entry missing required field '" + field + "'");
            }
            return String.valueOf(value);
        }
    }
}
