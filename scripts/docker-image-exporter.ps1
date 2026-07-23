<#
.SYNOPSIS
    Build, export and package the OCR / PI Data Extraction Docker stack.

.DESCRIPTION
    Builds the ocr-app image, optionally bakes the Ollama vision model INTO a
    custom Ollama image (so the remote/offline server never downloads the model),
    exports everything as one versioned tar, and assembles a self-contained
    deployment package containing exactly:

        ocr-images-<version>.tar   - all Docker images
        docker-compose.yml         - ONE combined compose file
        .env                       - all configuration (incl. CPU/GPU mode)
        README.md                  - setup guide

    GPU vs CPU is selected in .env via COMPOSE_PROFILES=cpu|gpu|rocm.
    Deployment on the target server is just:
        docker load -i ocr-images-<version>.tar
        edit .env
        docker compose up -d

.PARAMETER Version
    Image version tag. Default: 1.0.0.

.PARAMETER Model
    Ollama vision model to bake/ship. Default: qwen2.5vl:3b.

.PARAMETER Gpu
    Which Ollama base image(s) to ship: nvidia (CUDA image, also runs CPU),
    rocm (AMD image), or both. Default: nvidia.
    NOTE: 'both' makes the tar much larger (two Ollama images).

.PARAMETER OutputDir
    Where to write the package. Default: scripts\dist.

.PARAMETER SkipBuild
    Re-use existing local ocr-app image; skip the docker build step.

.PARAMETER SkipModelBake
    Do NOT bake the model into the Ollama image(s). Plain images are exported
    and the target server pulls the model itself (needs internet).

.EXAMPLE
    .\scripts\docker-image-exporter.ps1 -Version "1.0.0"
    .\scripts\docker-image-exporter.ps1 -Version "1.1.0" -Model "qwen2.5vl:7b"
    .\scripts\docker-image-exporter.ps1 -Version "1.0.0" -Gpu rocm
    .\scripts\docker-image-exporter.ps1 -Version "1.0.0" -Gpu both
    .\scripts\docker-image-exporter.ps1 -Version "1.0.0" -SkipBuild
    .\scripts\docker-image-exporter.ps1 -Version "1.0.0" -SkipModelBake
#>

param(
    [string] $Version   = "1.0.0",
    [string] $Model     = "qwen2.5vl:3b",
    [ValidateSet("nvidia", "rocm", "both")]
    [string] $Gpu       = "nvidia",
    [string] $OutputDir = "",
    [switch] $SkipBuild,
    [switch] $SkipModelBake
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# ---------- paths -------------------------------------------------------------
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$repoRoot  = (Resolve-Path (Join-Path $scriptDir "..")).Path
if ([string]::IsNullOrWhiteSpace($OutputDir)) { $OutputDir = Join-Path $scriptDir "dist" }
if (-not (Test-Path $OutputDir)) { New-Item -ItemType Directory -Path $OutputDir | Out-Null }

# ---------- helpers -----------------------------------------------------------
function Fail([string]$msg)  { Write-Error $msg; exit 1 }
function Step([string]$text) { Write-Host "" ; Write-Host $text -ForegroundColor Yellow }
function OK  ([string]$text) { Write-Host "  OK: $text" -ForegroundColor Green }
function Assert-Exit([string]$ctx) {
    if ($LASTEXITCODE -ne 0) { Fail "FAILED: $ctx (exit code $LASTEXITCODE)" }
}
# Runs a native command with all output suppressed WITHOUT tripping
# $ErrorActionPreference=Stop on stderr (PS 5.1 NativeCommandError). Returns exit code.
function Invoke-Quiet {
    param([Parameter(Mandatory)][string[]] $CommandArgs)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "SilentlyContinue"
    try {
        & $CommandArgs[0] @($CommandArgs[1..($CommandArgs.Count-1)]) *>$null
    } catch {}
    $ErrorActionPreference = $prev
    return $LASTEXITCODE
}

# Bakes $Model into $baseImage and tags the result $bundledTag.
function Bake-Model([string]$baseImage, [string]$bundledTag) {
    Step "  Baking model '$Model' into $baseImage -> $bundledTag ..."
    docker pull $baseImage
    Assert-Exit "docker pull $baseImage"

    $bakeName = "ocr-ollama-bake"
    Invoke-Quiet @("docker", "rm", "-f", $bakeName) | Out-Null

    docker run -d --name $bakeName --entrypoint "/bin/sh" $baseImage -c "ollama serve & sleep 4; ollama pull $Model; touch /tmp/pull-done; tail -f /dev/null"
    Assert-Exit "start bake container"

    Write-Host "  Waiting for model pull to finish (first run downloads the model)..." -ForegroundColor DarkGray
    $deadline = (Get-Date).AddMinutes(60)
    while ($true) {
        $code = Invoke-Quiet @("docker", "exec", $bakeName, "test", "-f", "/tmp/pull-done")
        if ($code -eq 0) { break }
        if ((Get-Date) -gt $deadline) {
            docker logs --tail 20 $bakeName
            docker rm -f $bakeName | Out-Null
            Fail "Model pull did not finish within 60 minutes."
        }
        Start-Sleep -Seconds 10
    }

    $listOutput = docker exec $bakeName ollama list
    if (-not ($listOutput -match [Regex]::Escape(($Model -split ':')[0]))) {
        docker logs --tail 40 $bakeName
        docker rm -f $bakeName | Out-Null
        Fail "Model '$Model' not present after pull. See container logs above."
    }

    docker stop $bakeName | Out-Null
    docker commit --change 'ENTRYPOINT ["/bin/ollama"]' --change 'CMD ["serve"]' $bakeName $bundledTag
    Assert-Exit "docker commit $bundledTag"
    docker rm -f $bakeName | Out-Null
    OK "$bundledTag (contains $Model)"
}

# ---------- image plan --------------------------------------------------------
$appImage = "ocr-app"

$wantNvidia = ($Gpu -eq "nvidia") -or ($Gpu -eq "both")
$wantRocm   = ($Gpu -eq "rocm")   -or ($Gpu -eq "both")

$nvidiaBase = "ollama/ollama:latest"   # CUDA build; also works on plain CPU
$rocmBase   = "ollama/ollama:rocm"

if ($SkipModelBake) {
    $nvidiaTag = $nvidiaBase
    $rocmTag   = $rocmBase
} else {
    $nvidiaTag = "ocr-ollama-bundled:${Version}"
    $rocmTag   = "ocr-ollama-bundled-rocm:${Version}"
}

# Image used for cpu/gpu profiles and for the rocm profile.
$ollamaImageDefault = if ($wantNvidia) { $nvidiaTag } else { $rocmTag }
$ollamaImageRocm    = if ($wantRocm)   { $rocmTag }   else { "ollama/ollama:rocm" }
$defaultProfile     = if ($wantNvidia) { "gpu" } else { "rocm" }

Write-Host ""
Write-Host "========================================================" -ForegroundColor Cyan
Write-Host "  OCR / PI Data Extraction Image Exporter" -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Cyan
Write-Host "  Version    : $Version"
Write-Host "  Model      : $Model"
Write-Host "  GPU flavor : $Gpu"
Write-Host "  Bake model : $(-not $SkipModelBake)"
Write-Host "  Output     : $OutputDir"
Write-Host ""

# ==========================================================================
# STEP 1 - BUILD ocr-app
# ==========================================================================
if ($SkipBuild) {
    Write-Host "[SKIP] ocr-app build skipped (-SkipBuild)." -ForegroundColor DarkGray
    $code = Invoke-Quiet @("docker", "image", "inspect", "${appImage}:latest")
    if ($code -ne 0) { Fail "Image ${appImage}:latest not found. Remove -SkipBuild to build it." }
} else {
    Step "[1] Building ocr-app image ..."
    docker build -t "${appImage}:latest" $repoRoot
    Assert-Exit "docker build ocr-app"
    OK "${appImage}:latest"
}

# ==========================================================================
# STEP 2 - PREPARE OLLAMA IMAGE(S)
# ==========================================================================
Step "[2] Preparing Ollama image(s) ..."
if ($SkipModelBake) {
    if ($wantNvidia) { docker pull $nvidiaBase; Assert-Exit "docker pull $nvidiaBase"; OK "$nvidiaBase (model pulled on target)" }
    if ($wantRocm)   { docker pull $rocmBase;   Assert-Exit "docker pull $rocmBase";   OK "$rocmBase (model pulled on target)" }
} else {
    if ($wantNvidia) { Bake-Model $nvidiaBase $nvidiaTag }
    if ($wantRocm)   { Bake-Model $rocmBase   $rocmTag }
}

# ==========================================================================
# STEP 3 - TAG + SAVE TAR
# ==========================================================================
Step "[3] Tagging and exporting tar ..."
docker tag "${appImage}:latest" "${appImage}:${Version}"
Assert-Exit "tag ocr-app"

$tags = @("${appImage}:${Version}")
if ($wantNvidia) { $tags += $nvidiaTag }
if ($wantRocm)   { $tags += $rocmTag }

$tarFile = Join-Path $OutputDir "ocr-images-${Version}.tar"
Write-Host "  Saving: $($tags -join ', ')"
Write-Host "  (This can take several minutes - bundled model images are large)" -ForegroundColor DarkGray
docker save $tags -o $tarFile
Assert-Exit "docker save"
$gb = [Math]::Round((Get-Item $tarFile).Length / 1GB, 2)
OK "Saved $tarFile ($gb GB)"

# ==========================================================================
# STEP 4 - ASSEMBLE DEPLOYMENT PACKAGE (tar + compose + .env + README only)
# ==========================================================================
$pkgDir = Join-Path $OutputDir "ocr-deploy-${Version}"
Step "[4] Assembling deployment package: $pkgDir"
if (Test-Path $pkgDir) { Remove-Item $pkgDir -Recurse -Force }
New-Item -ItemType Directory -Path $pkgDir | Out-Null

Move-Item $tarFile (Join-Path $pkgDir "ocr-images-${Version}.tar")

# ---- ONE combined docker-compose.yml -----------------------------------------
# CPU/GPU selection happens in .env via COMPOSE_PROFILES (cpu | gpu | rocm).
# The three ollama variants share the container name and data volume; the
# profile decides which one starts. YAML anchors keep them in sync.

$initService = @"

  # Pulls the model on first start (only needed because the model is NOT baked in).
  ollama-init:
    image: `${OLLAMA_IMAGE}
    container_name: ocr-ollama-init
    environment:
      OLLAMA_HOST: http://ocr-ollama-host:11434
      OLLAMA_MODEL: `${OLLAMA_MODEL}
    networks: [ocr]
    entrypoint: ["/bin/sh", "-c"]
    command:
      - 'sleep 5; if ollama list | grep -q "`$`$OLLAMA_MODEL"; then echo "Model already present."; else ollama pull "`$`$OLLAMA_MODEL"; fi && ollama list'
    restart: "no"
"@
# When the model is baked into the image, no init/pull service is needed.
if (-not $SkipModelBake) { $initService = "" }

$compose = @"
# OCR / PI Data Extraction deployment (v${Version})
#
#   1. docker load -i ocr-images-${Version}.tar        (once per version)
#   2. edit .env  ->  COMPOSE_PROFILES=cpu | gpu | rocm  + callback auth
#   3. docker compose up -d
#
# Exactly one ollama-* service starts, chosen by COMPOSE_PROFILES in .env.

x-ollama-common: &ollama-common
  container_name: ocr-ollama
  volumes:
    - ollama_data:/root/.ollama
  environment: &ollama-env
    OLLAMA_KEEP_ALIVE: `${OLLAMA_KEEP_ALIVE:-30m}
    OLLAMA_NUM_PARALLEL: `${OLLAMA_NUM_PARALLEL:-1}
    OLLAMA_MAX_LOADED_MODELS: `${OLLAMA_MAX_LOADED_MODELS:-1}
    # Low-VRAM optimizations (2-4 GB cards); harmless on big GPUs and CPU.
    OLLAMA_FLASH_ATTENTION: `${OLLAMA_FLASH_ATTENTION:-1}
    OLLAMA_KV_CACHE_TYPE: `${OLLAMA_KV_CACHE_TYPE:-q8_0}
  networks:
    ocr:
      aliases: [ocr-ollama-host]
  healthcheck:
    test: ["CMD", "ollama", "list"]
    interval: 10s
    timeout: 5s
    retries: 12
    start_period: 30s
  restart: unless-stopped

services:

  # ---- CPU mode (COMPOSE_PROFILES=cpu) --------------------------------------
  ollama-cpu:
    <<: *ollama-common
    profiles: [cpu]
    image: `${OLLAMA_IMAGE}

  # ---- NVIDIA GPU mode (COMPOSE_PROFILES=gpu) -------------------------------
  # Requires NVIDIA driver + nvidia-container-toolkit on the host.
  ollama-gpu:
    <<: *ollama-common
    profiles: [gpu]
    image: `${OLLAMA_IMAGE}
    deploy:
      resources:
        reservations:
          devices:
            - driver: nvidia
              count: `${GPU_COUNT:-all}
              capabilities: [gpu]

  # ---- AMD GPU mode (COMPOSE_PROFILES=rocm) ---------------------------------
  # Requires amdgpu kernel driver on the host (Linux only).
  # For older Radeon cards set HSA_OVERRIDE_GFX_VERSION in .env
  # (e.g. 10.3.0 for RX 6000 series).
  ollama-rocm:
    <<: *ollama-common
    profiles: [rocm]
    image: `${OLLAMA_IMAGE_ROCM}
    devices:
      - /dev/kfd
      - /dev/dri
    environment:
      <<: *ollama-env
      HSA_OVERRIDE_GFX_VERSION: `${HSA_OVERRIDE_GFX_VERSION:-}
$initService

  ocr-app:
    image: ocr-app:`${VERSION}
    container_name: ocr-app
    ports:
      - "`${OCR_APP_PORT:-8080}:8080"
    environment:
      OLLAMA_BASE_URL: http://ocr-ollama-host:11434
      OLLAMA_MODEL: `${OLLAMA_MODEL}
      OLLAMA_IDLE_TIMEOUT: `${OLLAMA_IDLE_TIMEOUT:-10m}
      OLLAMA_REQUEST_TIMEOUT: `${OLLAMA_REQUEST_TIMEOUT:-30m}
      OLLAMA_KEEP_ALIVE: `${OLLAMA_KEEP_ALIVE:-30m}
      OLLAMA_NUM_CTX: `${OLLAMA_NUM_CTX:-8192}
      OCR_RENDER_DPI: `${OCR_RENDER_DPI:-200}
      OCR_MAX_IMAGE_DIMENSION: `${OCR_MAX_IMAGE_DIMENSION:-1536}
      MAX_FILE_SIZE: `${MAX_FILE_SIZE:-50MB}
      CALLBACK_AUTH_TOKEN_URL: `${CALLBACK_AUTH_TOKEN_URL:-}
      CALLBACK_AUTH_CLIENT_ID: `${CALLBACK_AUTH_CLIENT_ID:-}
      CALLBACK_AUTH_CLIENT_SECRET: `${CALLBACK_AUTH_CLIENT_SECRET:-}
      CALLBACK_AUTH_SCOPE: `${CALLBACK_AUTH_SCOPE:-}
      JAVA_OPTS: `${JAVA_OPTS:--XX:MaxRAMPercentage=75.0}
    networks: [ocr]
    restart: unless-stopped

networks:
  ocr:
    driver: bridge

volumes:
  ollama_data:
"@
Set-Content (Join-Path $pkgDir "docker-compose.yml") $compose -Encoding UTF8
OK "docker-compose.yml (single file; mode via COMPOSE_PROFILES in .env)"

# ---- .env --------------------------------------------------------------------
$envFile = @"
# =============================================================================
# OCR / PI Data Extraction - deployment configuration (v${Version})
# =============================================================================

# --- RUN MODE (the ONLY switch between CPU and GPU) --------------------------
#   cpu   - no GPU (slow: minutes per document)
#   gpu   - NVIDIA GPU (needs nvidia driver + nvidia-container-toolkit)
#   rocm  - AMD GPU / Radeon (Linux host with amdgpu driver)
COMPOSE_PROFILES=${defaultProfile}

VERSION=${Version}

# --- OLLAMA IMAGES -----------------------------------------------------------
# OLLAMA_IMAGE      is used by cpu and gpu (NVIDIA) modes - the standard Ollama
#                   image contains the CUDA runtime, so one image covers both.
# OLLAMA_IMAGE_ROCM is used only by rocm (AMD/Radeon) mode - AMD needs a
#                   different Ollama build with the ROCm runtime.
# Normally do NOT change these: they are set to exactly what is inside
# ocr-images-${Version}.tar. Possible values:
#   ocr-ollama-bundled:<version>       - bundled image, model baked in (offline)
#   ocr-ollama-bundled-rocm:<version>  - bundled AMD image, model baked in
#   ollama/ollama:latest               - plain NVIDIA/CPU image (internet pull of model)
#   ollama/ollama:rocm                 - plain AMD image (internet pull of model)
#   ollama/ollama:<tag>                - any specific Ollama release, e.g. 0.5.7
OLLAMA_IMAGE=${ollamaImageDefault}
OLLAMA_IMAGE_ROCM=${ollamaImageRocm}

# Ports
OCR_APP_PORT=8080

# Vision model (already inside the Ollama image when baked)
OLLAMA_MODEL=${Model}

# --- GPU sizing guide --------------------------------------------------------
# >= 6 GB VRAM : defaults below are fine (full offload).
# 4 GB VRAM    : OLLAMA_NUM_CTX=4096, OCR_MAX_IMAGE_DIMENSION=1280
# 2-3 GB VRAM  : OLLAMA_NUM_CTX=4096, OCR_MAX_IMAGE_DIMENSION=1024
#                (model partially offloads to CPU - still 2-5x faster than CPU)
# -----------------------------------------------------------------------------

# Ollama behaviour
OLLAMA_KEEP_ALIVE=30m
OLLAMA_NUM_PARALLEL=1
OLLAMA_MAX_LOADED_MODELS=1
OLLAMA_NUM_CTX=8192
# Flash attention + q8 KV cache ~halve KV memory (important on 2-4 GB cards)
OLLAMA_FLASH_ATTENTION=1
OLLAMA_KV_CACHE_TYPE=q8_0
# AMD only: override for unsupported Radeon models (e.g. 10.3.0 for RX 6000)
HSA_OVERRIDE_GFX_VERSION=

# App timeouts (on GPU you can lower OLLAMA_IDLE_TIMEOUT to 2m)
OLLAMA_IDLE_TIMEOUT=10m
OLLAMA_REQUEST_TIMEOUT=30m

# NVIDIA GPU count for gpu mode
GPU_COUNT=all

# Image preprocessing (lower dimension = fewer vision tokens = faster + less VRAM)
OCR_RENDER_DPI=200
OCR_MAX_IMAGE_DIMENSION=1536

# Upload limit
MAX_FILE_SIZE=50MB

# Callback OAuth (client-credentials) - REQUIRED for callback delivery.
# NOTE: callback DESTINATIONS are per-job (each API request carries its own
# callbackUrl); only the token endpoint is configured here.
CALLBACK_AUTH_TOKEN_URL=http://192.168.10.56:9080/realms/MicroCube_dev/protocol/openid-connect/token
CALLBACK_AUTH_CLIENT_ID=Swift_BE
CALLBACK_AUTH_CLIENT_SECRET=change-me
CALLBACK_AUTH_SCOPE=

# JVM
JAVA_OPTS=-XX:MaxRAMPercentage=75.0
"@
Set-Content (Join-Path $pkgDir ".env") $envFile -Encoding UTF8
OK ".env"

# ---- README ------------------------------------------------------------------
$modelNote = if ($SkipModelBake) {
    "The Ollama image(s) in the tar are PLAIN images. On first start the ollama-init service pulls '$Model' from the internet - the target server needs internet access."
} else {
    "The Ollama image(s) in the tar ALREADY CONTAIN the '$Model' model. The target server needs NO internet access and never re-downloads the model."
}
$imagesList = $tags -join ", "
$rocmNote = if ($wantRocm) {
    "The ROCm (AMD) Ollama image IS included in the tar (rocm mode works offline)."
} else {
    "The ROCm (AMD) Ollama image is NOT in the tar (exported with -Gpu nvidia). rocm mode would pull ollama/ollama:rocm from the internet; for an offline AMD server rebuild the package with -Gpu rocm or -Gpu both."
}

$readme = @"
# OCR / PI Data Extraction - Deployment v${Version}

Self-contained package. Contents:

    ocr-images-${Version}.tar    Docker images: $imagesList
    docker-compose.yml           the stack (one file; CPU/GPU chosen in .env)
    .env                         all configuration
    README.md                    this file

$modelNote
$rocmNote

## Prerequisites (target server)
  - Docker Engine 24+ and docker compose v2
  - NVIDIA GPU: NVIDIA driver + nvidia-container-toolkit
  - AMD GPU:    amdgpu kernel driver (Linux, ROCm-capable card)
  - No GPU:     works too, just slow (minutes per document)

## Setup
  1. Transfer this folder to the server:
         scp -r ocr-deploy-${Version}/ user@<server-ip>:~/ocr/

  2. Load the images (once per version - the tar is large):
         cd ~/ocr/ocr-deploy-${Version}
         docker load -i ocr-images-${Version}.tar

  3. Edit .env:
         COMPOSE_PROFILES=cpu|gpu|rocm     <- pick the machine type (ONLY switch needed)
         CALLBACK_AUTH_TOKEN_URL / CLIENT_ID / CLIENT_SECRET
         For 2-4 GB GPUs apply the sizing guide inside .env.

  4. Start:
         docker compose up -d

  5. Verify:
         docker compose ps
         docker logs ocr-ollama 2>&1 | grep -i "inference compute"   # shows GPU when active
         curl http://localhost:8080/api/v1/pi-data-extraction/health

  6. Use:
         UI:  http://<server>:8080/pi-data-extraction.html
         API: POST http://<server>:8080/api/v1/pi-data-extraction/jobs
              multipart form: file=<pdf|png|jpg>, callbackUrl=<your endpoint>
              -> 202 { "jobId": "...", "status": "PROCESSING" }
              When done the app POSTs the result JSON to callbackUrl with a
              Bearer token fetched from CALLBACK_AUTH_TOKEN_URL.
         Poll: GET http://<server>:8080/api/v1/pi-data-extraction/jobs/{jobId}

## Machine with GPU vs without
    Same package for both. Only COMPOSE_PROFILES in .env differs:
        no GPU        ->  COMPOSE_PROFILES=cpu
        NVIDIA GPU    ->  COMPOSE_PROFILES=gpu
        AMD/Radeon    ->  COMPOSE_PROFILES=rocm
    Then: docker compose down && docker compose up -d

## GPU performance expectations
    CPU only          : image decode ~90s/page + slow generation -> 5-10 min/doc
    2-3 GB VRAM       : partial offload -> roughly 2-5x faster than CPU
    4 GB VRAM         : mostly offloaded -> ~1-2 min/doc
    >= 6 GB VRAM      : full offload -> typically 10-30 s/doc
    Small-VRAM tuning (flash attention + q8 KV cache are already on):
      - lower OLLAMA_NUM_CTX to 4096
      - lower OCR_MAX_IMAGE_DIMENSION to 1280/1024 (fewer vision tokens)

## Operations
    Stop:    docker compose down
    Start:   docker compose up -d
    Logs:    docker compose logs -f ocr-app
    Status:  docker compose ps

## Upgrade
    1. docker load -i ocr-images-<new>.tar
    2. Edit .env: VERSION=<new>, OLLAMA_IMAGE=<new bundled tag>
    3. docker compose up -d --force-recreate

## Changing the model later
    - Preferred: rebuild the package with  -Model "<name>"  and redeploy.
    - Or on a server WITH internet: docker exec ocr-ollama ollama pull <name>,
      set OLLAMA_MODEL in .env, then docker compose up -d

## Notes
    - The ollama_data volume is seeded from the image on FIRST start (Docker
      copies the baked-in model into the empty named volume automatically).
    - Callback URLs are EXTERNAL and per-job: each API request carries its own
      callbackUrl. Only the OAuth token endpoint lives in .env. Containers
      reach external hosts through normal Docker NAT - no extra config needed.
      If callbacks fail, check host firewall/outbound rules and test from
      inside the container:
          docker exec ocr-app sh -c 'wget -qO- http://<callback-host>:<port>/ || true'

## Troubleshooting
### Extraction times out / "no output for PT10M"
    Ollama is decoding on CPU. Confirm the GPU is active:
        docker logs ocr-ollama 2>&1 | grep -i "inference compute"
    NVIDIA: install nvidia-container-toolkit, set COMPOSE_PROFILES=gpu
    AMD:    set COMPOSE_PROFILES=rocm; for older Radeon cards also set
            HSA_OVERRIDE_GFX_VERSION in .env (e.g. 10.3.0 for RX 6000 series)
### "could not select device driver nvidia" on startup
    The host has no working NVIDIA container toolkit. Either install it, or
    set COMPOSE_PROFILES=cpu in .env and start again.
### Callback fails
    Check CALLBACK_AUTH_* in .env; test the token URL from inside the container.
### Model not found (HTTP 404 from Ollama)
    docker exec ocr-ollama ollama list   # must show ${Model}
"@
Set-Content (Join-Path $pkgDir "README.md") $readme -Encoding UTF8
OK "README.md"

Write-Host ""
Write-Host "  Package contents:"
Get-ChildItem $pkgDir | ForEach-Object {
    $size = if ($_.PSIsContainer) { "" } else { " ($([Math]::Round($_.Length / 1MB, 1)) MB)" }
    Write-Host "    $($_.Name)$size"
}

Write-Host ""
Write-Host "========================================================" -ForegroundColor Cyan
Write-Host "  DONE" -ForegroundColor Green
Write-Host "========================================================" -ForegroundColor Cyan
Write-Host "  Package : $pkgDir"
Write-Host "  Transfer the folder to the target server and follow README.md"
Write-Host ""
