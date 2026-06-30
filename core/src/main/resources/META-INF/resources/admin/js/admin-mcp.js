/**
 * Admin UI - MCP (Model Context Protocol) Traces Management
 *
 * Handles MCP client listing and trace visualization
 */

let mcpTraces = [];
let mcpTraceLevel = 'verbose';
let mcpClients = [];
let mcpAllFolded = true;
let selectedMcpClient = null;
let mcpTracesByClient = {}; // Store traces per client: {connectionId: [...traces]}
let mcpTracesLoaded = false; // Track if MCP traces have been loaded

/**
 * Load MCP clients.
 */
async function loadMcpClients() {
    try {
        const response = await fetch('/api/admin/mcp-clients');
        const newClients = await response.json();

        // Check if data actually changed to avoid unnecessary re-renders
        if (JSON.stringify(newClients) !== JSON.stringify(mcpClients)) {
            mcpClients = newClients;
            renderMcpClients();

            // Auto-select first client if none selected
            if (mcpClients.length > 0 && !selectedMcpClient) {
                selectMcpClient(mcpClients[0].id);
            }

            // Check if previously selected client still exists
            if (selectedMcpClient) {
                const stillExists = mcpClients.find(c => c.id === selectedMcpClient);
                if (!stillExists) {
                    // Previously selected client disconnected
                    selectedMcpClient = null;
                    if (mcpClients.length > 0) {
                        selectMcpClient(mcpClients[0].id);
                    }
                }
            }
        }
    } catch (e) {
        console.error('Failed to load MCP clients:', e);
        document.getElementById('mcp-clients-list').innerHTML =
            '<div style="padding: 1rem; color: #888;">Failed to load clients</div>';
    }
}

function renderMcpClients() {
    const list = document.getElementById('mcp-clients-list');
    if (!list) return;

    if (mcpClients.length === 0) {
        list.innerHTML = '<div style="padding: 1rem; color: #888;">No clients connected</div>';
        return;
    }

    list.innerHTML = mcpClients.map(client => {
        // Get first trace timestamp for this client
        const clientTraces = mcpTracesByClient[client.id] || [];
        let timeStr = '';
        if (clientTraces.length > 0) {
            const firstTrace = clientTraces[0];
            try {
                const date = new Date(firstTrace.timestamp);
                timeStr = date.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' });
            } catch (e) {
                timeStr = '';
            }
        }

        // Shorten connection ID for display (first 8 chars)
        const shortId = client.id.substring(0, 8) + '...';

        return `
            <div class="workspace-item ${client.id === selectedMcpClient ? 'active' : ''}"
                 onclick="selectMcpClient('${client.id}')"
                 style="cursor: pointer;"
                 title="${window.escapeHtml ? window.escapeHtml(client.id) : client.id}">
                <div style="font-weight: 600; margin-bottom: 0.25rem;">
                    📱 ${window.escapeHtml ? window.escapeHtml(client.name) : client.name} ${timeStr ? `<span style="color: #666; font-weight: normal; font-size: 0.75rem;">@ ${timeStr}</span>` : ''}
                </div>
                <div style="font-size: 0.75rem; color: #666; padding-left: 1.5rem;">
                    Session: ${window.escapeHtml ? window.escapeHtml(shortId) : shortId}
                </div>
            </div>
        `;
    }).join('');

    // Auto-select first client if none selected and clients exist
    // BUT only if we're on the MCP tab
    if (window.currentTab === 'mcp-traces') {
        if (!selectedMcpClient && mcpClients.length > 0) {
            selectMcpClient(mcpClients[0].id);
        } else if (selectedMcpClient) {
            // Verify selected client still exists
            const stillExists = mcpClients.find(c => c.id === selectedMcpClient);
            if (!stillExists) {
                // Selected client disconnected, select first available or show placeholder
                if (mcpClients.length > 0) {
                    selectMcpClient(mcpClients[0].id);
                } else {
                    selectedMcpClient = null;
                    loadMcpTracesConsole();
                }
            }
        }
    }
}

async function selectMcpClient(clientId) {
    selectedMcpClient = clientId;
    renderMcpClients();

    // Load initial traces if not already loaded
    await loadInitialMcpTraces();

    loadMcpConsole(clientId);
}

/**
 * Load initial MCP traces from backend (called once when accessing MCP tab).
 */
async function loadInitialMcpTraces() {
    if (mcpTracesLoaded) return;
    mcpTracesLoaded = true;

    try {
        const allTraces = await fetch('/api/admin/mcp-traces?limit=500').then(r => r.json());

        // Organize traces by connectionId (client)
        mcpTracesByClient = {};
        allTraces.forEach(trace => {
            const connectionId = trace.connectionId;
            if (!mcpTracesByClient[connectionId]) {
                mcpTracesByClient[connectionId] = [];
            }
            mcpTracesByClient[connectionId].push(trace);
        });

        console.log('Loaded initial MCP traces:', Object.keys(mcpTracesByClient).length, 'clients');

        // Re-render console if a client is selected
        if (selectedMcpClient) {
            renderMcpConsole();
        }
    } catch (e) {
        console.error('Failed to load initial MCP traces:', e);
    }
}

async function loadMcpTracesConsole() {
    const consoleArea = document.getElementById('console-area');

    // Show placeholder if no client selected yet
    consoleArea.innerHTML = `
        <div class="placeholder">
            ← Select an AI client to view MCP traces
        </div>
    `;
}

async function loadMcpConsole(clientId) {
    const consoleArea = document.getElementById('console-area');

    // Find client info
    const client = mcpClients.find(c => c.id === clientId);
    const clientName = client ? client.name : 'MCP Client';

    // Render console with tabs (exact same structure as LSP)
    consoleArea.innerHTML = `
        <div class="console-wrapper">
            <div class="console-header">
                <div class="console-tabs">
                    <button class="tab-button active" onclick="switchMcpConsoleTab('traces')">Traces</button>
                    <button class="tab-button" onclick="switchMcpConsoleTab('tools')">Tools</button>
                </div>
                <div class="console-controls" id="mcp-traces-controls">
                    <label style="color: #cccccc; font-size: 0.85rem;">
                        Trace Level:
                        <select id="mcp-trace-level" onchange="changeMcpTraceLevel(this.value)" style="margin-left: 0.5rem; background: #3e3e42; color: #cccccc; border: 1px solid #555; padding: 0.25rem 0.5rem; border-radius: 3px;">
                            <option value="off" ${mcpTraceLevel === 'off' ? 'selected' : ''}>Off</option>
                            <option value="messages" ${mcpTraceLevel === 'messages' ? 'selected' : ''}>Messages</option>
                            <option value="verbose" ${mcpTraceLevel === 'verbose' ? 'selected' : ''}>Verbose</option>
                        </select>
                    </label>
                    <button onclick="toggleAllMcpTraces()" id="mcp-fold-button">Unfold All</button>
                    <button onclick="clearMcpConsole()">Clear</button>
                </div>
            </div>
            <div class="tab-content">
                <div id="mcp-traces-tab" class="tab-panel active">
                    <div class="console" id="mcp-console-output" tabindex="0"></div>
                </div>
                <div id="mcp-tools-tab" class="tab-panel">
                    <div class="details-panel">
                        <p>MCP Tools coming soon...</p>
                    </div>
                </div>
            </div>
        </div>
    `;

    renderMcpConsole();
}

async function changeMcpTraceLevel(newLevel) {
    try {
        await fetch('/api/admin/config/mcp/trace', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ trace: newLevel })
        });

        mcpTraceLevel = newLevel;
        renderMcpConsole();
    } catch (err) {
        console.error('Failed to set MCP trace level:', err);
    }
}

function switchMcpConsoleTab(tab) {
    // Update tab buttons
    document.querySelectorAll('#console-area .tab-button').forEach(btn => {
        btn.classList.remove('active');
    });
    event.target.classList.add('active');

    // Update tab panels
    document.querySelectorAll('#console-area .tab-panel').forEach(panel => {
        panel.classList.remove('active');
    });

    // Show selected tab
    if (tab === 'traces') {
        document.getElementById('mcp-traces-tab').classList.add('active');
        document.getElementById('mcp-traces-controls').style.display = 'flex';
    } else if (tab === 'tools') {
        document.getElementById('mcp-tools-tab').classList.add('active');
        document.getElementById('mcp-traces-controls').style.display = 'none';
    }
}

function renderMcpConsole() {
    const output = document.getElementById('mcp-console-output');
    if (!output) return;

    if (mcpTraceLevel === 'off') {
        output.innerHTML = '<div style="padding: 1rem; color: #888;">Traces disabled (level: off)</div>';
        return;
    }

    // Get traces for the selected client
    const clientTraces = mcpTracesByClient[selectedMcpClient] || [];

    if (clientTraces.length === 0) {
        output.innerHTML = '<div style="padding: 1rem; color: #888;">No MCP traces yet...</div>';
        return;
    }

    // Save expanded state before re-rendering
    const expandedTraces = new Set();
    output.querySelectorAll('.mcp-trace-body.expanded').forEach(el => {
        expandedTraces.add(el.id);
    });

    const html = clientTraces.map(trace => formatMcpTrace(trace, '')).join('');
    output.innerHTML = html;

    // Restore expanded state
    expandedTraces.forEach(traceId => {
        const body = document.getElementById(traceId);
        const arrow = document.getElementById(traceId + '-arrow');
        if (body && arrow) {
            body.classList.remove('collapsed');
            body.classList.add('expanded');
            arrow.textContent = '▼';
        }
    });

    // Auto-scroll to bottom
    output.scrollTop = output.scrollHeight;
}

function renderMcpConsoleWithHighlights() {
    const output = document.getElementById('mcp-console-output');
    if (!output) return;

    if (mcpTraceLevel === 'off') {
        output.innerHTML = '<div style="padding: 1rem; color: #888;">Traces disabled (level: off)</div>';
        return;
    }

    // Get traces for the selected client
    const clientTraces = mcpTracesByClient[selectedMcpClient] || [];

    if (clientTraces.length === 0) {
        output.innerHTML = '<div style="padding: 1rem; color: #888;">No MCP traces yet...</div>';
        return;
    }

    // Save expanded state before re-rendering
    const expandedTraces = new Set();
    output.querySelectorAll('.mcp-trace-body.expanded').forEach(el => {
        expandedTraces.add(el.id);
    });

    const html = clientTraces.map(trace => formatMcpTrace(trace, window.currentSearchQuery || '')).join('');
    output.innerHTML = html;

    // Restore expanded state
    expandedTraces.forEach(traceId => {
        const body = document.getElementById(traceId);
        const arrow = document.getElementById(traceId + '-arrow');
        if (body && arrow) {
            body.classList.remove('collapsed');
            body.classList.add('expanded');
            arrow.textContent = '▼';
        }
    });
}

function formatMcpTrace(trace, searchQuery = '') {
    const content = trace.jsonContent;

    // Split by first newline to separate header from body
    const firstNewline = content.indexOf('\n');
    if (firstNewline === -1) {
        // No body, just header
        return `
            <div class="trace-line">
                <div style="padding: 0.25rem; font-family: 'Consolas', 'Monaco', monospace; font-size: 0.85rem; color: #cccccc;">${window.highlightText ? window.highlightText(content, searchQuery) : content}</div>
            </div>
        `;
    }

    const headerLine = content.substring(0, firstNewline);
    let body = content.substring(firstNewline + 1).trim();

    // Check if trace has search match
    const hasMatch = searchQuery && trace.jsonContent.toLowerCase().includes(searchQuery.toLowerCase());

    // Messages mode: show only header line, no folding
    if (mcpTraceLevel === 'messages') {
        return `
            <div class="trace-line">
                <div style="padding: 0.25rem; font-family: 'Consolas', 'Monaco', monospace; font-size: 0.85rem; color: #cccccc;">${window.highlightText ? window.highlightText(headerLine, searchQuery) : headerLine}</div>
            </div>
        `;
    }

    // Verbose mode: header + body folded by default
    const hasBody = body.length > 0;
    if (!hasBody) {
        return `<div class="trace-line">
            <div style="padding: 0.25rem; font-family: 'Consolas', 'Monaco', monospace; font-size: 0.85rem; color: #cccccc;">${window.highlightText ? window.highlightText(headerLine, searchQuery) : headerLine}</div>
        </div>`;
    }

    // With body: show toggle arrow and collapsible content
    // Auto-expand if has search match
    const traceId = 'mcp-trace-' + Math.random().toString(36).substr(2, 9);
    const foldState = hasMatch ? 'expanded' : 'collapsed';
    const toggleIcon = hasMatch ? '▼' : '▶';
    const fullContent = headerLine + '\n' + body;

    return `
        <div class="trace-line" onmouseenter="showMcpTooltip(event, '${traceId}', ${!hasMatch})" onmouseleave="hideMcpTooltip('${traceId}')">
            <div style="display: flex; align-items: flex-start; padding: 0.25rem; cursor: pointer;"
                 onclick="toggleMcpTrace('${traceId}')">
                <span id="${traceId}-arrow" class="mcp-trace-toggle" style="margin-right: 0.5rem; user-select: none; font-size: 0.7rem; color: #888;">${toggleIcon}</span>
                <div style="flex: 1; font-family: 'Consolas', 'Monaco', monospace; font-size: 0.85rem; color: #cccccc;">${window.highlightText ? window.highlightText(headerLine, searchQuery) : headerLine}</div>
            </div>
            <div id="${traceId}" class="mcp-trace-body ${foldState}" style="font-family: 'Consolas', 'Monaco', monospace; font-size: 0.8rem; color: #d4d4d4; white-space: pre-wrap; word-wrap: break-word; padding-left: 1.5rem;">${window.highlightText ? window.highlightText(body, searchQuery) : body}</div>
            <div class="trace-tooltip" id="${traceId}-tooltip">${window.escapeHtml ? window.escapeHtml(fullContent) : fullContent}</div>
        </div>
    `;
}

function showMcpTooltip(event, traceId, isFolded) {
    if (!isFolded) return;

    const body = document.getElementById(traceId);
    if (!body || !body.classList.contains('collapsed')) return;

    const tooltip = document.getElementById(traceId + '-tooltip');
    if (!tooltip) return;

    // Position tooltip near mouse
    const x = event.clientX + 15;
    const y = event.clientY + 15;

    tooltip.style.left = x + 'px';
    tooltip.style.top = y + 'px';
    tooltip.style.display = 'block';
}

function hideMcpTooltip(traceId) {
    const tooltip = document.getElementById(traceId + '-tooltip');
    if (tooltip) {
        tooltip.style.display = 'none';
    }
}

function toggleMcpTrace(id) {
    const body = document.getElementById(id);
    const arrow = document.getElementById(id + '-arrow');

    if (body.classList.contains('collapsed')) {
        body.classList.remove('collapsed');
        body.classList.add('expanded');
        arrow.textContent = '▼';
    } else {
        body.classList.remove('expanded');
        body.classList.add('collapsed');
        arrow.textContent = '▶';
    }
}

function toggleAllMcpTraces() {
    if (window.toggleAllTracesGeneric) {
        window.toggleAllTracesGeneric('mcp-console-output', 'mcp-trace-body', 'mcp-trace-toggle', 'mcp-fold-button', {
            get value() { return mcpAllFolded; },
            set value(v) { mcpAllFolded = v; }
        });
    }
}

async function clearMcpConsole() {
    try {
        await fetch('/api/admin/mcp-traces', { method: 'DELETE' });

        // Clear traces for current client only
        if (selectedMcpClient) {
            mcpTracesByClient[selectedMcpClient] = [];
        }

        renderMcpConsole();
    } catch (error) {
        console.error('Failed to clear MCP traces:', error);
    }
}

/**
 * Handle incoming MCP trace from WebSocket.
 */
function handleMcpTrace(trace) {
    const connectionId = trace.connectionId;

    // Store trace
    if (!mcpTracesByClient[connectionId]) {
        mcpTracesByClient[connectionId] = [];
    }
    mcpTracesByClient[connectionId].push(trace);

    // Re-render console if this trace is for the currently selected client
    if (selectedMcpClient === connectionId) {
        renderMcpConsole();
    }
}

/**
 * Handle MCP clients update from WebSocket.
 */
function handleMcpClientsUpdate(newClients) {
    // Check if data actually changed
    if (JSON.stringify(newClients) !== JSON.stringify(mcpClients)) {
        mcpClients = newClients;
        renderMcpClients();

        // Auto-select first client if none selected
        if (mcpClients.length > 0 && !selectedMcpClient) {
            selectMcpClient(mcpClients[0].id);
        }

        // Check if previously selected client still exists
        if (selectedMcpClient) {
            const stillExists = mcpClients.find(c => c.id === selectedMcpClient);
            if (!stillExists) {
                // Previously selected client disconnected
                selectedMcpClient = null;
                if (mcpClients.length > 0) {
                    selectMcpClient(mcpClients[0].id);
                } else {
                    loadMcpTracesConsole();
                }
            }
        }
    }
}

// Expose functions globally
window.loadMcpClients = loadMcpClients;
window.selectMcpClient = selectMcpClient;
window.loadMcpTracesConsole = loadMcpTracesConsole;
window.changeMcpTraceLevel = changeMcpTraceLevel;
window.switchMcpConsoleTab = switchMcpConsoleTab;
window.toggleMcpTrace = toggleMcpTrace;
window.toggleAllMcpTraces = toggleAllMcpTraces;
window.clearMcpConsole = clearMcpConsole;
window.showMcpTooltip = showMcpTooltip;
window.hideMcpTooltip = hideMcpTooltip;
window.handleMcpTrace = handleMcpTrace;
window.handleMcpClientsUpdate = handleMcpClientsUpdate;
window.renderMcpConsoleWithHighlights = renderMcpConsoleWithHighlights;
