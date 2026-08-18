# OCR / PI Data Extraction

Spring Boot (Java 21) service that turns Proforma Invoice PDFs/images into
structured trade-finance JSON using a vision model.

Two APIs, side by side:

### v1 — job based, Ollama (unchanged)

- **Submit**: `POST /api/v1/pi-data-extraction/jobs` returns `202 { jobId }`
  immediately; extraction runs in the background and the result is POSTed to
  your `callbackUrl` (OAuth client-credentials Bearer token).
- **Poll**: `GET /api/v1/pi-data-extraction/jobs/{jobId}` for status/result.
- **UI**: `/pi-data-extraction.html` (upload + poll + summary + raw overview),
  `/` (plain per-page OCR).

### v2 — synchronous, Spring AI, RAG-grounded → **[docs/SETUP.md](docs/SETUP.md)**

- **One request, one answer**: `POST /api/v2/pi-extraction/extract` (blocking, no
  timeout) or `POST /api/v2/pi-extraction/extract/stream` (SSE, chat-style).
- **Any vendor**: Ollama (default, local), OpenAI, Anthropic, Azure OpenAI,
  Vertex Gemini, Bedrock, Groq, DeepSeek, Mistral — selected per request.
- **Knowledge base instead of one giant prompt**: extraction rules and supplier
  layout patterns live in PostgreSQL/pgvector and are editable at runtime via
  `/api/v2/knowledge`, so only the rules relevant to *this* document are sent.
- **Verified output**: every extraction is checked against the document's own
  text layer plus schema and arithmetic, and reports what could not be verified.
  Nothing is silently corrected.

`extraction` in the v2 response is byte-for-byte the shape v1 returns, so moving
a consumer over is a URL change.

## Quick start (local dev)

```powershell
# 1. Start Ollama + PostgreSQL/pgvector, and pull the models
docker compose up -d
docker logs -f ocr-ollama-init          # wait for the model downloads

# 2. Run the app
mvn spring-boot:run -s .mvn/settings.xml

# 3a. v1 UI:  http://localhost:8080/pi-data-extraction.html
# 3b. v2 API: curl -N -X POST http://localhost:8080/api/v2/pi-extraction/extract/stream -F "file=@invoice.pdf"
```

Running v1 only, without PostgreSQL: start with `SPRING_PROFILES_ACTIVE=lite`.

## Configuration (env vars)

The app reads everything from environment variables (defaults in
[application.yml](src/main/resources/application.yml)). Key knobs:

| Variable | Default | Meaning |
|---|---|---|
| `OLLAMA_MODEL` | `qwen2.5vl:3b` | Vision model (must support images) |
| `OLLAMA_IDLE_TIMEOUT` | `10m` | Fail if Ollama emits nothing this long (CPU image decode is silent; on GPU set `2m`) |
| `OLLAMA_REQUEST_TIMEOUT` | `30m` | Hard cap per extraction |
| `OLLAMA_KEEP_ALIVE` | `30m` | Keep model loaded between jobs |
| `OCR_MAX_IMAGE_DIMENSION` | `1536` | Lower = fewer vision tokens = faster |
| `CALLBACK_AUTH_*` | — | Keycloak token URL / client id / secret for callback delivery |

v2 adds `AI_*` and `POSTGRES_*` variables — see the
[configuration reference](docs/SETUP.md#9-configuration-reference).

## Offline deployment (export tar, run on any server)

Use [`scripts/docker-image-exporter.ps1`](scripts/docker-image-exporter.ps1).
It builds the app image, **bakes the vision model into a bundled Ollama image**
(so the target server never downloads the model), saves everything into one
tar, and writes a ready-to-run folder with exactly four files:
the tar, ONE `docker-compose.yml`, `.env`, `README.md`:

```powershell
.\scripts\docker-image-exporter.ps1 -Version "1.0.0"                  # NVIDIA/CPU image
.\scripts\docker-image-exporter.ps1 -Version "1.0.0" -Gpu rocm        # AMD server
.\scripts\docker-image-exporter.ps1 -Version "1.0.0" -Gpu both        # ship both (bigger tar)
.\scripts\docker-image-exporter.ps1 -Version "1.1.0" -Model "qwen2.5vl:7b"
.\scripts\docker-image-exporter.ps1 -Version "1.0.0" -SkipBuild       # reuse local ocr-app image
.\scripts\docker-image-exporter.ps1 -Version "1.0.0" -SkipModelBake   # ship plain Ollama, pull model on target
```

Output: `scripts\dist\ocr-deploy-<version>\`. On the target server:

```bash
docker load -i ocr-images-1.0.0.tar
# edit .env: set COMPOSE_PROFILES=cpu|gpu|rocm + callback auth
docker compose up -d
```

The **same package works on GPU and non-GPU machines** — the machine type is
one line in `.env` (`COMPOSE_PROFILES=cpu`, `gpu` for NVIDIA, `rocm` for AMD);
no scripts needed on the server.

## Performance notes

- The Ollama log line `decoding image batch 1/3, n_tokens_batch = 1024` is ONE
  image decoded in 3 token-chunks (~3k vision tokens per 1536px page), not 3
  images. On CPU each chunk takes ~30s → run on GPU for interactive latency.
- `keep_alive` is sent with every request so the model stays resident.
- `temperature 0` is used for deterministic extraction.

### GPU sizing (2–4 GB cards supported)

| VRAM | Expected speed | Recommended .env |
|---|---|---|
| CPU only | 5–10 min/doc | — |
| 2–3 GB | 2–5× CPU (partial offload) | `OLLAMA_NUM_CTX=4096`, `OCR_MAX_IMAGE_DIMENSION=1024` |
| 4 GB | ~1–2 min/doc | `OLLAMA_NUM_CTX=4096`, `OCR_MAX_IMAGE_DIMENSION=1280` |
| ≥ 6 GB | 10–30 s/doc (full offload) | defaults |

`OLLAMA_FLASH_ATTENTION=1` + `OLLAMA_KV_CACHE_TYPE=q8_0` are set by default —
they roughly halve KV-cache memory and are harmless on CPU/big GPUs. AMD
(Radeon/ROCm) is supported in the deployment package via
`COMPOSE_PROFILES=rocm` (uses `ollama/ollama:rocm`).

## Other vision models

```powershell
$env:OLLAMA_MODEL="gemma3:4b"
docker compose up -d
```

Small models are faster but extract less reliably. Good local candidates:
`qwen2.5vl:3b` (default), `qwen2.5vl:7b`, `gemma3:4b`.
