const uploadForm = document.getElementById('uploadForm');
const pdfFileInput = document.getElementById('pdfFile');
const fileLabel = document.getElementById('fileLabel');
const submitBtn = document.getElementById('submitBtn');
const statusSection = document.getElementById('statusSection');
const statusText = document.getElementById('statusText');
const progressFill = document.getElementById('progressFill');
const resultsSection = document.getElementById('resultsSection');
const pagesContainer = document.getElementById('pagesContainer');
const errorSection = document.getElementById('errorSection');
const errorText = document.getElementById('errorText');
const downloadBtn = document.getElementById('downloadBtn');
const readinessSection = document.getElementById('readinessSection');
const readinessText = document.getElementById('readinessText');

let eventSource = null;
let finalResult = null;
let ollamaReady = false;
const pageElements = new Map();

checkReadiness();

async function checkReadiness() {
    try {
        const response = await fetch('/api/ocr/status');
        if (!response.ok) return;
        const status = await response.json();
        ollamaReady = status.modelReady;
        if (!status.modelReady) {
            readinessSection.classList.remove('hidden');
            readinessText.textContent = status.message;
            submitBtn.disabled = true;
        } else {
            readinessSection.classList.add('hidden');
            submitBtn.disabled = false;
        }
    } catch {
        // Ignore status check failures; OCR will report errors if Ollama is down.
    }
}

pdfFileInput.addEventListener('change', () => {
    const file = pdfFileInput.files[0];
    fileLabel.textContent = file ? file.name : 'Choose PDF file';
});

uploadForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    const file = pdfFileInput.files[0];
    if (!file) return;

    if (!ollamaReady) {
        await checkReadiness();
        if (!ollamaReady) {
            showError(readinessText.textContent || 'Ollama model is not ready yet.');
            return;
        }
    }

    resetUi();
    submitBtn.disabled = true;
    statusSection.classList.remove('hidden');

    try {
        const formData = new FormData();
        formData.append('file', file);

        const response = await fetch('/api/ocr/jobs', {
            method: 'POST',
            body: formData
        });

        if (!response.ok) {
            const err = await response.json().catch(() => ({ error: 'Upload failed' }));
            throw new Error(err.error || 'Upload failed');
        }

        const { jobId } = await response.json();
        statusText.textContent = `Job ${jobId} started...`;
        connectStream(jobId);
    } catch (err) {
        showError(err.message);
        submitBtn.disabled = false;
    }
});

function connectStream(jobId) {
    if (eventSource) {
        eventSource.close();
    }

    eventSource = new EventSource(`/api/ocr/jobs/${jobId}/stream`);

    eventSource.onmessage = (event) => {
        const data = JSON.parse(event.data);
        handleStreamEvent(data);
    };

    eventSource.onerror = () => {
        eventSource.close();
        if (!finalResult) {
            showError('Stream connection closed unexpectedly');
        }
        submitBtn.disabled = false;
    };
}

function handleStreamEvent(event) {
    switch (event.type) {
        case 'JOB_STARTED':
            statusText.textContent = `Processing ${event.fileName} (${event.totalPages} pages)...`;
            resultsSection.classList.remove('hidden');
            break;

        case 'PAGE_STARTED':
            ensurePageCard(event.page);
            updateProgress(event.page, event.totalPages);
            statusText.textContent = `Processing page ${event.page} of ${event.totalPages}...`;
            break;

        case 'PAGE_CHUNK':
            appendPageText(event.page, event.chunk);
            break;

        case 'PAGE_COMPLETED':
            setPageComplete(event.page, event.text, event.metadata);
            updateProgress(event.page, event.totalPages || pageElements.size);
            break;

        case 'JOB_COMPLETED':
            finalResult = event.job;
            statusText.textContent = 'OCR complete';
            progressFill.style.width = '100%';
            downloadBtn.classList.remove('hidden');
            if (eventSource) eventSource.close();
            submitBtn.disabled = false;
            break;

        case 'ERROR':
            showError(event.message || 'An error occurred');
            if (eventSource) eventSource.close();
            submitBtn.disabled = false;
            break;
    }
}

function ensurePageCard(pageNum) {
    if (pageElements.has(pageNum)) return;

    const card = document.createElement('div');
    card.className = 'page-card';
    card.id = `page-${pageNum}`;
    card.innerHTML = `
        <h3>Page ${pageNum}</h3>
        <div class="page-text"></div>
        <div class="page-meta"></div>
    `;
    pagesContainer.appendChild(card);
    pageElements.set(pageNum, card);
}

function appendPageText(pageNum, chunk) {
    ensurePageCard(pageNum);
    const textEl = pageElements.get(pageNum).querySelector('.page-text');
    textEl.textContent += chunk;
}

function setPageComplete(pageNum, text, metadata) {
    ensurePageCard(pageNum);
    const card = pageElements.get(pageNum);
    card.querySelector('.page-text').textContent = text;
    if (metadata) {
        card.querySelector('.page-meta').textContent =
            `${metadata.width}x${metadata.height}px · ${metadata.dpi} DPI · ${metadata.durationMs}ms`;
    }
}

function updateProgress(currentPage, totalPages) {
    const pct = totalPages > 0 ? Math.round((currentPage / totalPages) * 100) : 0;
    progressFill.style.width = `${pct}%`;
}

function showError(message) {
    errorSection.classList.remove('hidden');
    errorText.textContent = message;
}

function resetUi() {
    errorSection.classList.add('hidden');
    resultsSection.classList.add('hidden');
    downloadBtn.classList.add('hidden');
    pagesContainer.innerHTML = '';
    pageElements.clear();
    progressFill.style.width = '0%';
    finalResult = null;
    if (eventSource) {
        eventSource.close();
        eventSource = null;
    }
}

downloadBtn.addEventListener('click', () => {
    if (!finalResult) return;
    const blob = new Blob([JSON.stringify(finalResult, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `ocr-result-${finalResult.jobId}.json`;
    a.click();
    URL.revokeObjectURL(url);
});
