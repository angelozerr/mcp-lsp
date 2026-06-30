/**
 * Admin UI - DAP (Debug Adapter Protocol) Management
 *
 * Handles DAP session creation, launching, and management
 */

/**
 * Create a new test session for a DAP server.
 * Called from the workspace Debuggers tab.
 */
async function createNewTestSession(dapServerId) {
    try {
        // Get current workspace URI from admin.js
        const workspaceUri = window.selectedWorkspace;
        if (!workspaceUri) {
            window.showAlert('No Workspace Selected', 'Please select a workspace first.');
            return;
        }

        // Create the session
        const response = await fetch('/api/admin/dap/sessions', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                workspaceUri: workspaceUri,
                dapServerId: dapServerId,
                sessionName: 'Test Session'
            })
        });

        if (!response.ok) {
            const errorText = await response.text();

            // Try to parse JSON error
            let errorMessage = errorText;
            try {
                const errorJson = JSON.parse(errorText);
                errorMessage = errorJson.error || errorJson.message || errorText;
            } catch (e) {
                // If HTML error page, try to extract the error message
                if (errorText.includes('<html')) {
                    const match = errorText.match(/<h1[^>]*>(.*?)<\/h1>/i) ||
                                  errorText.match(/<title>(.*?)<\/title>/i);
                    if (match) {
                        errorMessage = match[1].replace(/&quot;/g, '"').replace(/&lt;/g, '<').replace(/&gt;/g, '>');
                    } else {
                        errorMessage = 'Server error (see console for details)';
                        console.error('Full error HTML:', errorText);
                    }
                }
            }

            throw new Error(errorMessage);
        }

        const session = await response.json();

        // Show launch config form in console
        showLaunchConfigForm(session, dapServerId);

    } catch (error) {
        console.error('Error creating test session:', error);

        // Show error in console
        const consoleArea = document.getElementById('console-area');
        consoleArea.innerHTML = `
            <div style="padding: 1rem;">
                <h3 style="color: #f48771;">❌ Failed to Create Session</h3>
                <pre style="background: #1e1e1e; padding: 1rem; border-radius: 3px; color: #f48771; font-family: monospace; white-space: pre-wrap;">${error.message}</pre>
            </div>
        `;
    }
}

/**
 * Show the launch configuration form in the console area.
 */
function showLaunchConfigForm(session, dapServerId) {
    const consoleArea = document.getElementById('console-area');

    // Store session ID for later use
    window.currentDapSessionId = session.sessionId;

    // Default config based on DAP server type
    const defaultConfig = getDefaultLaunchConfig(dapServerId);

    consoleArea.innerHTML = `
        <div style="padding: 1rem; height: 100%; display: flex; flex-direction: column; overflow: hidden;">
            <div style="margin-bottom: 1rem;">
                <h3 style="margin: 0 0 0.5rem 0; color: #cccccc;">${session.sessionName || 'New Debug Session'}</h3>
                <p style="margin: 0; color: #858585; font-size: 0.85rem;">Server: ${session.dapServerId || dapServerId}</p>
            </div>

            <div style="margin-bottom: 1rem;">
                <div style="display: flex; align-items: center; gap: 0.5rem; margin-bottom: 0.5rem;">
                    <label style="font-weight: 500; color: #cccccc;">Launch Configuration</label>
                    <div style="display: flex; gap: 0;">
                        <button
                            onclick="launchDapSession('${session.sessionId}')"
                            style="padding: 0.1rem 0.2rem; background: transparent; color: #4ec9b0; border: none; cursor: pointer; font-size: 1rem; display: flex; align-items: center; border-radius: 3px; transition: background 0.2s;"
                            onmouseover="this.style.background='rgba(78, 201, 176, 0.2)'"
                            onmouseout="this.style.background='transparent'"
                            title="Launch debug session">
                            ▶
                        </button>
                        <button
                            onclick="deleteDapSession('${session.sessionId}')"
                            style="padding: 0.1rem 0.2rem; background: transparent; color: #858585; border: none; cursor: pointer; font-size: 0.9rem; display: flex; align-items: center; border-radius: 3px; transition: background 0.2s;"
                            onmouseover="this.style.background='rgba(133, 133, 133, 0.2)'"
                            onmouseout="this.style.background='transparent'"
                            title="Delete session">
                            🗑️
                        </button>
                    </div>
                </div>
                <textarea
                    id="launch-config-editor"
                    style="width: 100%; padding: 0.75rem; background: #1e1e1e; color: #d4d4d4; border: 1px solid #3a3a3a; border-radius: 3px; font-family: 'Courier New', monospace; font-size: 0.9rem; resize: vertical; height: 150px;"
                >${JSON.stringify(defaultConfig, null, 2)}</textarea>
            </div>

            <div style="flex: 1; display: flex; flex-direction: column; min-height: 0;">
                <label style="display: block; margin-bottom: 0.5rem; font-weight: 500; color: #cccccc;">Console:</label>
                <div id="dap-traces-container" style="flex: 1; overflow-y: auto; background: #1e1e1e; padding: 0.5rem; border-radius: 3px; font-family: 'Courier New', monospace; font-size: 0.85rem;">
                    <div style="color: #666;">Ready. Click ▶ to launch.</div>
                </div>
            </div>
        </div>
    `;
}

/**
 * Get default launch configuration based on DAP server type.
 */
function getDefaultLaunchConfig(dapServerId) {
    const configs = {
        'debugpy': {
            type: 'python',
            request: 'launch',
            name: 'Python: Current File',
            program: '${workspaceFolder}/main.py',
            console: 'integratedTerminal'
        },
        'vscode-js-debug': {
            type: 'node',
            request: 'launch',
            name: 'Launch Program',
            program: '${workspaceFolder}/index.js',
            skipFiles: ['<node_internals>/**']
        }
    };

    return configs[dapServerId] || {
        type: 'debug',
        request: 'launch',
        name: 'Launch',
        program: '${workspaceFolder}/main'
    };
}

/**
 * Launch a DAP session with the provided configuration.
 */
async function launchDapSession(sessionId) {
    try {
        const configText = document.getElementById('launch-config-editor').value;
        const launchConfig = JSON.parse(configText);

        const response = await fetch(`/api/admin/dap/sessions/${sessionId}/launch`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(launchConfig)
        });

        if (!response.ok) {
            const error = await response.text();
            throw new Error(error || 'Launch failed');
        }

        const result = await response.json();

        // Traces will appear in the dap-traces-container below
        console.log('Launch result:', result);

    } catch (error) {
        console.error('Error launching session:', error);

        // Parse error response
        let errorData = null;
        try {
            errorData = JSON.parse(error.message);
        } catch (e) {
            // Not JSON, use as-is
            errorData = { message: error.message, type: 'Error', stackTrace: '' };
        }

        // Use common error formatter
        const tracesContainer = document.getElementById('dap-traces-container');
        if (tracesContainer && typeof window.formatErrorWithFolding === 'function') {
            tracesContainer.innerHTML = window.formatErrorWithFolding('Failed to Launch', errorData);
        }
    }
}

/**
 * Delete a DAP session.
 */
async function deleteDapSession(sessionId) {
    const confirmed = await window.confirmAction(
        'Delete Debug Session',
        'Delete this test session?\n\nThis action cannot be undone.',
        'Delete',
        true
    );

    if (!confirmed) return;

    try {
        const response = await fetch(`/api/admin/dap/sessions/${sessionId}`, {
            method: 'DELETE'
        });

        if (!response.ok) {
            throw new Error('Failed to delete session');
        }

        // Clear console
        const consoleArea = document.getElementById('console-area');
        consoleArea.innerHTML = `
            <div class="placeholder">
                Session deleted
            </div>
        `;

    } catch (error) {
        console.error('Error deleting session:', error);
        window.showAlert('Failed to Delete Session', error.message);
    }
}

/**
 * Select a DAP session (called from workspace view).
 */
function selectDapSession(sessionId) {
    console.log('Selected DAP session:', sessionId);

    // Store current session for trace updates
    window.currentDapSessionId = sessionId;

    // Get traces for this session
    const traces = window.dapTracesBySession?.[sessionId] || [];

    const consoleArea = document.getElementById('console-area');
    consoleArea.innerHTML = `
        <div style="padding: 1rem; height: 100%; display: flex; flex-direction: column;">
            <h3 style="margin: 0 0 1rem 0;">Debug Session: ${sessionId}</h3>
            <div id="dap-traces-container" style="flex: 1; overflow-y: auto; background: #1e1e1e; padding: 0.5rem; border-radius: 3px; font-family: 'Courier New', monospace; font-size: 0.85rem;">
                ${traces.length > 0 ? renderDapTraces(traces) : '<div style="color: #666;">No traces yet. Click Launch to start debugging.</div>'}
            </div>
        </div>
    `;
}

function renderDapTraces(traces) {
    return traces.map(trace => {
        const isError = trace.jsonContent.includes('"error"') || trace.jsonContent.includes('ErrorResponse');
        const color = trace.direction === 'SENT' ? '#569cd6' : '#4ec9b0';
        const errorStyle = isError ? 'color: #f48771; font-weight: bold;' : '';

        return `
            <div style="margin-bottom: 1rem; ${errorStyle}">
                <div style="color: ${color};">[${trace.direction}] ${trace.timestamp}</div>
                <pre style="margin: 0.25rem 0; white-space: pre-wrap; word-wrap: break-word; color: #d4d4d4;">${trace.jsonContent}</pre>
            </div>
        `;
    }).join('');
}

/**
 * Refresh traces display for the current session (called by handleDapTrace).
 */
function renderDapTracesForSession(sessionId) {
    if (!window.currentDapSessionId || window.currentDapSessionId !== sessionId) {
        return;
    }

    const container = document.getElementById('dap-traces-container');
    if (!container) {
        return; // Not in trace view
    }

    const traces = window.dapTracesBySession?.[sessionId] || [];
    container.innerHTML = traces.length > 0 ? renderDapTraces(traces) : '<div style="color: #666;">No traces yet.</div>';

    // Auto-scroll to bottom
    container.scrollTop = container.scrollHeight;
}

/**
 * ============================================
 * GLOBAL DAP SERVERS (Debuggers tab)
 * ============================================
 */

let selectedDapServer = null;
let currentDapServerTab = 'overview'; // overview, install
let dapServerConfigs = {};

/**
 * Load all global DAP servers.
 */
async function loadAllDapServers() {
    try {
        const response = await fetch('/api/admin/dap-servers');
        const dapServers = await response.json();

        // Store in map for easy access
        dapServerConfigs = {};
        dapServers.forEach(server => {
            dapServerConfigs[server.id] = server;
        });

        const container = document.getElementById('dap-servers-list');
        if (!container) {
            console.error('dap-servers-list container not found');
            return;
        }

        if (dapServers.length === 0) {
            container.innerHTML = '<div class="servers-placeholder">No debuggers configured</div>';
            return;
        }

        container.innerHTML = dapServers.map(server => {
            const isActive = selectedDapServer === server.id ? 'active' : '';
            return `
                <div class="server-item ${isActive}" onclick="showDapServerDetails('${server.id}')">
                    <div class="server-name">
                        <span class="server-source-icon">🐛</span>
                        ${server.name}
                    </div>
                    <div class="server-id">${server.id}</div>
                </div>
            `;
        }).join('');

        // Auto-select previously selected server if it exists, otherwise first server
        if (dapServers.length > 0) {
            const previousServerExists = selectedDapServer && dapServers.find(s => s.id === selectedDapServer);
            const serverToShow = previousServerExists ? selectedDapServer : dapServers[0].id;
            showDapServerDetails(serverToShow);
        }
    } catch (error) {
        console.error('Failed to load DAP servers:', error);
    }
}

/**
 * Show details for a global DAP server with Overview/Install tabs.
 */
async function showDapServerDetails(serverId) {
    selectedDapServer = serverId;

    // Re-render server list to update active state
    const dapServers = Object.values(dapServerConfigs);
    const container = document.getElementById('dap-servers-list');
    container.innerHTML = dapServers.map(server => {
        const isActive = selectedDapServer === server.id ? 'active' : '';
        return `
            <div class="server-item ${isActive}" onclick="showDapServerDetails('${server.id}')">
                <div class="server-name">
                    <span class="server-source-icon">🐛</span>
                    ${server.name}
                </div>
                <div class="server-id">${server.id}</div>
            </div>
        `;
    }).join('');

    const server = dapServerConfigs[serverId];
    if (!server) {
        console.error('DAP server not found:', serverId);
        return;
    }

    // Show console column
    const appContainer = document.querySelector('.app-container');
    const consoleColumn = document.querySelector('.console-container');
    consoleColumn.style.display = 'flex';
    appContainer.style.gridTemplateColumns = '400px 1fr';
    consoleColumn.style.gridColumn = '2';

    // Build document selector info
    let docSelectorHTML = '<p style="color: #999;">None configured</p>';
    if (server.documentSelector && server.documentSelector.length > 0) {
        docSelectorHTML = server.documentSelector.map(selector => {
            const parts = [];
            if (selector.language) parts.push(`Language: <code>${selector.language}</code>`);
            if (selector.pattern) parts.push(`Pattern: <code>${selector.pattern}</code>`);
            if (selector.scheme) parts.push(`Scheme: <code>${selector.scheme}</code>`);
            return `<li>${parts.join(', ')}</li>`;
        }).join('');
        docSelectorHTML = `<ul style="margin: 0.5rem 0; padding-left: 1.5rem;">${docSelectorHTML}</ul>`;
    }

    const detailsHTML = `
        <h3 style="margin-top: 0; color: #4ec9b0;">Debug Adapter Information</h3>

        <div style="margin-bottom: 1.5rem;">
            <strong style="color: #569cd6;">Server ID:</strong>
            <p style="margin: 0.25rem 0; color: #d4d4d4;"><code>${server.id}</code></p>
        </div>

        ${server.description ? `
        <div style="margin-bottom: 1.5rem;">
            <strong style="color: #569cd6;">Description:</strong>
            <p style="margin: 0.25rem 0; color: #d4d4d4;">${server.description}</p>
        </div>
        ` : ''}

        <div style="margin-bottom: 1.5rem;">
            <strong style="color: #569cd6;">Supported Languages/Files:</strong>
            ${docSelectorHTML}
        </div>

        <div style="margin-top: 2rem; padding: 1rem; background: #252526; border-left: 3px solid #4ec9b0; border-radius: 4px;">
            <strong>Note:</strong> Debuggers are started on-demand during debug sessions. They are not automatically started with workspaces.
        </div>
    `;

    const html = `
        <div class="console-header">
            <div class="console-title">
                <span class="server-source-icon">🐛</span>
                ${server.name || server.id}
            </div>
            <div class="console-tabs">
                <button class="tab-button ${currentDapServerTab === 'overview' ? 'active' : ''}" onclick="switchDapServerTab('overview')">Overview</button>
                <button class="tab-button ${currentDapServerTab === 'install' ? 'active' : ''}" onclick="switchDapServerTab('install')">Install</button>
            </div>
        </div>
        <div class="tab-content">
            <div id="dap-server-overview-tab" class="tab-panel ${currentDapServerTab === 'overview' ? 'active' : ''}">
                <div class="details-panel" style="padding: 2rem; color: #cccccc; overflow-y: auto;">
                    ${detailsHTML}
                </div>
            </div>
            <div id="dap-server-install-tab" class="tab-panel ${currentDapServerTab === 'install' ? 'active' : ''}">
                <div class="install-panel">
                    <h3>Installer Configuration</h3>
                    <div class="install-info">
                        <p><strong>Debugger:</strong> ${server.name}</p>
                        <p><strong>ID:</strong> ${server.id}</p>
                    </div>
                    <div class="installer-editor">
                        <div class="editor-header">
                            <span>installer.json</span>
                            <div class="editor-actions">
                                <button class="editor-btn" onclick="saveDapInstallerJson('${server.id}')" title="Save">💾 Save</button>
                                <button class="editor-btn" onclick="resetDapInstallerJson('${server.id}')" title="Reset">↻ Reset</button>
                            </div>
                        </div>
                        <textarea id="dap-installer-json-editor" class="json-editor" spellcheck="false"></textarea>
                    </div>
                    <button class="install-button" onclick="runDapInstaller('${server.id}')">▶ Run Installer</button>
                    <div id="dap-install-output" class="install-output"></div>
                </div>
            </div>
        </div>
    `;

    document.getElementById('console-area').innerHTML = html;

    // Load installer.json for this DAP server if on Install tab
    if (currentDapServerTab === 'install') {
        loadDapInstallerJson(server.id);
    }
}

/**
 * Switch between DAP server tabs (Overview/Install).
 */
function switchDapServerTab(tab) {
    currentDapServerTab = tab;
    if (selectedDapServer) {
        showDapServerDetails(selectedDapServer);
    }
}

/**
 * Load installer.json for a DAP server.
 */
async function loadDapInstallerJson(serverId) {
    try {
        const response = await fetch(`/api/admin/dap-servers/${serverId}/installer`);
        if (!response.ok) throw new Error('Failed to load installer.json');

        const installerJson = await response.json();
        const editor = document.getElementById('dap-installer-json-editor');
        if (editor) {
            editor.value = JSON.stringify(installerJson, null, 2);
        }
    } catch (error) {
        console.error('Failed to load DAP installer.json:', error);
        const editor = document.getElementById('dap-installer-json-editor');
        if (editor) {
            editor.value = '// No installer.json found for this debugger';
        }
    }
}

/**
 * Save installer.json for a DAP server.
 */
async function saveDapInstallerJson(serverId) {
    const editor = document.getElementById('dap-installer-json-editor');
    if (!editor) return;

    try {
        const installerJson = JSON.parse(editor.value);

        const response = await fetch(`/api/admin/dap-servers/${serverId}/installer`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(installerJson)
        });

        if (!response.ok) throw new Error('Failed to save installer.json');

        if (window.showAlert) {
            window.showAlert('Success', 'Installer configuration saved successfully.');
        }
    } catch (error) {
        console.error('Failed to save DAP installer.json:', error);
        if (window.showAlert) {
            window.showAlert('Error', 'Failed to save installer.json: ' + error.message);
        }
    }
}

/**
 * Reset installer.json to original.
 */
async function resetDapInstallerJson(serverId) {
    loadDapInstallerJson(serverId);
}

/**
 * Run installer for a DAP server.
 */
async function runDapInstaller(serverId) {
    const outputDiv = document.getElementById('dap-install-output');
    if (!outputDiv) return;

    outputDiv.innerHTML = '<div style="color: #4ec9b0;">Running installer...</div>';

    try {
        const response = await fetch(`/api/admin/dap-servers/${serverId}/install`, {
            method: 'POST'
        });

        if (!response.ok) throw new Error('Installation failed');

        const result = await response.json();
        outputDiv.innerHTML = `
            <div style="color: #4ec9b0;">✓ Installation completed successfully</div>
            <pre style="margin-top: 0.5rem; color: #d4d4d4;">${JSON.stringify(result, null, 2)}</pre>
        `;
    } catch (error) {
        console.error('Failed to run DAP installer:', error);
        outputDiv.innerHTML = `<div style="color: #f48771;">❌ Installation failed: ${error.message}</div>`;
    }
}

// Expose functions globally
window.createNewTestSession = createNewTestSession;
window.launchDapSession = launchDapSession;
window.deleteDapSession = deleteDapSession;
window.selectDapSession = selectDapSession;
window.renderDapTracesForSession = renderDapTracesForSession;
window.loadAllDapServers = loadAllDapServers;
window.showDapServerDetails = showDapServerDetails;
window.switchDapServerTab = switchDapServerTab;
