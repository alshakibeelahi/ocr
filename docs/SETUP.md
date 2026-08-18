# v2 Setup Guide — Spring AI, RAG-grounded PI extraction

This guide covers the **v2** API: `/api/v2/pi-extraction`. It is synchronous, works against any
supported model vendor, and grounds every extraction in a knowledge base of trade-finance rules.

**v1 is unchanged.** `/api/ocr/**` and `/api/v1/pi-data-extraction/**` behave exactly as before —
same job flow, same callback contract, same response shape. Nothing in this guide alters them.

---

## Table of contents

1. [What v2 does differently](#1-what-v2-does-differently)
2. [Quick start](#2-quick-start)
3. [Using the API](#3-using-the-api)
4. [Reading the response](#4-reading-the-response)
5. [Choosing a provider](#5-choosing-a-provider)
6. [Managing the knowledge base](#6-managing-the-knowledge-base)
7. [Long requests and reverse proxies](#7-long-requests-and-reverse-proxies)
8. [Running v1 only (`lite` profile)](#8-running-v1-only-lite-profile)
9. [Configuration reference](#9-configuration-reference)
10. [Troubleshooting](#10-troubleshooting)
11. [Performance tuning](#11-performance-tuning)

---

## 1. What v2 does differently

| | v1 | v2 |
|---|---|---|
| Interaction | submit job → poll → callback | one request, one answer (streaming or blocking) |
| Model | Ollama only, hand-rolled HTTP | Spring AI; Ollama, OpenAI, Anthropic, Azure, Vertex, Bedrock, Groq, DeepSeek, Mistral |
| Rules | one 60-line prompt in `application.yml` | a knowledge base you can edit at runtime |
| Prompt content | every rule, every time | mandatory rules + rules matching *this* document |
| Verification | none | schema + arithmetic + grounding against the document's own text |
| Provenance | none | which model, which prompt version, which rules — recorded per extraction |

### How the RAG actually works

The point of the knowledge base is **not** "this invoice looks like the last one, reuse that answer".
It is "this invoice looks like a kind I have rules for — apply those rules while reading it".

```
  upload
    │
    ├─ 0. ingest      SHA-256, render pages, read the PDF's text layer        (no model)
    ├─ 1. signature   who issued it, what the table columns are called,
    │                 which currency tokens appear — values masked out        (no model *)
    ├─ 2. retrieve    ALWAYS: the schema + every mandatory rule
    │                 PLUS:   layout patterns / rules / examples that
    │                         match this signature                            (no model)
    ├─ 3. extract     one streamed vision call with:
    │                   grounding rules → knowledge (hints, no values)
    │                   → the document's own text → the page images
    ├─ 4. normalise   the same deterministic rules v1 uses
    ├─ 5. verify      schema + arithmetic + every value found in the
    │                 document text                                           (no model)
    └─ 6. persist     audit row + result cache
```
\* scans with no text layer pay for one small vision call that reports structure only.

Three properties are deliberate and worth knowing:

- **Retrieval is additive, never gating.** Rules marked `always_include` bypass similarity search
  entirely. A document that matches nothing in the knowledge base still gets the full mandatory rule
  set — never worse than v1, better when there is a match.
- **The signature carries no values.** Invoice numbers, dates and amounts are masked before the
  retrieval query is built, so two invoices from the same supplier with different amounts produce
  the *same* signature. Retrieving on a value would be the first step towards answering one invoice
  with another one's data.
- **Examples are redacted.** Every digit in a stored example is `#`, and the prompt tells the model
  that example values are always wrong for the document in hand.

---

## 2. Quick start

### Prerequisites

- Docker and Docker Compose
- ~8 GB free disk (vision model + embedding model + images)
- A GPU is optional; see `deploy/.env.example` for the CPU/NVIDIA/AMD switch

### Production stack (everything in Docker)

```bash
cd deploy
cp .env.example .env
# edit .env: at minimum pick COMPOSE_PROFILES (cpu | gpu | rocm) and change POSTGRES_PASSWORD
docker compose up -d --build
```

This starts four things: PostgreSQL with pgvector, Ollama, a one-shot model puller, and the app.

First start pulls two models — the vision model (`qwen2.5vl:3b`, ~3 GB) and the embedding model
(`nomic-embed-text`, ~270 MB). Watch progress:

```bash
docker logs -f ocr-ollama-init
```

### Verify

```bash
curl -s http://localhost:8080/api/v2/pi-extraction/health | jq
```

```json
{
  "service": "pi-extraction-v2",
  "status": "UP",
  "defaultProvider": "ollama",
  "availableProviders": ["ollama"],
  "unavailableProviders": { "openai": "disabled (ai.providers.openai.enabled=false)" },
  "knowledgeStoreReachable": true,
  "knowledgeDocuments": 24,
  "knowledgeIndexed": 24,
  "embeddingModel": "nomic-embed-text",
  "embeddingDimensions": 768,
  "promptVersion": "v2.1",
  "ragEnabled": true
}
```

`status: UP` requires all three: a usable provider, a non-empty knowledge base, and
`knowledgeIndexed >= knowledgeDocuments`. That last one matters — rules sitting in the table with no
vectors behind them would degrade retrieval without any error appearing anywhere. If health is
`DEGRADED`, see [Troubleshooting](#10-troubleshooting).

### Development stack

Ollama and PostgreSQL in Docker, the app from Maven:

```bash
docker compose up -d            # from the repo root: ollama + ollama-init + postgres
mvn spring-boot:run -s .mvn/settings.xml
```

---

## 3. Using the API

### Streaming — the recommended default

```bash
curl -N -X POST http://localhost:8080/api/v2/pi-extraction/extract/stream \
  -F "file=@proforma-invoice.pdf"
```

Events arrive in this order:

```
event:stage
data:{"stage":"ingest","message":"Reading document"}

event:stage
data:{"stage":"signature","message":"Identifying document layout"}

event:stage
data:{"stage":"retrieve","message":"Selecting extraction rules"}

event:stage
data:{"stage":"extract","message":"Reading the document with ollama/qwen2.5vl:3b"}

event:delta
data:{"text":"{\"DEFN_PROFORMA"}
...

event:stage
data:{"stage":"verify","message":"Checking the extraction against the document"}

event:result
data:{ ...the full envelope... }
```

A `:keep-alive` comment goes out every 15 s so the connection is never idle, even while the model is
thinking. Set `streamDeltas=false` if you only want stage and result events.

### Blocking

```bash
curl -X POST http://localhost:8080/api/v2/pi-extraction/extract \
  -F "file=@proforma-invoice.pdf" | jq
```

One JSON response when the extraction finishes. **No request timeout is applied** — but note that
any reverse proxy in between has its own idle timeout, which is why streaming is the default
recommendation. See [section 7](#7-long-requests-and-reverse-proxies).

### Parameters

Both endpoints accept the same multipart form fields:

| Field | Default | Meaning |
|---|---|---|
| `file` | *(required)* | PDF, PNG or JPEG |
| `provider` | `ai.default-provider` | `ollama`, `openai`, `anthropic`, ... |
| `model` | the provider's configured model | per-request model override |
| `useRag` | `true` | `false` runs with mandatory rules only — useful for A/B checking what the knowledge base contributes |
| `includeTextLayer` | `true` | feed the PDF's own text to the model alongside the images |
| `useCache` | `true` | `false` forces a fresh extraction |
| `streamDeltas` | `true` | streaming endpoint only |

### Other endpoints

```bash
GET  /api/v2/pi-extraction/health          # readiness: providers, knowledge base, embedding model
GET  /api/v2/pi-extraction/providers       # what is configured and usable
GET  /api/v2/pi-extraction/runs/{id}       # audit record for a past extraction
```

---

## 4. Reading the response

```json
{
  "requestId": "0c9f...",
  "fileName": "proforma-invoice.pdf",
  "provider": "ollama",
  "model": "qwen2.5vl:3b",
  "pageCount": 3,
  "durationMs": 42137,
  "cached": false,
  "textLayerUsed": true,
  "signatureSource": "TEXT_LAYER",

  "extraction": {
    "DEFN_PROFORMA_INVOICE": { "pi_no": "...", "total_amount": 4500.00, "...": "..." },
    "DEFN_PROFORMA_INVOICE_HSC": [ { "sl_no": 1, "...": "..." } ]
  },

  "verification": {
    "status": "WARN",
    "schemaValid": true,
    "groundingAvailable": true,
    "fieldsChecked": 31,
    "fieldsGrounded": 30,
    "checks": [
      { "name": "schema", "passed": true, "detail": "Output matches the target schema" },
      { "name": "line-total-arithmetic", "passed": true, "detail": "2 line total(s) reconcile" },
      { "name": "grounding", "passed": false, "detail": "30 of 31 extracted values found verbatim in the document text" }
    ],
    "warnings": ["1 extracted value(s) could not be found in the document text. Review them before use: DEFN_PROFORMA_INVOICE.swift"],
    "ungroundedFields": ["DEFN_PROFORMA_INVOICE.swift"]
  },

  "knowledgeUsed": [
    { "id": "...", "type": "SCHEMA", "title": "Proforma Invoice target JSON schema", "mandatory": true },
    { "id": "...", "type": "LAYOUT_PATTERN", "title": "HS code printed once in the terms or notes block", "mandatory": false, "score": 0.81 }
  ],

  "usage": { "promptTokens": 4210, "completionTokens": 892 }
}
```

`extraction` is **byte-for-byte the shape v1 returns**. Migrating a consumer from v1 to v2 is a URL
change plus reading `extraction` out of the envelope.

### What `verification` means

| | Meaning | What to do |
|---|---|---|
| `status: PASS` | Every check that could run, passed | Use it |
| `status: WARN` | At least one check did not pass | Read `warnings`; route to a human if it matters |
| `groundingAvailable: false` | Scan or image upload — no text layer to check against | Treat every value as unverified |
| `ungroundedFields` | Values not found verbatim in the document text | Review these specifically |

**Nothing is ever silently corrected.** If a printed total disagrees with the line items, both are
reported exactly as extracted and the mismatch is named in `warnings`. For payment data a visible
discrepancy gets reviewed; a quiet correction gets trusted.

A field can be legitimately ungrounded — a currency inferred from a `US$` column header, an
ambiguous date left un-normalised, a value the text extractor re-flowed. `ungroundedFields` is a
"look at this", not a verdict.

---

## 5. Choosing a provider

Ollama is the default: local, no API key, nothing leaves the host. Other vendors are opt-in.

### Enable one

In `deploy/.env`:

```bash
AI_OPENAI_ENABLED=true
OPENAI_API_KEY=sk-...
OPENAI_MODEL=gpt-4.1
```

```bash
docker compose up -d ocr-app
curl -s http://localhost:8080/api/v2/pi-extraction/providers | jq
```

### Use it for one request

```bash
curl -X POST http://localhost:8080/api/v2/pi-extraction/extract \
  -F "file=@invoice.pdf" \
  -F "provider=openai" \
  -F "model=gpt-4.1"
```

An unknown or unconfigured provider returns **400 with the list of available ones**. It never falls
back to a different vendor — which model read the document is part of the audit trail, so it has to
be what you asked for or nothing.

### Change the default

```bash
AI_DEFAULT_PROVIDER=anthropic
```

### All supported providers

| Name | `.env` switch | Credentials |
|---|---|---|
| `ollama` | `AI_OLLAMA_ENABLED` | none |
| `openai` | `AI_OPENAI_ENABLED` | `OPENAI_API_KEY` |
| `anthropic` | `AI_ANTHROPIC_ENABLED` | `ANTHROPIC_API_KEY` |
| `azure-openai` | `AI_AZURE_ENABLED` | `AZURE_OPENAI_ENDPOINT`, `AZURE_OPENAI_API_KEY`, `AZURE_OPENAI_DEPLOYMENT` |
| `vertex-gemini` | `AI_VERTEX_ENABLED` | `VERTEX_PROJECT_ID`, `VERTEX_LOCATION`, `GOOGLE_APPLICATION_CREDENTIALS` |
| `bedrock` | `AI_BEDROCK_ENABLED` | default AWS chain + `AWS_REGION` |
| `groq` | `AI_GROQ_ENABLED` | `GROQ_API_KEY` |
| `deepseek` | `AI_DEEPSEEK_ENABLED` | `DEEPSEEK_API_KEY` |
| `mistral` | `AI_MISTRAL_ENABLED` | `MISTRAL_API_KEY` |

`groq`, `deepseek` and `mistral` reach their endpoints through the OpenAI-compatible client with a
different `base-url` — configuration only, no extra dependency. Any other OpenAI-compatible endpoint
(vLLM, LM Studio, an internal gateway) works the same way: add an entry under `ai.providers` with
`type: OPENAI` and point `base-url` at it.

### Notes per vendor

- **The model must be vision-capable.** A text-only model will return nonsense or refuse.
- **Image limits differ** — `ai.providers.<name>.max-images` guards this and produces a clear 400
  rather than a vendor error. Defaults: Ollama 40, Anthropic 90, OpenAI/Azure/Bedrock 20, Mistral 8,
  Groq 5.
- **Anthropic and Bedrock have no JSON mode.** They are configured `json-mode: false` and the
  pipeline extracts the JSON object from the response instead. No action needed.
- **A missing API key disables only that provider**, never startup. It shows up under
  `unavailableProviders` in `/health`.

---

## 6. Managing the knowledge base

This is what replaces "remember to add that rule to the prompt".

### Where the rules live

Built-in rules ship as seed files and are loaded on every startup (upsert by content checksum, so
restarting does not churn the index):

| File | Contents |
|---|---|
| `src/main/resources/knowledge/00-schema.yml` | The target JSON schema. Always injected. |
| `src/main/resources/knowledge/10-field-rules.yml` | Business rules — HS-code propagation, currency evidence, summary rows, tenor, numeric formatting. |
| `src/main/resources/knowledge/20-layout-patterns.yml` | Where fields sit in a given family of documents. **This is the file that grows.** |
| `src/main/resources/knowledge/30-examples.yml` | Redacted worked examples. |

### Add a rule at runtime, no redeploy

```bash
curl -X POST http://localhost:8080/api/v2/knowledge \
  -H 'Content-Type: application/json' \
  -d '{
    "type": "LAYOUT_PATTERN",
    "title": "Northwind Mills: HS code sits under the signature block",
    "issuer": "Northwind Mills Ltd",
    "tags": ["hs-code", "northwind"],
    "alwaysInclude": false,
    "body": "Signals: letterhead reads NORTHWIND MILLS, item table headed SL/ARTICLE/PCS/RATE/VALUE, and no HS code column.\n\nThe HS code is printed under the authorised-signature block at the foot of the last page. Apply it to every item row."
  }'
```

It takes effect on the next request, and appears in that extraction's `knowledgeUsed`.

### The four types

| Type | Retrieved by | Use it for |
|---|---|---|
| `SCHEMA` | always | the output contract |
| `FIELD_RULE` | similarity, or always if `alwaysInclude` | how to interpret a field |
| `LAYOUT_PATTERN` | similarity | where fields sit in a template |
| `EXAMPLE` | similarity | worked shapes, **digits masked** |

### `alwaysInclude` — when to set it

Set it `true` for anything whose failure would produce a *wrong number*: HS-code propagation, summary
row exclusion, numeric formatting, the grounding rules. A correctness rule that only fires when a
similarity score clears a threshold is a rule that silently stops applying.

Set it `false` for anything template-specific. Those are the entries that make retrieval worth
having, and injecting all of them on every document would just be the old giant prompt again.

### Never put a real value in the knowledge base

Especially in an `EXAMPLE`. Mask every digit with `#` and use placeholder names. The prompt tells the
model that example values belong to other documents and are always wrong for the one in hand, but
the structural guarantee — there is no real value there to copy — is the one that matters.

### Other knowledge operations

```bash
# Browse
curl -s 'http://localhost:8080/api/v2/knowledge?enabledOnly=true' | jq '.[] | {title, type, alwaysInclude}'

# Debug retrieval: what would this document pull in?
curl -s --get http://localhost:8080/api/v2/knowledge/search \
  --data-urlencode 'q=PROFORMA INVOICE, columns SL STYLE QTY UNIT PRICE AMOUNT, HS CODE in terms block' \
  --data 'topK=5' | jq '.[] | {score, title}'

# Turn a rule off without deleting it
curl -X PUT http://localhost:8080/api/v2/knowledge/{id} \
  -H 'Content-Type: application/json' -d '{"enabled": false}'

# Rebuild every embedding
curl -X POST http://localhost:8080/api/v2/knowledge/reindex
```

### Changing the embedding model

The embedding model is configured **independently of the chat provider** on purpose: switching chat
vendor must not change the vector space the knowledge base was indexed in.

To change it:

1. Add a Flyway migration altering `knowledge_vector.embedding` to the new dimension, **if the
   dimension differs**. `nomic-embed-text` is 768; `bge-m3` and `mxbai-embed-large` are 1024.
2. Set `AI_EMBEDDING_MODEL` and `AI_EMBEDDING_DIMENSIONS`, and pull the model into Ollama.
3. Restart, then `POST /api/v2/knowledge/reindex`.

Skipping step 3 leaves old vectors in a different space — retrieval will return confident nonsense
rather than failing loudly, which is why the reindex endpoint exists.

---

## 7. Long requests and reverse proxies

A large scan on CPU-only Ollama can legitimately take many minutes. The application never times the
client out:

- `spring.mvc.async.request-timeout: -1`
- the blocking endpoint uses a `DeferredResult` with no timeout
- the streaming endpoint uses `SseEmitter(0L)` plus a 15 s comment heartbeat
- the only cap is server-side and per provider: `ai.providers.<name>.timeout` (Ollama: 30 m)

What *will* cut you off is an intermediary. For nginx:

```nginx
location /api/v2/ {
    proxy_pass http://ocr-app:8080;

    proxy_read_timeout    3600s;   # must exceed your longest extraction
    proxy_send_timeout    3600s;
    proxy_connect_timeout   60s;

    # Required for SSE: without these, events are buffered until the response ends,
    # which defeats both the streaming UX and the heartbeat.
    proxy_buffering off;
    proxy_cache off;
    proxy_set_header Connection '';
    proxy_http_version 1.1;
    chunked_transfer_encoding off;
}
```

Behind a load balancer with a hard idle timeout you cannot raise (AWS ALB defaults to 60 s), use the
streaming endpoint — the heartbeat keeps the connection non-idle.

---

## 8. Running v1 only (`lite` profile)

For rolling the new jar out to an existing v1 deployment before the database is ready:

```bash
SPRING_PROFILES_ACTIVE=lite java -jar app.jar
```

or in `deploy/.env`:

```bash
SPRING_PROFILES_ACTIVE=lite
```

Under `lite`, the database auto-configuration is excluded and every v2 bean is undefined, so nothing
asks for a datasource. `/api/ocr/**` and `/api/v1/pi-data-extraction/**` behave exactly as before;
`/api/v2/**` returns 404. You can drop the `postgres` service from the compose file entirely.

---

## 9. Configuration reference

Everything is environment-overridable. Defaults are in `src/main/resources/application.yml`.

### Core

| Variable | Default | Notes |
|---|---|---|
| `AI_DEFAULT_PROVIDER` | `ollama` | used when a request names no provider |
| `AI_PROMPT_VERSION` | `v2.1` | part of the cache key — bump when you change the prompt or schema |
| `AI_RAG_ENABLED` | `true` | `false` disables retrieval globally (mandatory rules still apply) |
| `POSTGRES_URL` / `_USER` / `_PASSWORD` | `jdbc:postgresql://localhost:5432/ocr`, `ocr`, `ocr` | **change the password** |
| `FLYWAY_ENABLED` | `true` | |

### Embeddings

| Variable | Default | Notes |
|---|---|---|
| `AI_EMBEDDING_PROVIDER` | `ollama` | `ollama` or `openai` |
| `AI_EMBEDDING_MODEL` | `nomic-embed-text` | changing it requires a reindex |
| `AI_EMBEDDING_DIMENSIONS` | `768` | must match the Flyway migration |

### Retrieval

| Variable | Default | Notes |
|---|---|---|
| `AI_RAG_FIELD_RULE_TOP_K` / `_THRESHOLD` | `8` / `0.60` | |
| `AI_RAG_LAYOUT_TOP_K` / `_THRESHOLD` | `3` / `0.75` | raise the threshold if irrelevant patterns are matching |
| `AI_RAG_EXAMPLE_TOP_K` / `_THRESHOLD` | `2` / `0.80` | |
| `AI_RAG_MAX_KNOWLEDGE_CHARS` | `14000` | budget for the injected block; lowest-scoring non-mandatory chunks are dropped first |
| `AI_RAG_SEED_ON_STARTUP` | `true` | |

### Extraction

| Variable | Default | Notes |
|---|---|---|
| `AI_CACHE_ENABLED` | `true` | keyed on file SHA-256 + provider + model + prompt version + knowledge version |
| `AI_INCLUDE_TEXT_LAYER` | `true` | big accuracy win on digital PDFs |
| `AI_MAX_TEXT_LAYER_CHARS` | `60000` | |
| `AI_MIN_TEXT_LAYER_CHARS_PER_PAGE` | `120` | below this the PDF is treated as a scan |
| `AI_HEARTBEAT_INTERVAL` | `15s` | SSE keep-alive |

### Shared with v1

`OCR_RENDER_DPI` (200), `OCR_MAX_IMAGE_DIMENSION` (1536), `MAX_FILE_SIZE` (50MB),
`OLLAMA_BASE_URL`, `OLLAMA_MODEL`, `OLLAMA_NUM_CTX`, `OLLAMA_KEEP_ALIVE`.

---

## 10. Troubleshooting

**`knowledgeDocuments: 0` in health**

Seeding failed — deliberately non-fatal, so v1 keeps serving. Usually the embedding model is not
pulled yet. Check `docker logs ocr-app` for `Knowledge seeding failed`; the message names the model
and the URL it tried.

```bash
docker exec ocr-ollama ollama list          # expect nomic-embed-text
docker exec ocr-ollama ollama pull nomic-embed-text
docker restart ocr-app                      # seeding retries automatically
```

Nothing is stranded by a failed seed: entries are indexed *before* they are saved, so a failure
leaves the table untouched and the next start simply redoes the work.

**`knowledgeIndexed` lower than `knowledgeDocuments`**

Rows exist but their vectors do not — retrieval is returning less than it should. Rebuild the index:

```bash
curl -X POST http://localhost:8080/api/v2/knowledge/reindex
```

The usual cause is a changed embedding model (see [section 6](#changing-the-embedding-model)).

**`status: DEGRADED`**

One of: no provider initialised (check `unavailableProviders` in `/health` — each entry says why),
PostgreSQL unreachable (`knowledgeStoreReachable: false`), an empty knowledge base, or an
under-populated index. The other fields in the response distinguish them.

**`400: Provider 'x' is ... Available: [ollama]`**

Working as intended: the provider is disabled, misconfigured, or misspelled. The message says which.

**`400: Document has N pages but provider 'x' accepts at most M images`**

Split the document, or switch to a provider with a higher limit (Anthropic 90, Ollama 40).

**`502: The model did not return parseable JSON`**

The model produced prose instead of an object. The message includes the first 500 characters. Almost
always a non-vision or too-small model — check `ai.providers.<name>.model`.

**Every field is in `ungroundedFields`**

Check `groundingAvailable`. If `false`, it is a scan and nothing could be checked. If `true` and
everything is still ungrounded, the model is likely inventing values — try a larger model, and
compare a `useRag=false` run to see whether a knowledge entry is misleading it.

**Startup fails with `Endpoint must not be empty`**

An Azure auto-configuration is running. All model kinds must be `none` under `spring.ai.model` in
`application.yml` — `chat` alone is not enough, because the Azure image and audio auto-configurations
import the same client builder.

**Hibernate schema validation fails**

Flyway did not run, or ran against a different database. Confirm `FLYWAY_ENABLED=true` and that
`POSTGRES_URL` points where you think it does.

---

## 11. Performance tuning

**The single biggest lever is image size.** Vision tokens scale with pixels:

```bash
OCR_RENDER_DPI=150              # from 200
OCR_MAX_IMAGE_DIMENSION=1280    # from 1536
```

On a 4 GB GPU also set `OLLAMA_NUM_CTX=4096`.

**The text layer is nearly free accuracy.** For digitally generated PDFs it gives the model exact
characters instead of pixels, and it is what makes grounding checks possible. Leave
`AI_INCLUDE_TEXT_LAYER=true` unless you are deliberately measuring vision-only behaviour.

**The cache only helps for re-submissions.** It is keyed on the SHA-256 of the exact bytes, so it
fires when the same file is sent twice — never for a merely similar invoice. Re-sending a document
after editing knowledge correctly misses the cache, because the knowledge version is part of the key.

**Concurrency.** v2 runs on its own pool (`ai-v2-`, 4–8 threads) separate from v1's `ocr-` pool, so
v2 load cannot starve existing OCR jobs. With `OLLAMA_NUM_PARALLEL=1` the model itself serialises
anyway; raise it only if you have the VRAM.

**Where time goes**, roughly, for a 3-page digital PDF on a mid-range GPU:

| Stage | Typical |
|---|---|
| ingest (render + text layer) | 0.5–2 s |
| signature + retrieval | < 100 ms |
| model call | 10–120 s |
| normalise + verify | < 100 ms |

Everything except the model call is negligible, which is why the design spends its effort on giving
the model a smaller, better prompt rather than on optimising the surrounding code.
