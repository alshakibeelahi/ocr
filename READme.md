# OCR / PI Data Extraction (Ollama Vision)

Spring Boot (Java 21) service that turns Proforma Invoice PDFs/images into
structured trade-finance JSON using a local Ollama vision model
(`qwen2.5vl:3b` by default).

- **Job-based**: `POST /api/v1/pi-data-extraction/jobs` returns `202 { jobId }`
  immediately; extraction runs in the background and the result is POSTed to
  your `callbackUrl` (OAuth client-credentials Bearer token).
- **Poll**: `GET /api/v1/pi-data-extraction/jobs/{jobId}` for status/result.
- **UI**: `/pi-data-extraction.html` (upload + poll + summary + raw overview),
  `/` (plain per-page OCR).

## Quick start (local dev)

```powershell
# 1. Start Ollama (CPU) and pull the model
docker compose up -d
docker logs -f ocr-ollama-init          # wait for the model download

# 2. Run the app
mvn spring-boot:run -s .mvn/settings.xml

# 3. Open http://localhost:8080/pi-data-extraction.html
```

## Configuration (env vars)

The app reads everything from environment variables (defaults in
[application.yml](src/main/resources/application.yml)). Key knobs:

| Variable | Default | Meaning |
|---|---|---|
| `OLLAMA_MODEL` | `qwen2.5vl:3b` | Vision model (must support images) |
| `OLLAMA_IDLE_TIMEOUT` | `10m` | Fail if Ollama emits nothing this long (CPU image decode is silent; on GPU set `2m`) |
| `OLLAMA_REQUEST_TIMEOUT` | `30m` | Hard cap per extraction |
| `OLLAMA_KEEP_ALIVE` | `30m` | Keep model loaded between jobs |
| `OLLAMA_NUM_THREAD` | `0` | `0` = Ollama decides. On CPU set to the host's **physical** core count |
| `OCR_MAX_IMAGE_DIMENSION` | `1536` | Lower = fewer vision tokens = faster |
| `OCR_MAX_PAGES` | `3` | Pages sent in one request; × per-page vision tokens must fit `OLLAMA_NUM_CTX` |
| `CALLBACK_AUTH_*` | — | Keycloak token URL / client id / secret for callback delivery |

### Switching model size

Nothing in the app is model-specific, so the size change is one variable. The
ladder is `3b` → `7b` → `32b` → `72b`; there is no 5b.

```powershell
$env:OLLAMA_MODEL="qwen2.5vl:7b"   # up to the larger model
docker compose up -d               # ollama-init pulls it if missing
```

| Model | Resident | Notes |
|---|---|---|
| `qwen2.5vl:3b` | ~3.2 GB | Default. Fastest; weaker on dense tables and multi-column layouts |
| `qwen2.5vl:7b` | ~6.0 GB | ~2× slower than 3b, clearly better extraction. Needs `OLLAMA_IDLE_TIMEOUT=15m`, `OLLAMA_REQUEST_TIMEOUT=45m` on CPU |
| `qwen2.5vl:32b` | ~21 GB | GPU only |

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
.\scripts\docker-image-exporter.ps1 -Version "1.1.0" -Model "qwen2.5vl:3b"
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
| CPU only | see below | `OLLAMA_NUM_THREAD=<physical cores>`, `OCR_RENDER_DPI=150`, `OCR_MAX_IMAGE_DIMENSION=1024`, `OCR_MAX_PAGES=2`, stay on `3b` |
| 2–3 GB | 2–5× CPU (partial offload) | `OLLAMA_NUM_CTX=4096`, `OCR_MAX_IMAGE_DIMENSION=1024` |
| 4 GB | ~1–2 min/doc | `OLLAMA_NUM_CTX=4096`, `OCR_MAX_IMAGE_DIMENSION=1280` |
| ≥ 6 GB | 10–30 s/doc (full offload) | defaults |

### CPU-only hosts

On CPU the render settings decide whether a job finishes at all — every extra
pixel is vision tokens the CPU decodes serially. On a 4-core / 16 GB desktop
with `qwen2.5vl:3b`, expect **roughly 5–12 min per page** at 150 DPI / 1024 px;
`qwen2.5vl:7b` is about 2× that and needs the longer timeouts above.

Three things bite on a 16 GB machine specifically:

- `JAVA_OPTS=-XX:MaxRAMPercentage=75.0` hands the JVM ~12 GB in a container with
  no memory limit, leaving nothing for the model. Set `JAVA_OPTS=-Xmx1g`.
- On Docker Desktop the model lives in the WSL2 VM, which defaults to ~half of
  host RAM. Measured here: 7b is 5.7 GB resident against a ~8 GB cap — it fits,
  barely. Raise it in `%USERPROFILE%\.wslconfig` (`[wsl2]` / `memory=10GB`) then
  `wsl --shutdown`.
- Once the model is evicted, a job goes from minutes to hours. Close the IDE and
  browser, or stay on `qwen2.5vl:3b`.

A non-streaming call to `localhost:11434` from the host dies at exactly 30s
while a large model loads — that is Docker Desktop's port-forwarder dropping an
idle connection, not Ollama. The app is unaffected because it streams.

Setting `OLLAMA_NUM_THREAD` above the physical core count makes generation
*slower* — the threads contend for the same cores. Count cores, not hyperthreads.

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
