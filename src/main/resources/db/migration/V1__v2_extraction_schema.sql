-- =============================================================================
-- v2 Spring AI extraction stack.
--
-- Nothing in here is used by v1: the v1 job flow stays entirely in memory and is
-- unaffected by this schema.
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- -----------------------------------------------------------------------------
-- Knowledge base: the source of truth for what the extractor knows.
-- The vector table below is a derived index over these rows and can be rebuilt
-- from them at any time (POST /api/v2/knowledge/reindex).
-- -----------------------------------------------------------------------------
CREATE TABLE knowledge_document (
    id              uuid PRIMARY KEY,
    source_key      text UNIQUE,                 -- stable key for seeded entries; NULL for API-created
    type            varchar(32)  NOT NULL,       -- FIELD_RULE | LAYOUT_PATTERN | EXAMPLE | SCHEMA
    title           text         NOT NULL,
    body            text         NOT NULL,
    field_name      text,
    issuer          text,
    tags            text,
    always_include  boolean      NOT NULL DEFAULT false,
    enabled         boolean      NOT NULL DEFAULT true,
    checksum        varchar(64)  NOT NULL,
    revision        integer      NOT NULL DEFAULT 1,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX idx_knowledge_type ON knowledge_document (type) WHERE enabled;
-- Mandatory rules are fetched on every single extraction; this keeps that free.
CREATE INDEX idx_knowledge_always_include ON knowledge_document (always_include) WHERE enabled AND always_include;

-- -----------------------------------------------------------------------------
-- pgvector index over the knowledge base.
--
-- IMPORTANT: vector(768) matches ai.embedding.dimensions, which matches the
-- default embedding model nomic-embed-text. Changing the embedding model means
-- changing this dimension in a NEW migration and re-running the reindex - old
-- vectors live in a different space and would return meaningless neighbours.
--
-- The column layout is the one Spring AI's PgVectorStore expects.
-- -----------------------------------------------------------------------------
CREATE TABLE knowledge_vector (
    id        uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
    content   text,
    metadata  json,
    embedding vector(768)
);

CREATE INDEX idx_knowledge_vector_embedding
    ON knowledge_vector USING hnsw (embedding vector_cosine_ops);

-- -----------------------------------------------------------------------------
-- Audit trail. One row per extraction attempt, successful or not.
--
-- For financial data "which model, under which prompt version, with which rules,
-- produced this number" has to remain answerable long after the fact.
-- -----------------------------------------------------------------------------
CREATE TABLE extraction_run (
    id                 uuid PRIMARY KEY,
    created_at         timestamptz NOT NULL DEFAULT now(),
    file_name          text,
    file_sha256        varchar(64) NOT NULL,
    page_count         integer     NOT NULL DEFAULT 0,
    provider           text        NOT NULL,
    model              text,
    prompt_version     text        NOT NULL,
    knowledge_version  bigint      NOT NULL DEFAULT 0,
    knowledge_ids      text,
    rag_enabled        boolean     NOT NULL DEFAULT true,
    text_layer_used    boolean     NOT NULL DEFAULT false,
    duration_ms        bigint      NOT NULL DEFAULT 0,
    prompt_tokens      integer,
    completion_tokens  integer,
    status             varchar(16) NOT NULL,     -- COMPLETED | FAILED
    cached             boolean     NOT NULL DEFAULT false,
    result             jsonb,
    verification       jsonb,
    error_message      text
);

CREATE INDEX idx_extraction_run_created_at ON extraction_run (created_at DESC);
CREATE INDEX idx_extraction_run_sha ON extraction_run (file_sha256);

-- -----------------------------------------------------------------------------
-- Result cache.
--
-- The key is the SHA-256 of the exact upload plus provider, model, prompt version
-- and knowledge version. A hit therefore means "this identical file, under
-- identical conditions" - never "an invoice that looked similar". Two invoices
-- from the same supplier with different amounts can never collide here.
-- -----------------------------------------------------------------------------
CREATE TABLE extraction_cache (
    cache_key          varchar(128) PRIMARY KEY,
    file_sha256        varchar(64)  NOT NULL,
    provider           text         NOT NULL,
    model              text,
    prompt_version     text         NOT NULL,
    knowledge_version  bigint       NOT NULL DEFAULT 0,
    -- Kept so a cache hit can report the same page count as the original run,
    -- without reparsing the PDF just to answer that one field.
    page_count         integer      NOT NULL DEFAULT 0,
    result             jsonb        NOT NULL,
    verification       jsonb,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    hits               integer      NOT NULL DEFAULT 0
);

CREATE INDEX idx_extraction_cache_created_at ON extraction_cache (created_at DESC);
