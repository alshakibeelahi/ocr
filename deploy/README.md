# Production Deployment (Docker Compose)

Runs the **whole stack in Docker**: the OCR app + Ollama. CPU vs GPU is a
single switch in `.env` (`COMPOSE_PROFILES=cpu | gpu | rocm`).

```
deploy/
  docker-compose.yml   the stack (one file; CPU/GPU chosen in .env)
  .env.example         configuration template  ->  copy to .env
  README.md            this file
```

> For **offline servers** (no internet), don't use this folder directly —
> build a self-contained package with `scripts\docker-image-exporter.ps1`,
> which bakes the model into the images and produces a tar + compose + .env.

## Prerequisites
  - Docker Engine 24+ and docker compose v2
  - NVIDIA GPU: NVIDIA driver + nvidia-container-toolkit
  - AMD GPU:    amdgpu kernel driver (Linux, ROCm-capable card)
  - No GPU:     works too, just slow (minutes per document)

## Setup

1. Copy and edit the configuration:

   ```sh
   cd deploy
   cp .env.example .env
   ```

   In `.env` set:
   - `COMPOSE_PROFILES=cpu|gpu|rocm`  ← pick the machine type (ONLY switch needed)
   - `CALLBACK_AUTH_TOKEN_URL` / `CLIENT_ID` / `CLIENT_SECRET`
   - For 2–4 GB GPUs apply the sizing guide inside `.env`.

2. Start (builds the app image from the repo on first run):

   ```sh
   docker compose up -d --build
   ```

   On first start `ollama-init` pulls the vision model (needs internet once;
   it is cached in the `ollama_data` volume afterwards).

3. Verify:

   ```sh
   docker compose ps
   docker logs ocr-ollama 2>&1 | grep -i "inference compute"   # shows GPU when active
   curl http://localhost:8080/api/v1/pi-data-extraction/health
   ```

4. Use:
   - UI:  `http://<server>:8080/pi-data-extraction.html`
   - API: `POST http://<server>:8080/api/v1/pi-data-extraction/jobs`
     (multipart form: `file=<pdf|png|jpg>`, `callbackUrl=<your endpoint>`)
     → `202 { "jobId": "...", "status": "PROCESSING" }`
   - Poll: `GET http://<server>:8080/api/v1/pi-data-extraction/jobs/{jobId}`

## Machine with GPU vs without

Same compose file for all machines. Only `COMPOSE_PROFILES` in `.env` differs:

| Machine        | COMPOSE_PROFILES |
|----------------|------------------|
| no GPU         | `cpu`            |
| NVIDIA GPU     | `gpu`            |
| AMD / Radeon   | `rocm`           |

After changing it: `docker compose down && docker compose up -d`

## GPU performance expectations

    CPU only          : image decode ~90s/page + slow generation -> 5-10 min/doc
    2-3 GB VRAM       : partial offload -> roughly 2-5x faster than CPU
    4 GB VRAM         : mostly offloaded -> ~1-2 min/doc
    >= 6 GB VRAM      : full offload -> typically 10-30 s/doc

Small-VRAM tuning (flash attention + q8 KV cache are already on):
  - lower `OLLAMA_NUM_CTX` to 4096
  - lower `OCR_MAX_IMAGE_DIMENSION` to 1280/1024 (fewer vision tokens)

## Operations

    Stop:     docker compose down
    Start:    docker compose up -d
    Rebuild:  docker compose up -d --build ocr-app
    Logs:     docker compose logs -f ocr-app
    Status:   docker compose ps

## Troubleshooting

**Extraction times out / "no output for PT10M"** — Ollama is decoding on CPU.
Confirm the GPU is active:
`docker logs ocr-ollama 2>&1 | grep -i "inference compute"`
NVIDIA: install nvidia-container-toolkit, set `COMPOSE_PROFILES=gpu`.
AMD: set `COMPOSE_PROFILES=rocm`; for older Radeon cards also set
`HSA_OVERRIDE_GFX_VERSION` in `.env` (e.g. `10.3.0` for RX 6000 series).

**"could not select device driver nvidia" on startup** — the host has no
working NVIDIA container toolkit. Install it, or set `COMPOSE_PROFILES=cpu`.

**Callback fails** — check `CALLBACK_AUTH_*` in `.env`; test the token URL
from inside the container:
`docker exec ocr-app sh -c 'wget -qO- <token-url> || true'`

**Model not found (HTTP 404 from Ollama)** —
`docker exec ocr-ollama ollama list` must show the model; re-run
`docker compose up -d` to let `ollama-init` pull it again.
