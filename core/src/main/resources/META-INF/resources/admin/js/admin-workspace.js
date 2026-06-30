        function renderWorkspaces() {
            const container = document.getElementById('workspaces-list');

            if (workspaces.length === 0) {
                container.innerHTML = `
                    <div class="empty-workspaces">
                        <div class="empty-workspaces-title">No Workspaces Yet</div>
                        <div class="empty-workspaces-text">
                            Workspaces appear when an MCP client (Claude Desktop, Bob Shell, etc.)
                            calls an MCP tool with a <code>cwd</code> parameter.
                        </div>
                        <div class="empty-workspaces-text">
                            The <code>cwd</code> (current working directory) identifies the project/workspace
                            and triggers LSP server initialization.
                        </div>
                        <div class="empty-workspaces-hint">
                            💡 Try calling: getDiagnostics(cwd: "/path/to/project", fileUri: "file://...")
                        </div>
                    </div>
                `;

                // Clear servers and console
                selectedWorkspace = null;
                window.selectedWorkspace = null;
                selectedServer = null;
                document.getElementById('servers-list').innerHTML = '<div class="servers-placeholder">No workspaces selected</div>';
                document.getElementById('console-area').innerHTML = `
                    <div class="placeholder">
                        ← Select a workspace and LSP server to view console
                    </div>
                `;

                return;
            }

            container.innerHTML = workspaces.map(ws => {
                // Extract folder name from URI
                const folderName = ws.rootUri.split('/').filter(p => p).pop() || ws.rootUri;

                return `
                <div class="workspace-item ${ws.rootUri === selectedWorkspace ? 'active' : ''}" onclick="selectWorkspace('${ws.rootUri}')">
                    <div style="display: flex; justify-content: space-between; align-items: center;">
                        <div class="workspace-uri" title="${ws.rootUri}" style="flex: 1;">📂 ${folderName}</div>
                        <button class="close-workspace-btn" onclick="event.stopPropagation(); closeWorkspace('${ws.rootUri}')" title="Close workspace and stop all servers">×</button>
                    </div>
                    ${ws.mcpClients && ws.mcpClients.length > 0 ? `
                        <div class="workspace-section">
                            <div class="workspace-section-title">AI Clients</div>
                            ${ws.mcpClients.map(client => {
                                let timeStr = '';
                                if (client.connectedAt) {
                                    try {
                                        const date = new Date(client.connectedAt);
                                        timeStr = date.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' });
                                    } catch (e) {
                                        timeStr = '';
                                    }
                                }
                                const shortId = client.connectionId ? client.connectionId.substring(0, 8) + '...' : '';
                                return `
                                    <div class="workspace-section-item" title="Session: ${client.connectionId || 'N/A'}">
                                        <div>📱 ${client.name}${timeStr ? ` <span class="client-time">@ ${timeStr}</span>` : ''}</div>
                                        ${shortId ? `<div style="font-size: 0.7rem; color: #555; margin-left: 1.5rem; margin-top: 0.15rem;">Session: ${shortId}</div>` : ''}
                                    </div>
                                `;
                            }).join('')}
                        </div>
                    ` : ''}
                </div>
                `;
            }).join('');
        }

        function selectWorkspace(uri) {
            console.log('selectWorkspace called with:', uri);
            console.log('Current workspaces:', workspaces);

            // Only reset server selection if we're changing workspace
            if (selectedWorkspace !== uri) {
                selectedServer = null;
            }

            selectedWorkspace = uri;
            window.selectedWorkspace = uri;

            renderWorkspaces();

            // Find workspace in local data (already received via WebSocket)
            const workspace = workspaces.find(w => w.rootUri === uri);
            console.log('Found workspace in selectWorkspace:', workspace);
            if (workspace) {
                console.log('Rendering servers:', workspace.lspServers);
                renderServers(workspace.lspServers, workspace.dapSessions || [], workspace);
                // renderServers will auto-select a server and call selectServer
                // which will load the console, so don't show placeholder here
            } else {
                console.log('Workspace not found, showing placeholder');
                document.getElementById('servers-list').innerHTML = '<div class="servers-placeholder">No LSP servers</div>';
                showPlaceholder();
            }
        }

        function showConfirmModal(title, message, onConfirm) {
            const titleEl = document.getElementById('confirm-modal-title');
            const messageEl = document.getElementById('confirm-modal-message');
            const modalEl = document.getElementById('confirm-modal');

            if (!titleEl || !messageEl || !modalEl) {
                console.error('Confirm modal elements not found!');
                return;
            }

            titleEl.textContent = title;
            messageEl.innerHTML = message;
            modalEl.classList.add('visible');

            const confirmBtn = document.getElementById('modal-confirm-btn');
            confirmBtn.onclick = () => {
                hideConfirmModal();
                onConfirm();
            };
        }

        function hideConfirmModal() {
            document.getElementById('confirm-modal').classList.remove('visible');
        }

        async function closeWorkspace(uri) {
            const folderName = uri.split('/').filter(p => p).pop() || uri;

            showConfirmModal(
                'Close Workspace',
                `
                    <div style="margin-bottom: 1rem; font-size: 1.05rem;"><strong>⚠️ This will shut down all LSP servers for this workspace</strong></div>
                    <div style="margin-bottom: 0.75rem;">Specifically:</div>
                    <ul style="margin: 0 0 1rem 1.5rem; text-align: left; line-height: 1.6;">
                        <li><strong>Stop all running language servers</strong></li>
                        <li>Disconnect any IDE connections</li>
                        <li>Remove the workspace from memory</li>
                        <li>Clear all cached data</li>
                    </ul>
                    <div style="margin-top: 1rem; padding: 0.75rem; background: rgba(0,122,204,0.1); border-left: 3px solid #007acc; border-radius: 3px;">
                        <div><strong>Workspace:</strong> ${folderName}</div>
                        <div style="margin-top: 0.5rem; font-size: 0.85rem; color: #cccccc;">💡 The workspace will automatically reappear when an MCP client accesses it again.</div>
                    </div>
                `,
                async () => {
                    try {
                        const response = await fetch(`/api/admin/workspaces/${encodeURIComponent(uri)}`, {
                            method: 'DELETE'
                        });

                        if (!response.ok) {
                            throw new Error('Failed to close workspace');
                        }

                        // Reload workspaces list
                        await loadWorkspaces();

                    } catch (error) {
                        console.error('Failed to close workspace:', error);
                        alert('Failed to close workspace: ' + error.message);
                    }
                }
            );
        }

        let lastServersData = null;

        function loadServers(uri) {
            // Find workspace in local data (already received via WebSocket)
            const workspace = workspaces.find(w => w.rootUri === uri);
            if (workspace) {
                // Only re-render if servers data actually changed
                const newServersData = JSON.stringify(workspace.lspServers);
                if (newServersData !== lastServersData) {
                    lastServersData = newServersData;
                    renderServers(workspace.lspServers, workspace.dapSessions || [], workspace);
                }
            } else {
                console.warn('Workspace not found:', uri);
                document.getElementById('servers-list').innerHTML = '<div class="servers-placeholder">Workspace not found</div>';
            }
        }

        function formatStatusLabel(status, externalInstance) {
            const labels = {
                'STARTING': 'Starting',
                'RUNNING': 'Running',
                'STOPPING': 'Stopping',
                'STOPPED': 'Stopped',
                'INSTALLING': 'Installing',
                'INSTALL_FAILED': 'Install Failed',
                'START_FAILED': 'Start Failed',
                'SWITCHING': 'Switching',
                'CONNECTING_TO_IDE': 'Connecting to IDE',
                'CONNECTED_TO_IDE': 'Connected to IDE',
                'DISCONNECTING': 'Disconnecting'
            };

            // If connected to IDE and we have client info, show it
            if (status === 'CONNECTED_TO_IDE' && externalInstance && externalInstance.clientName) {
                const version = externalInstance.clientVersion ? ` ${externalInstance.clientVersion}` : '';
                return `Connected to ${externalInstance.clientName}${version}`;
            }

            return labels[status] || status;
        }

        function renderServers(lspServers, dapSessions = [], workspace = null) {
            const container = document.getElementById('servers-list');
            console.log('renderServers - lspServers:', lspServers, 'dapSessions:', dapSessions);

            if (!container) {
                console.error('servers-list element not found!');
                return;
            }

            // Build workspace header (compact, same level as left sidebar)
            const workspaceName = workspace ? (workspace.rootUri.split('/').filter(p => p).pop() || workspace.rootUri) : '';
            const headerHTML = `
                <div style="padding: 0.5rem 1rem; background: #1e1e1e; border-bottom: 1px solid #3a3a3a;">
                    <div style="font-size: 0.8rem; color: #cccccc; font-weight: 500;">📂 ${workspaceName}</div>
                </div>
            `;

            // Build tabs header
            const tabsHTML = `
                <div style="display: flex; background: #252526; border-bottom: 1px solid #1e1e1e;">
                    <div style="flex: 1; padding: 0.75rem; text-align: center; cursor: pointer; font-weight: ${currentWorkspaceTab === 'servers' ? '600' : '400'}; border-bottom: ${currentWorkspaceTab === 'servers' ? '2px solid #007acc' : '2px solid transparent'};" onclick="switchWorkspaceTab('servers')">Servers</div>
                    <div style="flex: 1; padding: 0.75rem; text-align: center; cursor: pointer; font-weight: ${currentWorkspaceTab === 'debuggers' ? '600' : '400'}; border-bottom: ${currentWorkspaceTab === 'debuggers' ? '2px solid #007acc' : '2px solid transparent'};" onclick="switchWorkspaceTab('debuggers')">Debuggers</div>
                </div>
            `;

            // Render content based on active tab
            let contentHTML = '';
            if (currentWorkspaceTab === 'servers') {
                contentHTML = lspServers.length > 0 ? renderLspServers(lspServers) : '<div class="servers-placeholder">No LSP servers</div>';
            } else {
                // Use global DAP configs (like LSP serverConfigs), not per-workspace
                const dapServers = (typeof window.dapConfigs !== 'undefined') ? window.dapConfigs : [];
                contentHTML = (dapServers.length > 0 || dapSessions.length > 0)
                    ? renderDapServers(dapServers, dapSessions)
                    : '<div class="servers-placeholder">No debug adapters</div>';
            }

            container.innerHTML = headerHTML + tabsHTML + contentHTML;
        }

        function switchWorkspaceTab(tab) {
            currentWorkspaceTab = tab;
            const workspace = workspaces.find(w => w.rootUri === selectedWorkspace);
            if (workspace) {
                renderServers(workspace.lspServers, workspace.dapSessions || [], workspace);
            }
        }

        function renderLspServers(serversRuntime) {
            if (serversRuntime.length === 0) {
                return '';
            }

            // Merge runtime with configs
            // Servers are already merged in handleWorkspacesUpdate()
            const servers = serversRuntime;

            // Calculate contributedBy for all servers
            const contributedByMap = buildContributedByMap(servers);

            // Auto-select server logic:
            // 1. If a server is selected and still exists in the list, keep it selected
            // 2. Otherwise, select first RUNNING server
            // 3. Otherwise, select first server
            if (selectedServer) {
                const stillExists = servers.find(s => s.id === selectedServer.id);
                if (!stillExists) {
                    console.log('Selected server no longer exists, auto-selecting...');
                    selectedServer = null;
                } else {
                    console.log('Keeping selected server:', selectedServer.id);
                }
            }

            if (!selectedServer && servers.length > 0) {
                console.log('Auto-selecting server - selectedServer is null, servers:', servers);
                const runningServer = servers.find(s => s.status === 'RUNNING');
                const serverToSelect = runningServer || servers[0];
                console.log('Server to auto-select:', serverToSelect);
                selectServer(serverToSelect);
            }

            return `
                ${servers.map(server => {
                    // Same HTML as before
                    const isExternal = server.externalInstance != null &&
                                       (server.status === 'CONNECTED_TO_IDE' || server.status === 'CONNECTING_TO_IDE');
                    const serverClass = isExternal ? 'server-item-external' : 'server-item-managed';
                    const extensionClass = server.isExtension ? 'server-extension' : '';
                    const extensionBadge = server.isExtension ? ' <span style="color: #999999; font-size: 0.85em;">(Extension)</span>' : '';

                    let actions = '';
                    if (!server.isExtension) {
                        if (isExternal) {
                            actions = `
                                <button class="server-action-btn server-action-disconnect"
                                        onclick='event.stopPropagation(); disconnectFromIdeAction("${server.id}")'
                                        title="Disconnect from IDE">⏏</button>
                            `;
                        } else {
                            if (server.status === 'RUNNING' || server.status === 'STARTING') {
                                actions = `
                                    <button class="server-action-btn" onclick='event.stopPropagation(); restartServerAction("${server.id}")' title="Restart">↻</button>
                                    <button class="server-action-btn" onclick='event.stopPropagation(); stopServerAction("${server.id}")' title="Stop">■</button>
                                `;
                            } else if (server.status === 'STOPPED') {
                                actions = `
                                    <button class="server-action-btn" onclick='event.stopPropagation(); startManagedServerAction("${server.id}")' title="Start MCP-managed server">▶</button>
                                    <button class="server-action-btn" onclick='event.stopPropagation(); connectToIdeAction("${server.id}")' title="Try to connect to IDE instance">🔗</button>
                                `;
                            }
                        }
                    }

                    const sourceIcon = isExternal ? '🔗' : (server.isExtension ? '🧩' : '🚀');
                    const sourceLabel = isExternal
                        ? `Connected to IDE (port ${server.externalInstance.port}, PID ${server.externalInstance.pid})`
                        : (server.isExtension ? 'Extension' : 'Managed by MCP');

                    let ideInfo = '';
                    if (isExternal && server.externalInstance) {
                        ideInfo = `
                            <span class="server-ide-info">
                                <span title="Port">:${server.externalInstance.port}</span>
                                <span title="Process ID">PID ${server.externalInstance.pid}</span>
                            </span>
                        `;
                    }

                    const tooltipText = server.command ? `Command: ${server.command}` : '';
                    const contributedInfo = formatContributeInfo(server, contributedByMap);

                    return `
                        <div class="server-item ${serverClass} ${extensionClass} ${selectedServer?.id === server.id ? 'active' : ''}"
                             onclick='selectServer(${JSON.stringify(server)})'
                             ${tooltipText ? `title="${tooltipText.replace(/"/g, '&quot;')}"` : ''}>
                            <div class="server-name">
                                <span class="server-source-icon" title="${sourceLabel}">${sourceIcon}</span>
                                ${server.name}${extensionBadge}
                            </div>
                            <div class="server-id" ${contributedInfo.tooltip ? `title="${contributedInfo.tooltip}"` : ''}>${server.id}${contributedInfo.text}</div>
                            <div>
                                <span class="status-badge ${server.status === 'RUNNING' && !server.isReady ? 'status-running-not-ready' : 'status-' + server.status.toLowerCase()}">${formatStatusLabel(server.status, server.externalInstance)}</span>
                                ${server.statusMessage ? `<span class="server-status-message" style="color: #888; font-size: 0.85rem; margin-left: 0.5rem;">${escapeHtml(server.statusMessage)}</span>` : ''}
                                ${!server.isExtension ? ideInfo : ''}
                                ${!server.isExtension && server.pid ? `<span class="server-ide-info"><span title="Process ID">${server.pid}</span></span>` : ''}
                            </div>
                            <div class="server-actions">
                                ${actions}
                            </div>
                        </div>
                    `;
                }).join('')}
            `;
        }

        function renderDapServers(dapServers, dapSessions) {
            if (dapServers.length === 0 && dapSessions.length === 0) {
                return '';
            }

            // Group sessions by server ID
            const sessionsByServer = {};
            dapSessions.forEach(session => {
                if (!sessionsByServer[session.dapServerId]) {
                    sessionsByServer[session.dapServerId] = [];
                }
                sessionsByServer[session.dapServerId].push(session);
            });

            return `
                ${dapServers.map(server => {
                    const sessions = sessionsByServer[server.id] || [];
                    const isInstalled = server.installed;

                    // Actions for debugger (like LSP servers)
                    let actions = `
                        <button class="server-action-btn" onclick='event.stopPropagation(); createNewTestSession("${server.id}")' title="New Test Launch">+</button>
                    `;

                    return `
                        <div class="server-item" style="opacity: 0.8; cursor: default;">
                            <div class="server-name">
                                <span class="server-source-icon">🐛</span>
                                ${server.name}
                            </div>
                            <div class="server-id">${server.id}</div>
                            <div>
                                <span class="status-badge ${isInstalled ? 'status-running' : 'status-stopped'}">${isInstalled ? 'Installed' : 'Not Installed'}</span>
                            </div>
                            <div class="server-actions">
                                ${actions}
                            </div>
                        </div>
                        ${sessions.map(session => {
                            const stateIcon = session.state === 'RUNNING' ? '▶️' : session.state === 'PAUSED' ? '⏸️' : '⏹️';
                            const statusClass = session.state === 'RUNNING' ? 'status-running' : session.state === 'PAUSED' ? 'status-running-not-ready' : 'status-stopped';

                            // Actions for sessions
                            let sessionActions = '';
                            if (session.state === 'CREATED') {
                                sessionActions = `<button class="server-action-btn" onclick='event.stopPropagation(); deleteDapSession("${session.sessionId}")' title="Delete">🗑️</button>`;
                            } else if (session.state === 'RUNNING') {
                                sessionActions = `<button class="server-action-btn" onclick='event.stopPropagation(); pauseDapSession("${session.sessionId}")' title="Pause">⏸</button>`;
                            }

                            return `
                                <div class="server-item" style="margin-left: 1.5rem; border-left: 2px solid #3a3a3a;" onclick="selectDapSession('${session.sessionId}')">
                                    <div class="server-name">
                                        <span class="server-source-icon">${stateIcon}</span>
                                        ${session.sessionName}
                                    </div>
                                    <div class="server-id">${session.language}</div>
                                    <div>
                                        <span class="status-badge ${statusClass}">${session.state}</span>
                                    </div>
                                    <div class="server-actions">
                                        ${sessionActions}
                                    </div>
                                </div>
                            `;
                        }).join('')}
                    `;
                }).join('')}
            `;
        }

        function selectDapSession(sessionId) {
            // Forward to admin-dap.js
            if (typeof window.selectDapSession === 'function') {
                window.selectDapSession(sessionId);
            }
        }

        function selectServer(server) {
            const wasAlreadySelected = selectedServer && selectedServer.id === server.id;
            selectedServer = server;
            const workspace = workspaces.find(w => w.rootUri === selectedWorkspace);
            renderServers(workspace?.lspServers || [], workspace?.dapSessions || [], workspace);

            // Only reload console if switching to a different server
            if (!wasAlreadySelected) {
                loadConsole(server);
            }
        }

        function showPlaceholder() {
            document.getElementById('console-area').innerHTML = `
                <div class="placeholder">
                    ← Select an LSP server to view console
                </div>
            `;
        }

        let currentTraceLevel = 'verbose';
        let currentServerId = null;

        async function changeTraceLevel(level) {
            if (!currentServerId) {
                console.error('No server selected');
                return;
            }

            try {
                const response = await fetch(`/api/admin/config/servers/${currentServerId}/trace`, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ trace: level })
                });

                if (!response.ok) {
                    throw new Error('Failed to save trace level');
                }

                currentTraceLevel = level;
                console.log('Trace level changed to:', level, 'for server:', currentServerId);

                // Show/hide fold button based on level
                const foldButton = document.getElementById('fold-button');
                if (foldButton) {
                    foldButton.style.display = (level === 'messages') ? 'none' : 'inline-block';
                }

                // Re-render console with new filter
                renderConsole();
            } catch (error) {
                console.error('Failed to change trace level:', error);
                alert('Failed to save trace level: ' + error.message);
            }
        }

        /**
         * Filter traces based on trace level.
         * - off: show nothing
         * - messages: show all messages (header only, no body)
         * - verbose: show everything (header + body)
         */
        function shouldShowTrace(trace, level) {
            if (level === 'off') {
                return false;
            }

            // Both 'messages' and 'verbose' show all traces
            // The difference is in how they're displayed (header only vs header+body)
            return true;
        }

        async function loadConsole(server) {
            // Check if server has contributions
            const workspace = workspaces.find(w => w.rootUri === selectedWorkspace);
            const allServers = workspace ? workspace.lspServers : [];
            const hasContributions = (server.contributions && Object.keys(server.contributions).length > 0) ||
                                    buildContributedByMap(allServers)[server.id]?.length > 0;

            // Extensions don't have traces - default to overview
            if (server.isExtension && currentConsoleTab === 'traces') {
                currentConsoleTab = 'overview';
            }

            // If current tab is contributions but there are none, switch to appropriate default
            if (!hasContributions && currentConsoleTab === 'contributions') {
                currentConsoleTab = server.isExtension ? 'overview' : 'traces';
            }

            // Build icon for console title
            const isExternal = server.externalInstance != null &&
                              (server.status === 'CONNECTED_TO_IDE' || server.status === 'CONNECTING_TO_IDE');
            const titleIcon = isExternal ? '🔗' : (server.isExtension ? '🧩' : '🚀');

            // Setup console UI with tabs
            document.getElementById('console-area').innerHTML = `
                <div class="console-wrapper">
                    <div class="console-header">
                        <div class="console-title">
                            <span class="server-source-icon">${titleIcon}</span>
                            ${server.name}
                            <span class="status-indicator" id="sse-status"></span>
                        </div>
                        <div class="console-tabs">
                            ${!server.isExtension ? `<button class="tab-button ${currentConsoleTab === 'traces' ? 'active' : ''}" onclick="switchConsoleTab('traces')">Traces</button>` : ''}
                            <button class="tab-button ${currentConsoleTab === 'overview' ? 'active' : ''}" onclick="switchConsoleTab('overview')">Overview</button>
                            ${hasContributions ? `<button class="tab-button ${currentConsoleTab === 'contributions' ? 'active' : ''}" onclick="switchConsoleTab('contributions')">Contributions</button>` : ''}
                            <button class="tab-button ${currentConsoleTab === 'install' ? 'active' : ''}" onclick="switchConsoleTab('install')">Install</button>
                        </div>
                        <div class="console-controls" id="traces-controls">
                            <label style="color: #cccccc; font-size: 0.85rem;">
                                Trace Level:
                                <select id="trace-level" onchange="changeTraceLevel(this.value)" style="margin-left: 0.5rem; background: #3e3e42; color: #cccccc; border: 1px solid #555; padding: 0.25rem 0.5rem; border-radius: 3px;">
                                    <option value="off">Off</option>
                                    <option value="messages">Messages</option>
                                    <option value="verbose" selected>Verbose</option>
                                </select>
                            </label>
                            <button onclick="toggleAllTraces()" id="fold-button">Unfold All</button>
                            <button onclick="clearConsole()">Clear</button>
                        </div>
                    </div>
                    <div class="tab-content">
                        <div id="traces-tab" class="tab-panel ${currentConsoleTab === 'traces' ? 'active' : ''}">
                            <div class="console" id="console-output" tabindex="0"></div>
                        </div>
                        <div id="overview-tab" class="tab-panel ${currentConsoleTab === 'overview' ? 'active' : ''}">
                            <div class="details-panel" id="overview-content">
                                <p>Loading...</p>
                            </div>
                        </div>
                        ${hasContributions ? `
                        <div id="contributions-tab" class="tab-panel ${currentConsoleTab === 'contributions' ? 'active' : ''}" style="overflow-y: auto;">
                            <div id="workspace-diagram-container" style="width: 100%; height: 400px; background: #1e1e1e; border-bottom: 1px solid #333;"></div>
                            <div class="details-panel" id="contributions-content" style="padding: 2rem; color: #cccccc;">
                                <p>Loading...</p>
                            </div>
                        </div>
                        ` : ''}
                        <div id="install-tab" class="tab-panel ${currentConsoleTab === 'install' ? 'active' : ''}">
                            <div class="install-panel">
                                <h3>Installer Configuration</h3>
                                <div class="install-info">
                                    <p><strong>Server:</strong> ${server.name}</p>
                                    <p><strong>ID:</strong> ${server.id}</p>
                                </div>
                                <div class="installer-editor">
                                    <div class="editor-header">
                                        <span>installer.json</span>
                                        <div class="editor-actions">
                                            <button class="editor-btn" onclick="saveInstallerJson('${server.id}')" title="Save">💾 Save</button>
                                            <button class="editor-btn" onclick="resetInstallerJson('${server.id}')" title="Reset">↻ Reset</button>
                                        </div>
                                    </div>
                                    <textarea id="installer-json-editor" class="json-editor" spellcheck="false"></textarea>
                                </div>
                                <button class="install-button" onclick="runInstaller('${server.id}')">▶ Run Installer</button>
                                <div id="install-output" class="install-output"></div>
                            </div>
                        </div>
                    </div>
                </div>
            `;

            currentServerId = server.id;

            // Store servers data for diagram rendering
            const currentWorkspace = workspaces.find(w => w.rootUri === selectedWorkspace);
            if (currentWorkspace) {
                window.currentWorkspaceDiagramServers = currentWorkspace.lspServers;
                window.currentWorkspaceDiagramServerId = server.id;
            }

            // If contributions tab is active, render diagram immediately
            if (currentConsoleTab === 'contributions' && currentWorkspace) {
                setTimeout(() => renderWorkspaceDiagram(currentWorkspace.lspServers, server.id), 100);
            }

            // Load and initialize trace level selector with saved value
            try {
                const traceLevelResponse = await fetch(`/api/admin/config/servers/${server.id}/trace`);
                const traceLevelData = await traceLevelResponse.json();
                currentTraceLevel = traceLevelData.trace || 'verbose';

                const traceLevelSelect = document.getElementById('trace-level');
                if (traceLevelSelect) {
                    traceLevelSelect.value = currentTraceLevel;
                }

                // Show/hide fold button based on level
                const foldButton = document.getElementById('fold-button');
                if (foldButton) {
                    foldButton.style.display = (currentTraceLevel === 'messages') ? 'none' : 'inline-block';
                }
            } catch (error) {
                console.error('Failed to load trace level:', error);
            }

            // Load traces for specific workspace + server
            try {
                const encodedWorkspace = encodeURIComponent(selectedWorkspace);

                console.log('loadConsole - serverId:', server.id, 'existing traces:', tracesByServer[server.id]?.length || 0);

                // Only load from API if we don't have traces for this server yet
                if (!tracesByServer[server.id] || tracesByServer[server.id].length === 0) {
                    console.log('Loading traces from API for server:', server.id);
                    const response = await fetch(`/api/admin/traces/workspace/${encodedWorkspace}/server/${server.id}?limit=50`);
                    const loadedTraces = await response.json();
                    tracesByServer[server.id] = loadedTraces || [];
                    console.log('Loaded', loadedTraces.length, 'traces for', server.id);
                } else {
                    console.log('Using cached traces for', server.id);
                }

                renderConsole();
                // WebSocket now handles real-time trace updates
            } catch (error) {
                console.error('Failed to load traces:', error);
            }

            // Load server details
            loadServerDetails(server.id);

            // Load installer.json
            loadInstallerJson(server.id);
        }

        let originalInstallerJson = null;

        async function loadInstallerJson(serverId) {
            try {
                const editor = document.getElementById('installer-json-editor');
                if (!editor) {
                    console.warn('installer-json-editor element not found, skipping load');
                    return;
                }

                const response = await fetch(`/api/admin/servers/${serverId}/installer`);
                if (!response.ok) {
                    editor.value = '// No installer.json found';
                    return;
                }

                const installerJson = await response.json();
                const formatted = JSON.stringify(installerJson, null, 2);
                originalInstallerJson = formatted;

                editor.value = formatted;
            } catch (error) {
                console.error('Failed to load installer.json:', error);
                const editor = document.getElementById('installer-json-editor');
                if (editor) {
                    editor.value = `// Error loading installer.json: ${error.message}`;
                }
            }
        }

        async function saveInstallerJson(serverId) {
            const editor = document.getElementById('installer-json-editor');
            const content = editor.value;

            try {
                // Validate JSON
                JSON.parse(content);

                const response = await fetch(`/api/admin/servers/${serverId}/installer`, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: content
                });

                if (response.ok) {
                    originalInstallerJson = content;
                    showAlert('Success', 'Installer configuration saved successfully.');
                } else {
                    const error = await response.text();
                    showAlert('Error', `Failed to save: ${error}`);
                }
            } catch (error) {
                showAlert('Error', `Invalid JSON: ${error.message}`);
            }
        }

        function resetInstallerJson(serverId) {
            if (originalInstallerJson) {
                document.getElementById('installer-json-editor').value = originalInstallerJson;
            } else {
                loadInstallerJson(serverId);
            }
        }

        async function loadServerDetails(serverId) {
            console.log('loadServerDetails called for:', serverId);
            try {
                const detailsContent = document.getElementById('overview-content');
                if (!detailsContent) {
                    console.warn('details-content element not found, skipping load');
                    return;
                }
                console.log('detailsContent found, fetching details...');

                const response = await fetch(`/api/admin/servers/${serverId}/details`);
                if (!response.ok) {
                    throw new Error('Failed to load server details');
                }

                const details = await response.json();

                // Get all servers for contributedBy calculation
                const allServers = workspaces.find(w => w.rootUri === selectedWorkspace)?.lspServers || [];

                // Use shared rendering function
                detailsContent.innerHTML = `
                    <h3>Server Configuration</h3>
                    ${renderServerDetailsHTML(details)}
                `;

                // Update contributions tab
                const contributionsContent = document.getElementById('contributions-content');
                if (contributionsContent) {
                    const contributionsHTML = formatContributionsSection(details, allServers);
                    contributionsContent.innerHTML = contributionsHTML || '<p class="detail-value">No contributions</p>';
                }
            } catch (error) {
                console.error('Failed to load server details:', error);
                const detailsContent = document.getElementById('overview-content');
                if (detailsContent) {
                    detailsContent.innerHTML = `<p class="error">Failed to load server details: ${error.message}</p>`;
                }
            }
        }

        /**
         * Render complete server details HTML (shared between Servers and Workspaces tabs).
         * Does NOT include contributions (now in separate tab).
         * @param {Object} server - The server config/details object
         * @returns {string} HTML string
         */
        function renderServerDetailsHTML(server) {
            // Format command (can be string or object)
            let commandStr = '';
            if (server.command) {
                if (typeof server.command === 'string') {
                    commandStr = server.command;
                } else if (typeof server.command === 'object') {
                    commandStr = JSON.stringify(server.command, null, 2);
                }
            }

            return `
                <div class="details-section">
                    <h4>General Information</h4>
                    <div class="detail-item">
                        <span class="detail-label">ID:</span>
                        <span class="detail-value">${server.id}</span>
                    </div>
                    <div class="detail-item">
                        <span class="detail-label">Name:</span>
                        <span class="detail-value">${server.name || 'N/A'}</span>
                    </div>
                    ${server.description ? `
                    <div class="detail-item">
                        <span class="detail-label">Description:</span>
                        <span class="detail-value">${server.description}</span>
                    </div>
                    ` : ''}
                </div>

                ${server.documentSelector && server.documentSelector.length > 0 ? `
                <div class="details-section">
                    <h4>Document Selector</h4>
                    ${server.documentSelector.map(selector => `
                        <div class="selector-item">
                            ${selector.language ? `<span class="selector-tag">language: ${selector.language}</span>` : ''}
                            ${selector.scheme ? `<span class="selector-tag">scheme: ${selector.scheme}</span>` : ''}
                            ${selector.pattern ? `<span class="selector-tag">pattern: ${selector.pattern}</span>` : ''}
                        </div>
                    `).join('')}
                </div>
                ` : ''}

                ${commandStr ? `
                <div class="details-section">
                    <h4>Command</h4>
                    <pre class="command-preview">${commandStr}</pre>
                </div>
                ` : ''}

                ${server.initializationOptions && Object.keys(server.initializationOptions).length > 0 ? `
                <div class="details-section">
                    <h4>Initialization Options</h4>
                    <pre class="detail-value">${JSON.stringify(server.initializationOptions, null, 2)}</pre>
                </div>
                ` : ''}
            `;
        }

        /**
         * Format contributions section for server details view.
         * Shows both "Contributes To" and "Contributed By" with contribution details.
         * @param {Object} server - The server object with contributions
         * @param {Array} allServers - All servers list for calculating contributedBy (optional, will fetch from workspace if not provided)
         */
        function formatContributionsSection(server, allServers = null) {
            console.log('formatContributionsSection - server:', server.id, 'contributions:', server.contributions);
            const contributesTo = server.contributions ? Object.keys(server.contributions) : [];

            // Calculate contributedBy from all servers
            if (!allServers) {
                allServers = workspaces.find(w => w.rootUri === selectedWorkspace)?.lspServers || [];
            }
            const contributedByMap = buildContributedByMap(allServers);
            const contributedBy = contributedByMap[server.id] || [];
            console.log('  contributesTo:', contributesTo, 'contributedBy:', contributedBy);

            if (contributesTo.length === 0 && contributedBy.length === 0) {
                return ''; // No contributions section if nothing to show
            }

            let html = '<div class="details-section"><h4>Contributions</h4>';

            // Show "Contributes To" section
            if (contributesTo.length > 0) {
                html += '<div class="contribution-subsection">';
                html += '<h5 style="color: #4ec9b0; margin-bottom: 0.5rem;">→ Contributes To</h5>';

                for (const targetServerId of contributesTo) {
                    const contributionData = server.contributions[targetServerId];
                    html += `<div class="contribution-target" style="margin-bottom: 1rem;">`;
                    html += `<div style="font-weight: bold; color: #dcdcaa; margin-bottom: 0.25rem;">${targetServerId}</div>`;

                    // Show each contribution type (bundles, classpath, bindRequest, etc.)
                    for (const [type, items] of Object.entries(contributionData)) {
                        if (items && items.length > 0) {
                            html += `<div style="margin-left: 1rem; margin-bottom: 0.5rem;">`;
                            html += `<span style="color: #888;">${type}:</span>`;
                            html += `<ul style="margin: 0.25rem 0 0 1.5rem; padding: 0; color: #aaa; font-size: 0.9rem;">`;
                            items.forEach(item => {
                                const displayValue = typeof item === 'string' ? item : JSON.stringify(item);
                                const isError = displayValue.startsWith('ERROR:');
                                const cleanValue = isError ? displayValue.substring(6) : displayValue;
                                const style = isError
                                    ? 'color: #ff6b6b; font-weight: bold; cursor: help;'
                                    : '';
                                const title = isError ? 'File not found or pattern did not match any files' : '';
                                html += `<li style="margin-bottom: 0.2rem; word-break: break-all; ${style}" ${title ? `title="${title}"` : ''}>${escapeHtml(cleanValue)}</li>`;
                            });
                            html += `</ul></div>`;
                        }
                    }
                    html += `</div>`;
                }
                html += '</div>';
            }

            // Show "Contributed By" section grouped by contribution type
            if (contributedBy.length > 0) {
                html += '<div class="contribution-subsection" style="margin-top: 1rem;">';
                html += '<h5 style="color: #ce9178; margin-bottom: 0.5rem;">← Contributed By</h5>';

                // Group contributions by type (bundles, bindRequest, classpath, etc.)
                const contributionsByType = {};

                contributedBy.forEach(contributorServerId => {
                    // Find the contributor server in allServers to get its contributions
                    const contributorServer = allServers.find(s => s.id === contributorServerId);
                    if (!contributorServer || !contributorServer.contributions) {
                        return;
                    }

                    // Get what this contributor gives to the current server
                    const contributionData = contributorServer.contributions[server.id];
                    if (!contributionData) {
                        return;
                    }

                    // Group by type
                    for (const [type, items] of Object.entries(contributionData)) {
                        if (items && items.length > 0) {
                            if (!contributionsByType[type]) {
                                contributionsByType[type] = [];
                            }
                            items.forEach(item => {
                                contributionsByType[type].push({
                                    server: contributorServerId,
                                    value: item
                                });
                            });
                        }
                    }
                });

                // Display grouped by type
                for (const [type, contributions] of Object.entries(contributionsByType)) {
                    html += `<div style="margin-bottom: 1rem;">`;
                    html += `<div style="font-weight: bold; color: #888; margin-bottom: 0.5rem;">${type} <span style="color: #666;">(Total: ${contributions.length})</span></div>`;
                    html += `<div style="margin-left: 1rem;">`;

                    contributions.forEach(contrib => {
                        const displayValue = typeof contrib.value === 'string' ? contrib.value : JSON.stringify(contrib.value);
                        const isError = displayValue.startsWith('ERROR:');
                        const cleanValue = isError ? displayValue.substring(6) : displayValue;
                        const valueStyle = isError
                            ? 'word-break: break-all; color: #ff6b6b; font-weight: bold; cursor: help;'
                            : 'word-break: break-all;';
                        const title = isError ? 'File not found or pattern did not match any files' : '';
                        html += `<div style="margin-bottom: 0.3rem; color: #aaa; font-size: 0.9rem;">`;
                        html += `<span style="display: inline-block; min-width: 120px; color: #dcdcaa;">${contrib.server}</span>`;
                        html += `<span style="color: #569cd6;">•</span> `;
                        html += `<span style="${valueStyle}" ${title ? `title="${title}"` : ''}>${escapeHtml(cleanValue)}</span>`;
                        html += `</div>`;
                    });

                    html += `</div></div>`;
                }

                html += '</div>';
            }

            html += '</div>';
            return html;
        }

        function switchConsoleTab(tabName) {
            currentConsoleTab = tabName; // Save current tab

            // Update tab buttons
            document.querySelectorAll('.tab-button').forEach(btn => {
                btn.classList.remove('active');
            });
            event.target.classList.add('active');

            // Update tab panels
            document.querySelectorAll('.tab-panel').forEach(panel => {
                panel.classList.remove('active');
            });
            document.getElementById(tabName + '-tab').classList.add('active');

            // Show/hide controls
            const tracesControls = document.getElementById('traces-controls');
            if (tracesControls) {
                tracesControls.style.display = tabName === 'traces' ? 'flex' : 'none';
            }

            // Render diagram when switching to contributions tab
            if (tabName === 'contributions' && window.currentWorkspaceDiagramServers) {
                renderWorkspaceDiagram(window.currentWorkspaceDiagramServers, window.currentWorkspaceDiagramServerId);
            }
        }

        function switchServerTab(tabName) {
            currentServerTab = tabName; // Save current tab

            // Update tab buttons
            document.querySelectorAll('.tab-button').forEach(btn => {
                btn.classList.remove('active');
            });
            event.target.classList.add('active');

            // Update tab panels
            document.querySelectorAll('.tab-panel').forEach(panel => {
                panel.classList.remove('active');
            });
            document.getElementById('server-' + tabName + '-tab').classList.add('active');

            // Render diagram when switching to contributions tab
            if (tabName === 'contributions' && window.currentDiagramServers) {
                renderServerDiagram(window.currentDiagramServers, window.currentDiagramServerId);
            }
        }

        async function runInstaller(serverId) {
            const output = document.getElementById('install-output');
            output.innerHTML = `
                <div class="install-status">
                    <div class="spinner"></div>
                    <p>Running installer...</p>
                    <p class="hint">Check the <strong>Traces</strong> tab for real-time progress.</p>
                </div>
            `;

            try {
                const encodedWorkspace = encodeURIComponent(selectedWorkspace);
                const response = await fetch(`/api/admin/install/${encodedWorkspace}/${serverId}`, {
                    method: 'POST'
                });

                const result = await response.json();

                if (response.ok) {
                    output.innerHTML = `
                        <div class="install-success">
                            <h4>✓ Installation Successful</h4>
                            <p><strong>Status:</strong> ${result.status}</p>
                            <p><strong>Message:</strong> ${result.message}</p>
                            ${result.serverHome ? `<p><strong>Server Home:</strong> <code>${result.serverHome}</code></p>` : ''}
                            <p class="hint">Check the <strong>Traces</strong> tab for installation logs.</p>
                        </div>
                    `;

                    // Refresh server list to show updated status
                    setTimeout(() => loadWorkspaces(), 1000);
                } else {
                    output.innerHTML = `
                        <div class="install-error">
                            <h4>✗ Installation Failed</h4>
                            <p><strong>Status:</strong> ${result.status}</p>
                            <p><strong>Message:</strong> ${result.message}</p>
                            ${result.error ? `
                                <div class="error-details">
                                    <strong>Error Details:</strong>
                                    <pre>${result.error}</pre>
                                </div>
                            ` : ''}
                            <p class="hint">Check the <strong>Traces</strong> tab for detailed error logs.</p>
                        </div>
                    `;
                }
            } catch (error) {
                console.error('Failed to run installer:', error);
                output.innerHTML = `
                    <div class="install-error">
                        <h4>✗ Request Failed</h4>
                        <p><strong>Error:</strong> ${error.message}</p>
                        <p class="hint">Could not communicate with the server.</p>
                    </div>
                `;
            }
        }


        function renderConsole() {
            const container = document.getElementById('console-output');
            if (!container) return;

            console.log('renderConsole - currentServerId:', currentServerId);
            console.log('tracesByServer keys:', Object.keys(tracesByServer));
            console.log('mcpTracesByClient keys:', Object.keys(mcpTracesByClient));

            // Get traces for current server
            const traces = tracesByServer[currentServerId] || [];
            console.log('Traces for', currentServerId, ':', traces.length);
            if (traces.length > 0) {
                console.log('First trace:', traces[0]);
            }

            // Filter traces based on current level
            const filteredTraces = traces.filter(trace => shouldShowTrace(trace, currentTraceLevel));

            if (filteredTraces.length === 0) {
                const message = currentTraceLevel === 'off'
                    ? 'Traces are disabled (level: off)'
                    : 'No LSP trace messages yet.';
                container.innerHTML = `<div style="text-align: center; padding: 2rem; color: #858585;">${message}</div>`;
                return;
            }

            container.innerHTML = filteredTraces.map((trace, index) => {
                const content = trace.jsonContent;

                // Parse the trace: first line is header, rest is body
                const lines = content.split('\n');
                const headerLine = lines[0]; // [Trace - HH:mm:ss] ...

                // Detect if this is an error trace
                const isError = headerLine.startsWith('[Error');
                const headerColor = isError ? '#ff6b6b' : '#cccccc';

                // Messages mode: show only header line, no folding
                if (currentTraceLevel === 'messages') {
                    return `
                        <div class="trace-line">
                            <div style="padding: 0.25rem; font-family: 'Consolas', 'Monaco', monospace; font-size: 0.85rem; color: ${headerColor};">${escapeHtml(headerLine)}</div>
                        </div>
                    `;
                }

                // Verbose mode: header + body folded by default
                const bodyLines = lines.slice(1);
                const body = bodyLines.join('\n').trim();
                const hasBody = body.length > 0;

                // If no body, display like messages mode (no toggle arrow)
                if (!hasBody) {
                    return `
                        <div class="trace-line">
                            <div style="padding: 0.25rem; font-family: 'Consolas', 'Monaco', monospace; font-size: 0.85rem; color: ${headerColor};">${escapeHtml(headerLine)}</div>
                        </div>
                    `;
                }

                // Filter out trailing empty lines
                let trimmedBodyLines = bodyLines;
                while (trimmedBodyLines.length > 0 && trimmedBodyLines[trimmedBodyLines.length - 1].trim() === '') {
                    trimmedBodyLines = trimmedBodyLines.slice(0, -1);
                }

                // Colorize stack trace lines in body
                let bodyHtml = '';
                for (let i = 0; i < trimmedBodyLines.length; i++) {
                    const line = trimmedBodyLines[i];
                    const trimmed = line.trim();
                    if (isError && trimmed.startsWith('at ') && trimmed.includes('(') && trimmed.includes(')')) {
                        bodyHtml += `<span style="color: #ff6b6b;">${escapeHtml(line)}</span>`;
                    } else {
                        bodyHtml += escapeHtml(line);
                    }
                    // Add newline except for last line
                    if (i < trimmedBodyLines.length - 1) {
                        bodyHtml += '\n';
                    }
                }

                const fullContent = headerLine + '\n' + body;

                return `
                    <div class="trace-line" onmouseenter="showTooltip(event, ${index}, true)" onmouseleave="hideTooltip(${index})">
                        <div class="trace-header folded" id="header-${index}"
                             onmousedown="onHeaderMouseDown(${index})"
                             onmouseup="onHeaderMouseUp(${index})"
                             style="padding: 0.25rem; font-family: 'Consolas', 'Monaco', monospace; font-size: 0.85rem;">
                            <span class="trace-toggle" id="toggle-${index}" style="margin-right: 0.5rem;">▶</span>
                            <span class="trace-header-text" style="color: ${headerColor};">${escapeHtml(headerLine)}</span>
                        </div>
                        <div class="trace-body collapsed" id="body-${index}" style="font-family: 'Consolas', 'Monaco', monospace; font-size: 0.85rem; color: #cccccc; white-space: pre-wrap;">${bodyHtml}</div>
                        <div class="trace-tooltip" id="tooltip-${index}">${escapeHtml(fullContent)}</div>
                    </div>
                `;
            }).join('');

            container.scrollTop = container.scrollHeight;
        }

        function escapeHtml(text) {
            const div = document.createElement('div');
            div.textContent = text;
            return div.innerHTML;
        }

        function showTooltip(event, index, isFolded) {
            if (!isFolded) return;

            const body = document.getElementById('body-' + index);
            if (!body || !body.classList.contains('collapsed')) return;

            const tooltip = document.getElementById('tooltip-' + index);
            if (!tooltip) return;

            // Position tooltip near mouse
            const x = event.clientX + 15;
            const y = event.clientY + 15;

            tooltip.style.left = x + 'px';
            tooltip.style.top = y + 'px';
            tooltip.style.display = 'block';
        }

        function hideTooltip(index) {
            const tooltip = document.getElementById('tooltip-' + index);
            if (tooltip) {
                tooltip.style.display = 'none';
            }
        }

        let mouseDownTime = 0;
        let mouseDownIndex = -1;

        function onHeaderMouseDown(index) {
            mouseDownTime = Date.now();
            mouseDownIndex = index;
        }

        function onHeaderMouseUp(index) {
            // Si c'est un click rapide (< 200ms) et au même endroit, toggle
            const timeDiff = Date.now() - mouseDownTime;
            if (timeDiff < 200 && mouseDownIndex === index) {
                // Vérifier si du texte a été sélectionné
                const selection = window.getSelection();
                if (!selection || selection.toString().length === 0) {
                    toggleTrace(index);
                }
            }
            mouseDownIndex = -1;
        }

        function toggleTrace(index) {
            const body = document.getElementById('body-' + index);
            const toggle = document.getElementById('toggle-' + index);
            const header = document.getElementById('header-' + index);
            const traceLine = header.parentElement;

            if (body.classList.contains('expanded')) {
                body.classList.remove('expanded');
                body.classList.add('collapsed');
                toggle.textContent = '▶';
                header.classList.add('folded');

                // Ajouter le tooltip pour les traces pliées
                if (!traceLine.querySelector('.trace-tooltip')) {
                    const headerText = header.querySelector('.trace-header-text').textContent;
                    const bodyText = body.textContent;
                    const fullContent = headerText + '\n' + bodyText;

                    const tooltip = document.createElement('div');
                    tooltip.className = 'trace-tooltip';
                    tooltip.id = 'tooltip-' + index;
                    tooltip.textContent = fullContent;
                    traceLine.appendChild(tooltip);

                    // Ajouter les event listeners
                    traceLine.onmouseenter = (e) => showTooltip(e, index, true);
                    traceLine.onmouseleave = () => hideTooltip(index);
                }
            } else {
                body.classList.remove('collapsed');
                body.classList.add('expanded');
                toggle.textContent = '▼';
                header.classList.remove('folded');

                // Retirer le tooltip pour les traces dépliées
                const tooltip = traceLine.querySelector('.trace-tooltip');
                if (tooltip) {
                    tooltip.remove();
                }

                // Retirer les event listeners
                traceLine.onmouseenter = null;
                traceLine.onmouseleave = null;
            }
        }

        let allFolded = true; // Par défaut: tout plié

        // Generic function for toggling all traces (LSP or MCP)
        function toggleAllTracesGeneric(outputId, bodyClass, toggleClass, buttonId, foldedStateRef) {
            const consoleOutput = document.getElementById(outputId);
            if (!consoleOutput) return;

            const bodies = consoleOutput.querySelectorAll(`.${bodyClass}`);
            const toggles = consoleOutput.querySelectorAll(`.${toggleClass}`);
            const foldButton = document.getElementById(buttonId);

            if (foldedStateRef.value) {
                // Unfold all
                bodies.forEach(body => {
                    body.classList.remove('collapsed');
                    body.classList.add('expanded');
                });
                toggles.forEach(toggle => {
                    toggle.textContent = '▼';
                });
                foldButton.textContent = 'Fold All';
                foldedStateRef.value = false;
            } else {
                // Fold all
                bodies.forEach(body => {
                    body.classList.remove('expanded');
                    body.classList.add('collapsed');
                });
                toggles.forEach(toggle => {
                    toggle.textContent = '▶';
                });
                foldButton.textContent = 'Unfold All';
                foldedStateRef.value = true;
            }
        }

        function toggleAllTraces() {
            toggleAllTracesGeneric('console-output', 'trace-body', 'trace-toggle', 'fold-button', {
                get value() { return allFolded; },
                set value(v) { allFolded = v; }
            });
        }

        function searchTraces(query) {
            const consoleOutput = document.getElementById('console-output');
            if (!consoleOutput) return;

            const traceElements = consoleOutput.querySelectorAll('.trace-entry');

            if (!query || query.trim() === '') {
                // Reset: show all traces, remove highlights
                traceElements.forEach(el => {
                    el.style.display = '';
                    el.querySelectorAll('.search-highlight').forEach(mark => {
                        const parent = mark.parentNode;
                        parent.replaceChild(document.createTextNode(mark.textContent), mark);
                        parent.normalize();
                    });
                });
                return;
            }

            const lowerQuery = query.toLowerCase();
            let matchCount = 0;

            traceElements.forEach(el => {
                const text = el.textContent.toLowerCase();
                const matches = text.includes(lowerQuery);

                if (matches) {
                    el.style.display = '';
                    matchCount++;

                    // Open details if match is in JSON content
                    const details = el.querySelector('details');
                    if (details) {
                        const detailsText = details.textContent.toLowerCase();
                        if (detailsText.includes(lowerQuery)) {
                            details.open = true;
                        }
                    }

                    // TODO: Highlight matching text (optional enhancement)
                } else {
                    el.style.display = 'none';
                }
            });

        }

        async function clearConsole() {
            try {
                await fetch('/api/admin/traces', { method: 'DELETE' });

                // Clear traces for current server only
                if (currentServerId) {
                    tracesByServer[currentServerId] = [];
                }

                renderConsole();
            } catch (error) {
                console.error('Failed to clear traces:', error);
            }
        }

        async function stopServerAction(serverId) {
            if (!selectedWorkspace) return;

            const server = workspaces.find(w => w.rootUri === selectedWorkspace)?.lspServers.find(s => s.id === serverId);
            if (!server) return;

            const confirmed = await confirmAction(
                'Stop LSP Server',
                `Stop "${server.name}"?\n\nThe server process will be terminated.`,
                'Stop',
                true
            );
            if (!confirmed) return;

            try {
                const response = await fetch(
                    `/api/admin/servers/${encodeURIComponent(selectedWorkspace)}/${serverId}/stop`,
                    { method: 'POST' }
                );

                if (response.ok) {
                    // Refresh workspace to update status
                    loadServers(selectedWorkspace);
                } else {
                    const error = await response.text();
                    showAlert('Error', 'Failed to stop server: ' + error);
                }
            } catch (error) {
                console.error('Failed to stop server:', error);
                showAlert('Error', 'Failed to stop server: ' + error.message);
            }
        }

        async function startManagedServerAction(serverId) {
            if (!selectedWorkspace) return;

            const server = workspaces.find(w => w.rootUri === selectedWorkspace)?.lspServers.find(s => s.id === serverId);
            if (!server) return;

            try {
                const response = await fetch(
                    `/api/admin/servers/${encodeURIComponent(selectedWorkspace)}/${serverId}/start-managed`,
                    { method: 'POST' }
                );

                if (response.ok) {
                    loadServers(selectedWorkspace);
                } else {
                    const error = await response.text();
                    showAlert('Error', 'Failed to start managed server: ' + error);
                }
            } catch (error) {
                console.error('Failed to start managed server:', error);
                showAlert('Error', 'Failed to start managed server: ' + error.message);
            }
        }

        async function restartServerAction(serverId) {
            if (!selectedWorkspace) return;

            const server = workspaces.find(w => w.rootUri === selectedWorkspace)?.lspServers.find(s => s.id === serverId);
            if (!server) return;

            const confirmed = await confirmAction(
                'Restart LSP Server',
                `Restart "${server.name}"?\n\nThe server will be stopped and restarted.`,
                'Restart',
                false
            );
            if (!confirmed) return;

            try {
                const response = await fetch(
                    `/api/admin/servers/${encodeURIComponent(selectedWorkspace)}/${serverId}/restart`,
                    { method: 'POST' }
                );

                if (response.ok) {
                    // Refresh workspace to update status
                    loadServers(selectedWorkspace);
                } else {
                    const error = await response.text();
                    showAlert('Error', 'Failed to restart server: ' + error);
                }
            } catch (error) {
                console.error('Failed to restart server:', error);
                showAlert('Error', 'Failed to restart server: ' + error.message);
            }
        }

        async function disconnectFromIdeAction(serverId) {
            if (!selectedWorkspace) return;

            const server = workspaces.find(w => w.rootUri === selectedWorkspace)?.lspServers.find(s => s.id === serverId);
            if (!server) return;

            const confirmed = await confirmAction(
                'Disconnect from IDE',
                `Disconnect "${server.name}" from IDE?\n\nThe connection to the IDE instance will be closed.`,
                'Disconnect',
                true
            );
            if (!confirmed) return;

            try {
                const response = await fetch(
                    `/api/admin/servers/${encodeURIComponent(selectedWorkspace)}/${serverId}/disconnect`,
                    { method: 'POST' }
                );

                if (response.ok) {
                    loadServers(selectedWorkspace);
                } else {
                    const error = await response.text();
                    showAlert('Error', 'Failed to disconnect: ' + error);
                }
            } catch (error) {
                console.error('Failed to disconnect:', error);
                showAlert('Error', 'Failed to disconnect: ' + error.message);
            }
        }

        async function connectToIdeAction(serverId) {
            if (!selectedWorkspace) return;

            const server = workspaces.find(w => w.rootUri === selectedWorkspace)?.lspServers.find(s => s.id === serverId);
            if (!server) return;

            try {
                const response = await fetch(
                    `/api/admin/servers/${encodeURIComponent(selectedWorkspace)}/${serverId}/connect-ide`,
                    { method: 'POST' }
                );

                if (response.ok) {
                    loadServers(selectedWorkspace);
                } else {
                    const error = await response.text();
                    showAlert('Error', 'Failed to connect to IDE: ' + error);
                }
            } catch (error) {
                console.error('Failed to connect to IDE:', error);
                showAlert('Error', 'Failed to connect to IDE: ' + error.message);
            }
        }

        // Auto-refresh is no longer needed - SSE handles real-time updates
        // Keep the function for compatibility but make it a no-op
        function autoRefresh() {
            // SSE streams handle all updates in real-time
            // No polling needed anymore
        }

        // Search functionality
        let searchMatches = [];
        let currentMatchIndex = -1;
        let currentSearchQuery = '';

        let fullTextSelected = false;

        document.addEventListener('keydown', (e) => {
            const consoleOutput = document.getElementById('console-output');

            // Ctrl+A to select all console content
            if (e.ctrlKey && e.key === 'a' && consoleOutput && selectedServer) {
                // Vérifier si le focus est dans la console ou pas dans un input
                const activeElement = document.activeElement;
                if (activeElement.tagName !== 'INPUT' && activeElement.tagName !== 'TEXTAREA') {
                    e.preventDefault();
                    selectAllConsoleContent();
                }
            }

            // Ctrl+C après Ctrl+A pour copier tout (incluant plié)
            if (e.ctrlKey && e.key === 'c' && fullTextSelected && selectedServer) {
                e.preventDefault();
                copyFullConsoleContent();
            }

            // Ctrl+F to open search - pour LSP ou MCP console
            if (e.ctrlKey && e.key === 'f') {
                const mcpConsoleOutput = document.getElementById('mcp-console-output');
                const isLspConsole = consoleOutput && selectedServer;
                const isMcpConsole = mcpConsoleOutput && currentTab === 'mcp-traces';

                if (isLspConsole || isMcpConsole) {
                    e.preventDefault();
                    openSearch();
                }
            }

            // Escape to close search
            if (e.key === 'Escape') {
                closeSearch();
            }
        });

        // Reset flag when clicking
        document.addEventListener('mousedown', () => {
            fullTextSelected = false;
        });

        function selectAllConsoleContent() {
            const consoleOutput = document.getElementById('console-output');
            if (!consoleOutput) return;

            // Sélectionner visuellement tout le contenu visible
            const range = document.createRange();
            range.selectNodeContents(consoleOutput);
            const selection = window.getSelection();
            selection.removeAllRanges();
            selection.addRange(range);

            // Marquer que la sélection complète est active
            fullTextSelected = true;

            // Donner le focus
            consoleOutput.focus();
        }

        function copyFullConsoleContent() {
            // Get traces for current server
            const traces = tracesByServer[currentServerId] || [];

            // Construire le texte complet incluant les traces pliées
            let fullText = '';
            traces.forEach(trace => {
                fullText += trace.jsonContent + '\n\n';
            });

            // Copier dans le presse-papier
            navigator.clipboard.writeText(fullText).then(() => {
                fullTextSelected = false;
            }).catch(err => {
                console.error('Failed to copy:', err);
                fullTextSelected = false;
            });
        }

        function openSearch() {
            const searchBox = document.getElementById('search-box');
            const searchInput = document.getElementById('search-input');
            searchBox.classList.add('visible');
            searchInput.focus();
            searchInput.select();
        }

        function closeSearch() {
            const searchBox = document.getElementById('search-box');
            searchBox.classList.remove('visible');
            clearHighlights();
        }

        document.getElementById('search-input').addEventListener('input', (e) => {
            performSearch(e.target.value);
        });

        document.getElementById('search-input').addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
                e.preventDefault();
                if (e.shiftKey) {
                    searchPrev();
                } else {
                    searchNext();
                }
            }
        });

        function highlightText(text, query) {
            if (!query) return escapeHtml(text);

            const escaped = escapeHtml(text);
            const regex = new RegExp(escapeRegex(query), 'gi');
            return escaped.replace(regex, match => `<span class="highlight">${match}</span>`);
        }

        function renderConsoleWithHighlights() {
            const container = document.getElementById('console-output');
            if (!container) return;

            const traces = tracesByServer[currentServerId] || [];
            const filteredTraces = traces.filter(trace => shouldShowTrace(trace, currentTraceLevel));

            if (filteredTraces.length === 0) {
                const message = currentTraceLevel === 'off'
                    ? 'Traces are disabled (level: off)'
                    : 'No LSP trace messages yet.';
                container.innerHTML = `<div style="text-align: center; padding: 2rem; color: #858585;">${message}</div>`;
                return;
            }

            container.innerHTML = filteredTraces.map((trace, index) => {
                const content = trace.jsonContent;
                const hasMatch = currentSearchQuery && content.toLowerCase().includes(currentSearchQuery.toLowerCase());

                const lines = content.split('\n');
                const headerLine = lines[0];

                // Messages mode
                if (currentTraceLevel === 'messages') {
                    return `
                        <div class="trace-line">
                            <div style="padding: 0.25rem; font-family: 'Consolas', 'Monaco', monospace; font-size: 0.85rem; color: #cccccc;">${highlightText(headerLine, currentSearchQuery)}</div>
                        </div>
                    `;
                }

                // Verbose mode
                const body = lines.slice(1).join('\n').trim();
                const hasBody = body.length > 0;

                if (!hasBody) {
                    return `
                        <div class="trace-line">
                            <div style="padding: 0.25rem; font-family: 'Consolas', 'Monaco', monospace; font-size: 0.85rem; color: #cccccc;">${highlightText(headerLine, currentSearchQuery)}</div>
                        </div>
                    `;
                }

                const fullContent = headerLine + '\n' + body;

                // Auto-expand if has search match
                const foldState = hasMatch ? 'expanded' : 'collapsed';
                const toggleIcon = hasMatch ? '▼' : '▶';
                const headerClass = hasMatch ? 'trace-header' : 'trace-header folded';

                return `
                    <div class="trace-line" onmouseenter="showTooltip(event, ${index}, ${!hasMatch})" onmouseleave="hideTooltip(${index})">
                        <div class="${headerClass}" id="header-${index}"
                             onmousedown="onHeaderMouseDown(${index})"
                             onmouseup="onHeaderMouseUp(${index})"
                             style="padding: 0.25rem; font-family: 'Consolas', 'Monaco', monospace; font-size: 0.85rem;">
                            <span class="trace-toggle" id="toggle-${index}" style="margin-right: 0.5rem;">${toggleIcon}</span>
                            <span class="trace-header-text" style="color: #cccccc;">${highlightText(headerLine, currentSearchQuery)}</span>
                        </div>
                        <div class="trace-body ${foldState}" id="body-${index}" style="font-family: 'Consolas', 'Monaco', monospace; font-size: 0.85rem; color: #cccccc;">${highlightText(body, currentSearchQuery)}</div>
                        <div class="trace-tooltip" id="tooltip-${index}">${escapeHtml(fullContent)}</div>
                    </div>
                `;
            }).join('');
        }

        function performSearch(query) {
            currentSearchQuery = query;
            searchMatches = [];
            currentMatchIndex = -1;

            // Re-render with highlighting based on active console
            if (currentTab === 'mcp-traces') {
                renderMcpConsoleWithHighlights();
            } else {
                renderConsoleWithHighlights();
            }

            // Collect all highlights for navigation
            document.querySelectorAll('.highlight').forEach(el => {
                searchMatches.push({ element: el });
            });

            if (searchMatches.length > 0) {
                currentMatchIndex = 0;
                highlightCurrentMatch();
            }

            updateSearchCount();
        }

        function escapeRegex(string) {
            return string.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        }

        function clearHighlights() {
            currentSearchQuery = '';
            searchMatches = [];
            currentMatchIndex = -1;

            // Re-render without highlights
            renderConsoleWithoutScroll();
        }

        function highlightCurrentMatch() {
            // Remove all current highlights
            document.querySelectorAll('.highlight.current').forEach(el => {
                el.classList.remove('current');
            });

            if (currentMatchIndex >= 0 && currentMatchIndex < searchMatches.length) {
                const highlights = document.querySelectorAll('.highlight');
                if (highlights[currentMatchIndex]) {
                    highlights[currentMatchIndex].classList.add('current');
                    highlights[currentMatchIndex].scrollIntoView({ behavior: 'smooth', block: 'center' });
                }
            }
        }

        function searchNext() {
            if (searchMatches.length === 0) return;
            currentMatchIndex = (currentMatchIndex + 1) % searchMatches.length;
            highlightCurrentMatch();
            updateSearchCount();
        }

        function searchPrev() {
            if (searchMatches.length === 0) return;
            currentMatchIndex = (currentMatchIndex - 1 + searchMatches.length) % searchMatches.length;
            highlightCurrentMatch();
            updateSearchCount();
        }

        function updateSearchCount() {
            const searchCount = document.getElementById('search-count');
            if (searchMatches.length === 0) {
                searchCount.textContent = '0/0';
            } else {
                searchCount.textContent = `${currentMatchIndex + 1}/${searchMatches.length}`;
            }
        }

        function renderConsoleWithoutScroll() {
            const container = document.getElementById('console-output');
            if (!container) return;

            // Get traces for current server
            const traces = tracesByServer[currentServerId] || [];

            // Filter traces based on current level
            const filteredTraces = traces.filter(trace => shouldShowTrace(trace, currentTraceLevel));

            if (filteredTraces.length === 0) {
                const message = currentTraceLevel === 'off'
                    ? 'Traces are disabled (level: off)'
                    : 'No LSP trace messages yet.';
                container.innerHTML = `<div style="text-align: center; padding: 2rem; color: #858585;">${message}</div>`;
                return;
            }

            container.innerHTML = filteredTraces.map((trace, index) => {
                const content = trace.jsonContent;

                // Parse the trace: first line is header, rest is body
                const lines = content.split('\n');
                const headerLine = lines[0]; // [Trace - HH:mm:ss] ...

                // Messages mode: show only header line, no folding
                if (currentTraceLevel === 'messages') {
                    return `
                        <div class="trace-line">
                            <div style="padding: 0.25rem; font-family: 'Consolas', 'Monaco', monospace; font-size: 0.85rem; color: #cccccc;">${escapeHtml(headerLine)}</div>
                        </div>
                    `;
                }

                // Verbose mode: header + body folded by default
                const bodyLines = lines.slice(1);
                const body = bodyLines.join('\n').trim();
                const hasBody = body.length > 0;

                // If no body, display like messages mode (no toggle arrow)
                if (!hasBody) {
                    return `
                        <div class="trace-line">
                            <div style="padding: 0.25rem; font-family: 'Consolas', 'Monaco', monospace; font-size: 0.85rem; color: #cccccc;">${escapeHtml(headerLine)}</div>
                        </div>
                    `;
                }

                return `
                    <div class="trace-line">
                        <div class="trace-header folded" onclick="toggleTrace(${index})" style="padding: 0.25rem; font-family: 'Consolas', 'Monaco', monospace; font-size: 0.85rem;">
                            <span class="trace-toggle" id="toggle-${index}" style="margin-right: 0.5rem;">▶</span>
                            <span class="trace-header-text" style="color: #cccccc;">${escapeHtml(headerLine)}</span>
                        </div>
                        <div class="trace-body collapsed" id="body-${index}" style="font-family: 'Consolas', 'Monaco', monospace; font-size: 0.85rem; color: #cccccc;">${escapeHtml(body)}</div>
                    </div>
                `;
            }).join('');
        }

        // Modal functions
        function showModal(title, message, buttons) {
            const modal = document.getElementById('modal-overlay');
            const modalTitle = document.getElementById('modal-title');
            const modalMessage = document.getElementById('modal-message');
            const modalButtons = document.getElementById('modal-buttons');

            modalTitle.textContent = title;
            modalMessage.textContent = message;

            modalButtons.innerHTML = buttons.map(btn => `
                <button class="modal-button ${btn.type || 'secondary'}"
                        onclick="${btn.onclick}">${btn.label}</button>
            `).join('');

            modal.classList.add('visible');
        }

        function hideModal() {
            document.getElementById('modal-overlay').classList.remove('visible');
        }

        async function confirmAction(title, message, confirmLabel, isDanger = false) {
            return new Promise((resolve) => {
                showModal(title, message, [
                    {
                        label: 'Cancel',
                        type: 'secondary',
                        onclick: `hideModal(); window.modalResolve(false);`
                    },
                    {
                        label: confirmLabel,
                        type: isDanger ? 'danger' : 'primary',
                        onclick: `hideModal(); window.modalResolve(true);`
                    }
                ]);

                window.modalResolve = resolve;
            });
        }

        function showAlert(title, message) {
            showModal(title, message, [
                {
                    label: 'OK',
                    type: 'primary',
                    onclick: 'hideModal()'
                }
            ]);
        }

        // Close modal on overlay click (if modal exists)
        const modalOverlay = document.getElementById('modal-overlay');
        if (modalOverlay) {
            modalOverlay.addEventListener('click', (e) => {
                if (e.target.id === 'modal-overlay') {
                    hideModal();
                    if (window.modalResolve) {
                        window.modalResolve(false);
                    }
                }
            });
        }

