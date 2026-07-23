const uploadForm = document.getElementById('uploadForm');
const pdfFileInput = document.getElementById('pdfFile');
const callbackUrlInput = document.getElementById('callbackUrl');
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
const CLIENT_LOG_PREFIX = '[PI-FLOW]';
const POLL_INTERVAL_MS = 3000;

let pollTimer = null;
let pollStartedAt = 0;
let finalResult = null;
let ollamaReady = false;

checkReadiness();

async function checkReadiness() {
    try {
        console.log(`${CLIENT_LOG_PREFIX} Checking Ollama readiness`);
        const response = await fetch('/api/v1/pi-data-extraction/status');
        if (!response.ok) return;
        const status = await response.json();
        console.log(`${CLIENT_LOG_PREFIX} Readiness response`, status);
        ollamaReady = status.modelReady;
        if (!status.modelReady) {
            readinessSection.classList.remove('hidden');
            readinessText.textContent = status.message;
            submitBtn.disabled = true;
        } else {
            readinessSection.classList.add('hidden');
            submitBtn.disabled = false;
        }
    } catch (error) {
        console.error(`${CLIENT_LOG_PREFIX} Readiness check failed`, error);
        // Extraction will report connection errors if Ollama is not reachable.
    }
}

pdfFileInput.addEventListener('change', () => {
    const file = pdfFileInput.files[0];
    fileLabel.textContent = file ? file.name : 'Choose PI PDF or image';
});

uploadForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    const file = pdfFileInput.files[0];
    const callbackUrl = callbackUrlInput.value.trim();
    if (!file || !callbackUrl) return;
    console.log(`${CLIENT_LOG_PREFIX} Starting upload`, {
        fileName: file.name,
        contentType: file.type,
        sizeBytes: file.size,
        callbackUrl
    });

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
    statusText.textContent = 'Uploading document...';
    progressFill.style.width = '5%';

    try {
        const formData = new FormData();
        formData.append('file', file);
        formData.append('callbackUrl', callbackUrl);

        const response = await fetch('/api/v1/pi-data-extraction/jobs', {
            method: 'POST',
            body: formData
        });

        if (!response.ok) {
            const err = await response.json().catch(() => ({ error: 'Upload failed' }));
            throw new Error(err.error || 'Upload failed');
        }

        const { jobId, status } = await response.json();
        console.log(`${CLIENT_LOG_PREFIX} Job accepted`, { jobId, status, callbackUrl });
        statusText.textContent = `Job ${jobId} accepted (${status}). Extracting in background - the callback will also be sent to your URL when done.`;
        progressFill.style.width = '15%';
        startPolling(jobId);
    } catch (err) {
        console.error(`${CLIENT_LOG_PREFIX} Upload flow failed`, err);
        showError(err.message);
        submitBtn.disabled = false;
    }
});

function startPolling(jobId) {
    stopPolling();
    pollStartedAt = Date.now();
    pollTimer = setInterval(() => pollJob(jobId), POLL_INTERVAL_MS);
    pollJob(jobId);
}

function stopPolling() {
    if (pollTimer) {
        clearInterval(pollTimer);
        pollTimer = null;
    }
}

async function pollJob(jobId) {
    try {
        const response = await fetch(`/api/v1/pi-data-extraction/jobs/${jobId}`);
        if (!response.ok) {
            throw new Error(`Job status request failed (HTTP ${response.status})`);
        }
        const job = await response.json();
        console.log(`${CLIENT_LOG_PREFIX} Poll`, { jobId, status: job.status });
        renderJobProgress(job);

        if (job.status === 'COMPLETED') {
            stopPolling();
            finalResult = normalizeFinalResult(job);
            statusText.textContent = 'PI data extraction complete - callback sent';
            progressFill.style.width = '100%';
            renderResult(finalResult);
            downloadBtn.classList.remove('hidden');
            submitBtn.disabled = false;
        } else if (job.status === 'FAILED') {
            stopPolling();
            showError(job.errorMessage || 'Extraction failed');
            submitBtn.disabled = false;
        }
    } catch (err) {
        console.error(`${CLIENT_LOG_PREFIX} Poll failed`, err);
        // Keep polling on transient errors; give up after 45 minutes.
        if (Date.now() - pollStartedAt > 45 * 60 * 1000) {
            stopPolling();
            showError('Timed out waiting for the extraction job to finish.');
            submitBtn.disabled = false;
        }
    }
}

function renderJobProgress(job) {
    if (job.status === 'PROCESSING') {
        const elapsed = Math.round((Date.now() - pollStartedAt) / 1000);
        statusText.textContent = `Extracting trade data... (${elapsed}s elapsed, job ${job.jobId})`;
        // Slowly creep the bar toward 90% while processing.
        const currentPct = parseFloat(progressFill.style.width) || 15;
        progressFill.style.width = `${Math.min(90, currentPct + 2)}%`;
    }
}

function renderResult(result) {
    resultsSection.classList.remove('hidden');
    pagesContainer.innerHTML = '';

    // 1. Structured PI summary cards
    renderExtractionSummary(result.extraction);

    // 2. Overview: raw extracted output exactly as it will be delivered
    const overview = document.createElement('div');
    overview.className = 'page-card';
    overview.innerHTML = `
        <h3>Overview - Raw Extraction</h3>
        <p class="muted">This is the exact extraction payload delivered to the callback URL.</p>
        <pre class="page-text"></pre>
        <div class="page-meta"></div>
    `;
    overview.querySelector('.page-text').textContent = JSON.stringify({
        jobId: result.jobId,
        fileName: result.fileName,
        status: result.status,
        extraction: result.extraction ?? result.rawExtractionText
    }, null, 2);

    const metadata = result.metadata;
    if (metadata) {
        const sourcePages = metadata.sourcePageCount ? `${metadata.sourcePageCount} PDF pages - ` : '';
        overview.querySelector('.page-meta').textContent =
            `${sourcePages}${metadata.width}x${metadata.height}px max - ${metadata.dpi} DPI - ${metadata.durationMs}ms`;
    }
    pagesContainer.appendChild(overview);
}

function renderExtractionSummary(extraction) {
    if (!extraction) {
        const fallback = document.createElement('div');
        fallback.className = 'summary-panel';
        fallback.innerHTML = '<h3>No structured PI data returned</h3><p class="muted">See the raw extraction below.</p>';
        pagesContainer.appendChild(fallback);
        return;
    }

    const summary = document.createElement('div');
    summary.className = 'extraction-summary';
    pagesContainer.appendChild(summary);

    const invoices = getExpectedFormatInvoices(extraction);
    if (invoices.length > 0) {
        invoices.forEach((invoice, index) => {
            summary.appendChild(renderInvoiceCard(invoice, index));
        });
        return;
    }

    const fallback = document.createElement('div');
    fallback.className = 'summary-panel';
    fallback.innerHTML = '<h3>No PI Items Returned</h3><p class="muted">The raw extraction below contains the model output.</p>';
    summary.appendChild(fallback);
}

function getExpectedFormatInvoices(extraction) {
    if (extraction.DEFN_PROFORMA_INVOICE || extraction.DEFN_PROFORMA_INVOICE_HSC) {
        return [{
            unique_id: 'pi-1',
            header: extraction.DEFN_PROFORMA_INVOICE || {},
            items: asArray(extraction.DEFN_PROFORMA_INVOICE_HSC)
        }];
    }

    return asArray(extraction.PROFORMA_INVOICES).map((invoice, index) => ({
        unique_id: invoice.unique_id || `pi-${index + 1}`,
        header: invoice.DEFN_PROFORMA_INVOICE || {},
        items: asArray(invoice.DEFN_PROFORMA_INVOICE_HSC)
    }));
}

function renderInvoiceCard(invoice, index) {
    const card = document.createElement('div');
    card.className = 'summary-panel invoice-panel';
    const uniqueId = invoice.unique_id || `pi-${index + 1}`;
    const header = invoice.header || {};
    const items = asArray(invoice.items);
    const content = header.content || {};
    const visibleNo = header.pi_no || 'No visible PI number';

    card.innerHTML = `
        <div class="invoice-title">
            <h3>${escapeHtml(uniqueId)}: ${escapeHtml(visibleNo)}</h3>
            <span>${escapeHtml(header.status || 'Draft')}</span>
        </div>
        <div class="fact-grid">
            ${renderFact('Applicant', header.applicant_name)}
            ${renderFact('Beneficiary', header.beneficiary_name)}
            ${renderFact('Bank', header.bank_name)}
            ${renderFact('Swift', header.swift)}
            ${renderFact('Currency', header.currency)}
            ${renderFact('Total amount', header.total_amount)}
            ${renderFact('PI date', header.pi_date)}
            ${renderFact('Draft days', header.draft_at_days)}
            ${renderFact('Shipment validity', header.shipment_validity)}
        </div>
        ${renderMiniSection('HSC Items', items.map(renderHscItem))}
        ${renderMiniSection('Payment Terms', renderTextRow('Payment', header.payment_terms))}
        ${renderMiniSection('Remarks', renderTextRow('Remarks', header.remarks))}
        ${renderMiniSection('Extra Content', renderContentRows(content))}
    `;
    return card;
}

function renderHscItem(item) {
    const label = item.description || `Item ${item.sl_no || ''}`.trim();
    const bits = [
        item.hs_code ? `HS ${item.hs_code}` : null,
        item.quantity ? `${item.quantity}${item.quantity_unit ? ` ${item.quantity_unit}` : ''}` : null,
        item.unit_price ? `Unit ${item.unit_price}` : null,
        item.total_amount ? `Amount ${item.total_amount}` : null,
        item.style_no ? `Style ${item.style_no}` : null,
        item.net_weight ? `Net ${item.net_weight}` : null
    ].filter(Boolean);
    return `<li><strong>${escapeHtml(label)}</strong><span>${escapeHtml(bits.join(' | '))}</span></li>`;
}

function renderTextRow(label, value) {
    return value ? [`<li><strong>${escapeHtml(label)}</strong><span>${escapeHtml(value)}</span></li>`] : [];
}

function renderContentRows(content) {
    if (!content || typeof content !== 'object') return [];
    return Object.entries(content)
        .slice(0, 12)
        .map(([key, value]) => `<li><strong>${escapeHtml(key)}</strong><span>${escapeHtml(joinValues(value))}</span></li>`);
}

function renderFact(label, value) {
    return `
        <div class="fact">
            <span>${escapeHtml(label)}</span>
            <strong>${escapeHtml(value ?? '')}</strong>
        </div>
    `;
}

function renderMiniSection(title, rows) {
    const content = Array.isArray(rows) ? rows.filter(Boolean).join('') : rows;
    if (!content) return '';
    return `
        <div class="mini-section">
            <h4>${escapeHtml(title)}</h4>
            <ul>${content}</ul>
        </div>
    `;
}

function joinValues(value) {
    if (!value) return '';
    if (Array.isArray(value)) {
        return value.map((item) => {
            if (item == null) return '';
            if (typeof item === 'object') {
                return item.value || item.text || item.label || item.name || JSON.stringify(item);
            }
            return String(item);
        }).filter(Boolean).join(', ');
    }
    if (typeof value === 'object') {
        return value.value || value.text || value.label || JSON.stringify(value);
    }
    return String(value);
}

function asArray(value) {
    if (!value) return [];
    return Array.isArray(value) ? value : [value];
}

function escapeHtml(value) {
    return String(value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
}

function normalizeFinalResult(job) {
    const page = job.pages?.[0] || {};
    const extractionText = page.text || '';
    return {
        jobId: job.jobId,
        fileName: job.fileName,
        status: job.status,
        createdAt: job.createdAt,
        errorMessage: job.errorMessage,
        metadata: page.metadata || null,
        extraction: parseJsonOrNull(extractionText),
        rawExtractionText: extractionText
    };
}

function parseJsonOrNull(text) {
    try {
        return JSON.parse(text);
    } catch {
        return null;
    }
}

function showError(message) {
    console.error(`${CLIENT_LOG_PREFIX} UI error`, message);
    errorSection.classList.remove('hidden');
    errorText.textContent = message;
}

function resetUi() {
    errorSection.classList.add('hidden');
    resultsSection.classList.add('hidden');
    downloadBtn.classList.add('hidden');
    pagesContainer.innerHTML = '';
    progressFill.style.width = '0%';
    finalResult = null;
    stopPolling();
}

downloadBtn.addEventListener('click', () => {
    if (!finalResult) return;
    const blob = new Blob([JSON.stringify(finalResult, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `pi-data-extraction-${finalResult.jobId}.json`;
    a.click();
    URL.revokeObjectURL(url);
});
