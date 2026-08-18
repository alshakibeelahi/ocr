/*
 * v2 UI: synchronous, RAG-grounded PI extraction.
 *
 * Two things here are not in the v1 page and are the reason it exists:
 *  - the extraction is streamed, so a multi-minute read shows progress instead of a spinner;
 *  - every extracted value is rendered with its verification state, so a reviewer can see at a
 *    glance which numbers were found in the document and which were not.
 */

const API = '/api/v2/pi-extraction';
const KNOWLEDGE_API = '/api/v2/knowledge';
const LOG = '[PI-V2]';

const el = (id) => document.getElementById(id);

const uploadForm = el('uploadForm');
const pdfFileInput = el('pdfFile');
const fileLabel = el('fileLabel');
const providerSelect = el('providerSelect');
const modelInput = el('modelInput');
const submitBtn = el('submitBtn');
const cancelBtn = el('cancelBtn');

const healthSection = el('healthSection');
const healthChip = el('healthChip');
const healthSummary = el('healthSummary');
const healthDetail = el('healthDetail');

const progressSection = el('progressSection');
const progressFill = el('progressFill');
const statusText = el('statusText');
const stageList = el('stageList');
const streamDetails = el('streamDetails');
const streamOutput = el('streamOutput');

const resultsSection = el('resultsSection');
const runMeta = el('runMeta');
const verificationPanel = el('verificationPanel');
const invoicesContainer = el('invoicesContainer');
const knowledgeUsedPanel = el('knowledgeUsedPanel');
const rawJson = el('rawJson');
const downloadBtn = el('downloadBtn');

const errorSection = el('errorSection');
const errorText = el('errorText');

const knowledgeList = el('knowledgeList');
const knowledgeCount = el('knowledgeCount');

/** Ordered so the progress bar can advance without inventing a percentage. */
const STAGES = ['ingest', 'signature', 'retrieve', 'extract', 'normalise', 'verify'];
const STAGE_LABELS = {
    ingest: 'Reading document',
    signature: 'Identifying layout',
    retrieve: 'Selecting rules',
    extract: 'Reading with the model',
    normalise: 'Applying field rules',
    verify: 'Checking against the document',
    cached: 'Served from cache'
};

let lastResult = null;
let inFlight = null;

// ---------------------------------------------------------------- readiness

checkHealth();
el('refreshHealthBtn').addEventListener('click', checkHealth);

async function checkHealth() {
    healthSection.classList.remove('hidden');
    healthChip.className = 'chip';
    healthChip.textContent = 'checking';
    try {
        const [health, providers] = await Promise.all([
            fetch(`${API}/health`).then((r) => r.json()),
            fetch(`${API}/providers`).then((r) => r.json())
        ]);
        console.log(`${LOG} health`, health);

        const up = health.status === 'UP';
        healthChip.className = `chip ${up ? 'chip-ok' : 'chip-warn'}`;
        healthChip.textContent = health.status;

        const available = health.availableProviders || [];
        healthSummary.textContent =
            `${available.length} provider${available.length === 1 ? '' : 's'} ready` +
            ` (default ${health.defaultProvider}) - ` +
            `${health.knowledgeIndexed}/${health.knowledgeDocuments} knowledge entries indexed - ` +
            `prompt ${health.promptVersion}`;

        const problems = [];
        if (available.length === 0) {
            problems.push('No chat provider is usable. Check the provider configuration.');
        }
        if (!health.knowledgeStoreReachable) {
            problems.push('The knowledge store is unreachable - PostgreSQL may be down.');
        } else if (health.knowledgeDocuments === 0) {
            problems.push('The knowledge base is empty. Seeding usually fails because the embedding '
                + `model '${health.embeddingModel}' has not been pulled yet.`);
        } else if (health.knowledgeIndexed < health.knowledgeDocuments) {
            problems.push('Some knowledge entries have no vectors behind them, so retrieval is '
                + 'returning less than it should. Use Reindex below.');
        }
        healthDetail.textContent = problems.join(' ');
        healthDetail.classList.toggle('hidden', problems.length === 0);

        populateProviders(providers, health.defaultProvider);
        submitBtn.disabled = available.length === 0;
        knowledgeCount.textContent = `- ${health.knowledgeDocuments} entries`;
    } catch (error) {
        console.error(`${LOG} health check failed`, error);
        healthChip.className = 'chip chip-warn';
        healthChip.textContent = 'unreachable';
        healthSummary.textContent = 'Could not reach the v2 API.';
        healthDetail.textContent = 'If the application is running with the "lite" profile, v2 is '
            + 'disabled by design and only the v1 endpoints are served.';
        healthDetail.classList.remove('hidden');
    }
}

function populateProviders(providers, defaultProvider) {
    const available = providers.available || {};
    providerSelect.innerHTML = '';

    const defaultOption = document.createElement('option');
    defaultOption.value = '';
    defaultOption.textContent = `Default (${defaultProvider})`;
    providerSelect.appendChild(defaultOption);

    Object.entries(available).forEach(([name, info]) => {
        const option = document.createElement('option');
        option.value = name;
        option.textContent = `${name} - ${info.model}`;
        option.dataset.model = info.model;
        providerSelect.appendChild(option);
    });

    Object.entries(providers.unavailable || {}).forEach(([name, reason]) => {
        const option = document.createElement('option');
        option.value = name;
        option.disabled = true;
        option.textContent = `${name} (${reason.split(' -')[0]})`;
        providerSelect.appendChild(option);
    });
}

providerSelect.addEventListener('change', () => {
    // Show the provider's own model as a placeholder so it is obvious what runs if left blank.
    const selected = providerSelect.selectedOptions[0];
    modelInput.placeholder = selected?.dataset.model || 'provider default';
});

pdfFileInput.addEventListener('change', () => {
    const file = pdfFileInput.files[0];
    fileLabel.textContent = file ? file.name : 'Choose PI PDF or image';
});

// ---------------------------------------------------------------- extraction

uploadForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    const file = pdfFileInput.files[0];
    if (!file) return;

    resetUi();
    setBusy(true);

    const formData = new FormData();
    formData.append('file', file);
    if (providerSelect.value) formData.append('provider', providerSelect.value);
    if (modelInput.value.trim()) formData.append('model', modelInput.value.trim());
    formData.append('useRag', el('useRag').checked);
    formData.append('includeTextLayer', el('includeTextLayer').checked);
    formData.append('useCache', el('useCache').checked);
    formData.append('streamDeltas', el('streamDeltas').checked);

    progressSection.classList.remove('hidden');
    renderStages(null);
    statusText.textContent = 'Uploading document...';
    progressFill.style.width = '4%';

    const controller = new AbortController();
    inFlight = controller;

    try {
        await streamExtraction(formData, controller.signal);
    } catch (error) {
        if (error.name === 'AbortError') {
            statusText.textContent = 'Cancelled.';
            progressFill.style.width = '0%';
        } else {
            console.error(`${LOG} extraction failed`, error);
            showError(error.message);
        }
    } finally {
        inFlight = null;
        setBusy(false);
    }
});

cancelBtn.addEventListener('click', () => inFlight?.abort());

/**
 * Reads the SSE stream from a multipart POST.
 *
 * EventSource cannot POST a file, so the stream is consumed straight off the fetch body. The
 * server also sends `:keep-alive` comments while the model is thinking; those parse to nothing and
 * are ignored, which is exactly what keeps the connection alive through a proxy.
 */
async function streamExtraction(formData, signal) {
    const response = await fetch(`${API}/extract/stream`, { method: 'POST', body: formData, signal });

    if (!response.ok) {
        const problem = await response.json().catch(() => ({}));
        throw new Error(problem.error || `Extraction failed (HTTP ${response.status})`);
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';

    while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });

        let boundary;
        while ((boundary = buffer.indexOf('\n\n')) >= 0) {
            const chunk = buffer.slice(0, boundary);
            buffer = buffer.slice(boundary + 2);
            handleEvent(parseEvent(chunk));
        }
    }
}

function parseEvent(chunk) {
    let name = 'message';
    const dataLines = [];
    for (const line of chunk.split('\n')) {
        if (line.startsWith(':')) continue;               // keep-alive comment
        if (line.startsWith('event:')) name = line.slice(6).trim();
        else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim());
    }
    if (dataLines.length === 0) return null;
    try {
        return { name, data: JSON.parse(dataLines.join('\n')) };
    } catch {
        return { name, data: { text: dataLines.join('\n') } };
    }
}

function handleEvent(event) {
    if (!event) return;
    switch (event.name) {
        case 'stage':
            renderStages(event.data.stage);
            statusText.textContent = event.data.message;
            break;
        case 'delta':
            streamDetails.classList.remove('hidden');
            streamOutput.textContent += event.data.text;
            streamOutput.scrollTop = streamOutput.scrollHeight;
            break;
        case 'result':
            progressFill.style.width = '100%';
            renderStages('done');
            statusText.textContent = event.data.cached
                ? 'Served from cache - identical file, identical settings.'
                : `Done in ${formatDuration(event.data.durationMs)}.`;
            renderResult(event.data);
            break;
        case 'error':
            showError(event.data.error || 'Extraction failed');
            break;
        default:
            break;
    }
}

function renderStages(currentStage) {
    const currentIndex = currentStage === 'done' ? STAGES.length : STAGES.indexOf(currentStage);
    stageList.innerHTML = STAGES.map((stage, index) => {
        let state = 'pending';
        if (currentIndex > index) state = 'done';
        else if (currentIndex === index) state = 'active';
        return `<li class="stage stage-${state}"><span></span>${escapeHtml(STAGE_LABELS[stage])}</li>`;
    }).join('');

    if (currentIndex >= 0) {
        // Advance by completed stages rather than by a made-up percentage.
        progressFill.style.width = `${Math.min(100, 4 + (currentIndex / STAGES.length) * 96)}%`;
    }
}

// ---------------------------------------------------------------- results

function renderResult(result) {
    lastResult = result;
    resultsSection.classList.remove('hidden');
    rawJson.textContent = JSON.stringify(result, null, 2);

    renderRunMeta(result);
    renderVerification(result.verification);
    renderInvoices(result.extraction, result.verification);
    renderKnowledgeUsed(result.knowledgeUsed);
}

function renderRunMeta(result) {
    const facts = [
        ['Provider', `${result.provider} / ${result.model}`],
        ['Pages', result.pageCount],
        ['Duration', formatDuration(result.durationMs)],
        ['Text layer', result.textLayerUsed ? 'used' : 'not available'],
        ['Layout match', result.signatureSource === 'NONE' ? 'none' : result.signatureSource.toLowerCase()],
        ['Cached', result.cached ? 'yes' : 'no']
    ];
    if (result.usage?.promptTokens) {
        facts.push(['Tokens', `${result.usage.promptTokens} in / ${result.usage.completionTokens ?? '?'} out`]);
    }
    runMeta.innerHTML = `<div class="fact-grid">${facts.map(([k, v]) => renderFact(k, v)).join('')}</div>`;
}

function renderVerification(verification) {
    if (!verification) {
        verificationPanel.innerHTML = '';
        return;
    }

    const pass = verification.status === 'PASS';
    const grounded = verification.groundingAvailable
        ? `${verification.fieldsGrounded}/${verification.fieldsChecked} values found in the document text`
        : 'Not checked - this document has no text layer';
    const groundedPct = verification.fieldsChecked > 0
        ? Math.round((verification.fieldsGrounded / verification.fieldsChecked) * 100)
        : 0;

    verificationPanel.innerHTML = `
        <div class="summary-panel verification-panel ${pass ? 'verified-ok' : 'verified-warn'}">
            <div class="invoice-title">
                <h3>Verification</h3>
                <span class="chip ${pass ? 'chip-ok' : 'chip-warn'}">${escapeHtml(verification.status)}</span>
            </div>

            <p class="muted small">
                These checks are deterministic and run after extraction. Nothing was corrected -
                anything that did not reconcile is reported as it was extracted.
            </p>

            <div class="grounding">
                <div class="grounding-bar ${verification.groundingAvailable ? '' : 'unavailable'}">
                    <div style="width:${verification.groundingAvailable ? groundedPct : 0}%"></div>
                </div>
                <span class="muted small">${escapeHtml(grounded)}</span>
            </div>

            ${renderMiniSection('Checks', (verification.checks || []).map((check) => `
                <li class="check ${check.passed ? 'check-ok' : 'check-fail'}">
                    <strong>${check.passed ? '✓' : '✗'} ${escapeHtml(check.name)}</strong>
                    <span>${escapeHtml(check.detail)}</span>
                </li>
            `))}

            ${renderMiniSection('Warnings', (verification.warnings || []).map(
                (warning) => `<li class="warning-row"><span>${escapeHtml(warning)}</span></li>`))}

            ${renderMiniSection('Unverified fields', (verification.ungroundedFields || []).length
                ? [`<li class="unverified-list"><span>${(verification.ungroundedFields || [])
                    .map((field) => `<code class="unverified-chip">${escapeHtml(field)}</code>`)
                    .join(' ')}</span></li>`]
                : [])}
        </div>
    `;
}

function renderInvoices(extraction, verification) {
    invoicesContainer.innerHTML = '';
    if (!extraction) {
        invoicesContainer.innerHTML =
            '<div class="summary-panel"><h3>No structured data returned</h3></div>';
        return;
    }

    const ungrounded = new Set(verification?.ungroundedFields || []);
    getInvoices(extraction).forEach((invoice) => {
        invoicesContainer.appendChild(renderInvoiceCard(invoice, ungrounded));
    });
}

function getInvoices(extraction) {
    if (extraction.DEFN_PROFORMA_INVOICE || extraction.DEFN_PROFORMA_INVOICE_HSC) {
        return [{
            uniqueId: 'pi-1',
            // Field paths in the verification report are relative to the document root, so a
            // single-invoice document has no prefix.
            path: '',
            header: extraction.DEFN_PROFORMA_INVOICE || {},
            items: asArray(extraction.DEFN_PROFORMA_INVOICE_HSC)
        }];
    }
    return asArray(extraction.PROFORMA_INVOICES).map((invoice, index) => ({
        uniqueId: invoice.unique_id || `pi-${index + 1}`,
        path: `PROFORMA_INVOICES[${index}].`,
        header: invoice.DEFN_PROFORMA_INVOICE || {},
        items: asArray(invoice.DEFN_PROFORMA_INVOICE_HSC)
    }));
}

function renderInvoiceCard(invoice, ungrounded) {
    const card = document.createElement('div');
    card.className = 'summary-panel invoice-panel';
    const header = invoice.header || {};
    const headerPath = `${invoice.path}DEFN_PROFORMA_INVOICE.`;
    const fact = (label, field) => renderFact(label, header[field], ungrounded.has(headerPath + field));

    card.innerHTML = `
        <div class="invoice-title">
            <h3>${escapeHtml(invoice.uniqueId)}: ${escapeHtml(header.pi_no || 'No visible PI number')}</h3>
            <span>${escapeHtml(header.status || 'Draft')}</span>
        </div>
        <div class="fact-grid">
            ${fact('Applicant', 'applicant_name')}
            ${fact('Beneficiary', 'beneficiary_name')}
            ${fact('Bank', 'bank_name')}
            ${fact('Swift', 'swift')}
            ${fact('Currency', 'currency')}
            ${fact('Total amount', 'total_amount')}
            ${fact('PI date', 'pi_date')}
            ${fact('Draft days', 'draft_at_days')}
            ${fact('Shipment validity', 'shipment_validity')}
        </div>
        ${renderMiniSection('HSC Items', invoice.items.map(
            (item, index) => renderHscItem(item, `${invoice.path}DEFN_PROFORMA_INVOICE_HSC[${index}].`, ungrounded)))}
        ${renderMiniSection('Payment Terms', textRow('Payment', header.payment_terms))}
        ${renderMiniSection('Remarks', textRow('Remarks', header.remarks))}
        ${renderMiniSection('Extra Content', contentRows(header.content))}
    `;
    return card;
}

function renderHscItem(item, path, ungrounded) {
    const label = item.description || `Item ${item.sl_no || ''}`.trim();
    const bits = [
        ['hs_code', item.hs_code, (v) => `HS ${v}`],
        ['quantity', item.quantity, (v) => `${v}${item.quantity_unit ? ` ${item.quantity_unit}` : ''}`],
        ['unit_price', item.unit_price, (v) => `Unit ${v}`],
        ['total_amount', item.total_amount, (v) => `Amount ${v}`],
        ['style_no', item.style_no, (v) => `Style ${v}`],
        ['net_weight', item.net_weight, (v) => `Net ${v}`]
    ]
        .filter(([, value]) => value !== null && value !== undefined && value !== '')
        .map(([field, value, format]) => {
            const text = escapeHtml(format(value));
            return ungrounded.has(path + field) ? `<span class="unverified-inline">${text}</span>` : text;
        });

    const flagged = bits.some((bit) => bit.includes('unverified-inline'));
    return `<li${flagged ? ' class="has-unverified"' : ''}>
        <strong>${escapeHtml(label)}</strong><span>${bits.join(' | ')}</span></li>`;
}

function renderKnowledgeUsed(knowledgeUsed) {
    if (!knowledgeUsed || knowledgeUsed.length === 0) {
        knowledgeUsedPanel.innerHTML = '';
        return;
    }
    const mandatory = knowledgeUsed.filter((k) => k.mandatory);
    const matched = knowledgeUsed.filter((k) => !k.mandatory);

    knowledgeUsedPanel.innerHTML = `
        <div class="summary-panel">
            <div class="invoice-title">
                <h3>Rules applied</h3>
                <span>${mandatory.length} always-on, ${matched.length} matched this document</span>
            </div>
            <p class="muted small">
                Guidance only - none of these carry values. They tell the model how and where to
                read; the document itself is the only source of what it says.
            </p>
            ${renderMiniSection('Matched this document', matched.map((k) => `
                <li><strong>${escapeHtml(k.title)}</strong>
                    <span>${escapeHtml(k.type)} - match ${k.score?.toFixed(2) ?? '-'}</span></li>`))}
            ${renderMiniSection('Always applied', mandatory.map((k) => `
                <li><strong>${escapeHtml(k.title)}</strong><span>${escapeHtml(k.type)}</span></li>`))}
        </div>
    `;
}

downloadBtn.addEventListener('click', () => {
    if (!lastResult) return;
    const blob = new Blob([JSON.stringify(lastResult, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `pi-extraction-${lastResult.requestId}.json`;
    a.click();
    URL.revokeObjectURL(url);
});

// ---------------------------------------------------------------- knowledge base

el('knowledgeListBtn').addEventListener('click', listKnowledge);
el('knowledgeSearchBtn').addEventListener('click', searchKnowledge);
el('knowledgeSearch').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
        e.preventDefault();
        searchKnowledge();
    }
});
el('reindexBtn').addEventListener('click', reindexKnowledge);
el('knowledgeDetails').addEventListener('toggle', (event) => {
    if (event.target.open && !knowledgeList.dataset.loaded) listKnowledge();
});

async function listKnowledge() {
    const type = el('knowledgeTypeFilter').value;
    knowledgeList.innerHTML = '<p class="muted small">Loading...</p>';
    try {
        const url = type ? `${KNOWLEDGE_API}?type=${encodeURIComponent(type)}` : KNOWLEDGE_API;
        const entries = await fetch(url).then((r) => r.json());
        knowledgeList.dataset.loaded = 'true';
        knowledgeList.innerHTML = entries.length === 0
            ? '<p class="muted small">No entries.</p>'
            : entries.map(renderKnowledgeEntry).join('');
        wireKnowledgeToggles();
    } catch (error) {
        knowledgeList.innerHTML = `<p class="muted small">Could not load: ${escapeHtml(error.message)}</p>`;
    }
}

/** Runs the same vector search the pipeline uses, so you can see what a document would pull in. */
async function searchKnowledge() {
    const query = el('knowledgeSearch').value.trim();
    if (!query) return listKnowledge();

    const type = el('knowledgeTypeFilter').value;
    knowledgeList.innerHTML = '<p class="muted small">Searching...</p>';
    try {
        const params = new URLSearchParams({ q: query, topK: '8' });
        if (type) params.set('type', type);
        const hits = await fetch(`${KNOWLEDGE_API}/search?${params}`).then((r) => r.json());
        knowledgeList.innerHTML = hits.length === 0
            ? '<p class="muted small">Nothing matched. The mandatory rules would still be applied.</p>'
            : hits.map((hit) => `
                <div class="knowledge-entry">
                    <div class="knowledge-head">
                        <strong>${escapeHtml(hit.title || '(untitled)')}</strong>
                        <span class="chip chip-muted">${escapeHtml(hit.type || '')}</span>
                        <span class="muted small">match ${hit.score?.toFixed(3) ?? '-'}</span>
                    </div>
                    <pre class="knowledge-body">${escapeHtml((hit.text || '').slice(0, 600))}</pre>
                </div>`).join('');
    } catch (error) {
        knowledgeList.innerHTML = `<p class="muted small">Search failed: ${escapeHtml(error.message)}</p>`;
    }
}

function renderKnowledgeEntry(entry) {
    return `
        <div class="knowledge-entry ${entry.enabled ? '' : 'disabled'}">
            <div class="knowledge-head">
                <strong>${escapeHtml(entry.title)}</strong>
                <span class="chip chip-muted">${escapeHtml(entry.type)}</span>
                ${entry.alwaysInclude ? '<span class="chip chip-always">always</span>' : ''}
                ${entry.issuer ? `<span class="muted small">${escapeHtml(entry.issuer)}</span>` : ''}
                <label class="toggle small-toggle">
                    <input type="checkbox" data-knowledge-id="${escapeHtml(entry.id)}"
                           ${entry.enabled ? 'checked' : ''}>
                    <span>enabled</span>
                </label>
            </div>
            <pre class="knowledge-body">${escapeHtml(entry.body.slice(0, 600))}${entry.body.length > 600 ? '\n...' : ''}</pre>
        </div>`;
}

function wireKnowledgeToggles() {
    knowledgeList.querySelectorAll('input[data-knowledge-id]').forEach((input) => {
        input.addEventListener('change', async () => {
            const id = input.dataset.knowledgeId;
            input.disabled = true;
            try {
                const response = await fetch(`${KNOWLEDGE_API}/${id}`, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ enabled: input.checked })
                });
                if (!response.ok) throw new Error(`HTTP ${response.status}`);
                input.closest('.knowledge-entry').classList.toggle('disabled', !input.checked);
                checkHealth();
            } catch (error) {
                input.checked = !input.checked;
                showError(`Could not update knowledge entry: ${error.message}`);
            } finally {
                input.disabled = false;
            }
        });
    });
}

async function reindexKnowledge() {
    const button = el('reindexBtn');
    button.disabled = true;
    button.textContent = 'Reindexing...';
    try {
        const result = await fetch(`${KNOWLEDGE_API}/reindex`, { method: 'POST' }).then((r) => r.json());
        knowledgeList.innerHTML =
            `<p class="muted small">Reindexed ${result.indexed} entries.</p>`;
        knowledgeList.dataset.loaded = '';
        checkHealth();
    } catch (error) {
        showError(`Reindex failed: ${error.message}`);
    } finally {
        button.disabled = false;
        button.textContent = 'Reindex';
    }
}

// ---------------------------------------------------------------- helpers

function renderFact(label, value, unverified = false) {
    const empty = value === null || value === undefined || value === '';
    return `
        <div class="fact ${unverified ? 'fact-unverified' : ''}"
             ${unverified ? 'title="Not found in the document text - review before use"' : ''}>
            <span>${escapeHtml(label)}${unverified ? ' <em>unverified</em>' : ''}</span>
            <strong class="${empty ? 'muted' : ''}">${empty ? '-' : escapeHtml(value)}</strong>
        </div>`;
}

function renderMiniSection(title, rows) {
    const content = Array.isArray(rows) ? rows.filter(Boolean).join('') : rows;
    if (!content) return '';
    return `<div class="mini-section"><h4>${escapeHtml(title)}</h4><ul>${content}</ul></div>`;
}

function textRow(label, value) {
    return value ? [`<li><strong>${escapeHtml(label)}</strong><span>${escapeHtml(value)}</span></li>`] : [];
}

function contentRows(content) {
    if (!content || typeof content !== 'object') return [];
    return Object.entries(content)
        .slice(0, 12)
        .map(([key, value]) =>
            `<li><strong>${escapeHtml(key)}</strong><span>${escapeHtml(joinValues(value))}</span></li>`);
}

function joinValues(value) {
    if (value === null || value === undefined) return '';
    if (Array.isArray(value)) {
        return value.map((item) => (item && typeof item === 'object'
            ? item.value || item.text || item.label || item.name || JSON.stringify(item)
            : String(item ?? ''))).filter(Boolean).join(', ');
    }
    if (typeof value === 'object') return value.value || value.text || value.label || JSON.stringify(value);
    return String(value);
}

function asArray(value) {
    if (!value) return [];
    return Array.isArray(value) ? value : [value];
}

function formatDuration(ms) {
    if (ms == null) return '-';
    if (ms < 1000) return `${ms}ms`;
    const seconds = ms / 1000;
    if (seconds < 60) return `${seconds.toFixed(1)}s`;
    return `${Math.floor(seconds / 60)}m ${Math.round(seconds % 60)}s`;
}

function escapeHtml(value) {
    return String(value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
}

function setBusy(busy) {
    submitBtn.disabled = busy;
    cancelBtn.classList.toggle('hidden', !busy);
}

function showError(message) {
    console.error(`${LOG} ${message}`);
    errorSection.classList.remove('hidden');
    errorText.textContent = message;
}

function resetUi() {
    errorSection.classList.add('hidden');
    resultsSection.classList.add('hidden');
    streamDetails.classList.add('hidden');
    streamOutput.textContent = '';
    invoicesContainer.innerHTML = '';
    verificationPanel.innerHTML = '';
    knowledgeUsedPanel.innerHTML = '';
    runMeta.innerHTML = '';
    rawJson.textContent = '';
    progressFill.style.width = '0%';
    lastResult = null;
}
