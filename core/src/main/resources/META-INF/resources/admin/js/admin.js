        let workspaces = [];
        let selectedWorkspace = null;
        let selectedServer = null;

        // Expose selectedWorkspace globally for admin-dap.js and admin-lsp.js
        window.selectedWorkspace = null;

        let tracesByServer = {}; // Store traces per server: {serverId: [...traces]}
        let currentTab = 'workspaces';
        let workspacesRendered = false; // Track if workspaces have been rendered at least once
        let currentConsoleTab = 'traces'; // Track current tab in Workspaces view
        let currentWorkspaceTab = 'servers'; // Track current tab in workspace view: 'servers' or 'debuggers'

        // Expose currentTab globally for admin-mcp.js
        window.currentTab = currentTab;

        // WebSocket connection (replaces SSE and polling)
        let adminWebSocket = null;

        // Global server configurations (static config loaded once at startup)
        let serverConfigs = {}; // Map<serverId, ServerConfigDTO>

        // Expose serverConfigs globally for admin-lsp.js
        window.serverConfigs = serverConfigs;

        /**
         * Load global server configurations (called once at startup).
         */
        async function loadServerConfigs() {
            try {
                const response = await fetch('/api/admin/servers');
                const configs = await response.json();

                // Build a map for quick lookup
                serverConfigs = {};
                configs.forEach(config => {
                    serverConfigs[config.id] = config;
                });

                // Update global reference
                window.serverConfigs = serverConfigs;

                console.log('Loaded', configs.length, 'server configs');
            } catch (error) {
                console.error('Failed to load server configs:', error);
            }
        }

        /**
         * Load global DAP server configurations (called once at startup).
         */
        async function loadDapConfigs() {
            try {
                const response = await fetch('/api/admin/dap-servers');
                const configs = await response.json();

                // Store globally (like LSP serverConfigs)
                window.dapConfigs = configs;

                console.log('Loaded', configs.length, 'DAP configs');
            } catch (error) {
                console.error('Failed to load DAP configs:', error);
                window.dapConfigs = [];
            }
        }

        /**
         * Check if a server is an extension (no documentSelector = pure extension).
         * @param {Object} server - Server config
         * @returns {boolean} True if extension
         */
        function isExtension(server) {
            return !server.documentSelector || server.documentSelector.length === 0;
        }

        /**
         * Merge runtime state with static config.
         * @param {Object} runtime - ServerRuntimeDTO from workspace
         * @returns {Object} Merged server object with both config and runtime
         */
        function mergeServerData(runtime) {
            const config = serverConfigs[runtime.serverId] || {};
            return {
                // Config fields
                id: runtime.serverId,
                name: config.name || runtime.serverId,
                description: config.description,
                documentSelector: config.documentSelector,
                command: config.command,
                args: config.args,
                env: config.env,
                workingDirectory: config.workingDirectory,
                initializationOptions: config.initializationOptions,
                contributions: config.contributions,
                isExtension: config.isExtension,

                // Runtime fields
                status: runtime.status,
                statusMessage: runtime.statusMessage,
                isReady: runtime.isReady,
                pid: runtime.pid,
                externalInstance: runtime.externalInstance
            };
        }

        /**
         * Build contributedBy map (inverse of contributesTo)
         */
        function buildContributedByMap(servers) {
            const map = {};
            for (const server of servers) {
                // contributions is now a Map<targetServerId, Map<contributionType, List<?>>>
                if (server.contributions) {
                    for (const targetId of Object.keys(server.contributions)) {
                        if (!map[targetId]) map[targetId] = [];
                        map[targetId].push(server.id);
                    }
                }
            }
            return map;
        }

        /**
         * Format contribute info for display (contributesTo or contributedBy)
         */
        function formatContributeInfo(server, contributedByMap) {
            // Extract contributesTo from contributions map
            const contributesTo = server.contributions ? Object.keys(server.contributions) : [];
            const contributedBy = contributedByMap[server.id] || [];

            let text = '';
            let tooltip = '';

            if (contributesTo.length > 0) {
                const full = contributesTo.join(', ');
                const styled = contributesTo.map(id => `<span style="color: #888;">${id}</span>`).join(', ');
                const displayStyled = full.length > 20
                    ? contributesTo.slice(0, 1).map(id => `<span style="color: #888;">${id}</span>`).join('') + ', <span style="color: #888;">...</span>'
                    : styled;
                text = ` <span style="color: #aaa; font-size: 1.3rem; font-weight: bold;">→</span> ${displayStyled}`;
                if (full.length > 20) {
                    tooltip = `Contributes to: ${full}`;
                }
            } else if (contributedBy.length > 0) {
                const full = contributedBy.join(', ');
                const styled = contributedBy.map(id => `<span style="color: #888;">${id}</span>`).join(', ');
                const displayStyled = full.length > 20
                    ? contributedBy.slice(0, 1).map(id => `<span style="color: #888;">${id}</span>`).join('') + ', <span style="color: #888;">...</span>'
                    : styled;
                text = ` <span style="color: #aaa; font-size: 1.3rem; font-weight: bold;">←</span> ${displayStyled}`;
                if (full.length > 20) {
                    tooltip = `Contributed by: ${full}`;
                }
            }

            return { text, tooltip };
        }

        /**
         * Connect to admin WebSocket for real-time updates.
         * Replaces SSE streams and polling intervals.
         */
        function connectAdminWebSocket() {
            const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
            const wsUrl = `${protocol}//${window.location.host}/api/admin/ws`;

            console.log('Connecting to WebSocket:', wsUrl);
            adminWebSocket = new WebSocket(wsUrl);

            adminWebSocket.onopen = () => {
                console.log('WebSocket connected');
            };

            adminWebSocket.onmessage = (event) => {
                const message = JSON.parse(event.data);
                handleWebSocketMessage(message);
            };

            adminWebSocket.onerror = (error) => {
                console.error('WebSocket error:', error);
            };

            adminWebSocket.onclose = () => {
                console.log('WebSocket closed, reconnecting in 3s...');
                setTimeout(connectAdminWebSocket, 3000);
            };
        }

        /**
         * Route incoming WebSocket messages by type.
         */
        function handleWebSocketMessage(message) {
            console.log('WebSocket message received:', message.type, message);
            switch (message.type) {
                case 'lsp-trace':
                    handleLspTrace(message);
                    break;
                case 'dap-trace':
                    handleDapTrace(message);
                    break;
                case 'mcp-trace':
                    handleMcpTrace(message);
                    break;
                case 'workspaces-update':
                    handleWorkspacesUpdate(message.workspaces);
                    break;
                case 'mcp-clients-update':
                    handleMcpClientsUpdate(message.clients);
                    break;
                case 'server-status-changed':
                    handleServerStatusChanged(message);
                    break;
                default:
                    console.warn('Unknown WebSocket message type:', message.type);
            }
        }

        /**
         * Handle LSP trace message from WebSocket.
         */
        function handleLspTrace(trace) {
            console.log('handleLspTrace called for server:', trace.serverId, 'current:', currentServerId);

            // Store trace by server
            if (!tracesByServer[trace.serverId]) {
                tracesByServer[trace.serverId] = [];
            }
            tracesByServer[trace.serverId].push(trace);

            console.log('Stored trace, total for', trace.serverId, ':', tracesByServer[trace.serverId].length);

            // Keep only last 200 traces per server
            if (tracesByServer[trace.serverId].length > 200) {
                tracesByServer[trace.serverId] = tracesByServer[trace.serverId].slice(-200);
            }

            // Refresh console if this trace is for the currently selected server
            if (trace.workspaceUri === selectedWorkspace && trace.serverId === currentServerId) {
                console.log('Refreshing console for current server');
                renderConsole();
            }
        }

        /**
         * Handle DAP trace message from WebSocket.
         */
        function handleDapTrace(trace) {
            console.log('handleDapTrace called for session:', trace.sessionId);

            // Store trace by session
            if (!window.dapTracesBySession) {
                window.dapTracesBySession = {};
            }
            if (!window.dapTracesBySession[trace.sessionId]) {
                window.dapTracesBySession[trace.sessionId] = [];
            }

            // Check if this is a progress update (replaces previous line)
            const isProgress = trace.jsonContent && trace.jsonContent.startsWith('[PROGRESS]');

            if (isProgress) {
                // Remove [PROGRESS] prefix from display
                trace.jsonContent = trace.jsonContent.substring(10); // Remove "[PROGRESS]"

                // Replace last trace if it was also a progress message
                const traces = window.dapTracesBySession[trace.sessionId];
                const lastTrace = traces[traces.length - 1];
                if (lastTrace && lastTrace.isProgress) {
                    traces[traces.length - 1] = { ...trace, isProgress: true };
                } else {
                    traces.push({ ...trace, isProgress: true });
                }
            } else {
                window.dapTracesBySession[trace.sessionId].push(trace);
            }

            // Keep only last 200 traces per session
            if (window.dapTracesBySession[trace.sessionId].length > 200) {
                window.dapTracesBySession[trace.sessionId] = window.dapTracesBySession[trace.sessionId].slice(-200);
            }

            // Refresh console if this session is selected
            if (window.currentDapSessionId === trace.sessionId) {
                if (typeof window.renderDapTracesForSession === 'function') {
                    window.renderDapTracesForSession(trace.sessionId);
                }
            }

            console.log('DAP trace stored:', trace.sessionId, window.dapTracesBySession[trace.sessionId].length, 'traces');
        }

        /**
         * Handle MCP trace message from WebSocket.
         */
        function handleMcpTrace(trace) {
            const connectionId = trace.connectionId;
            if (!mcpTracesByClient[connectionId]) {
                mcpTracesByClient[connectionId] = [];
            }
            mcpTracesByClient[connectionId].push(trace);

            // Keep only last 500 traces per client
            if (mcpTracesByClient[connectionId].length > 500) {
                mcpTracesByClient[connectionId].shift();
            }

            // Refresh MCP console if this trace is for the currently selected client
            if (connectionId === selectedMcpClient) {
                renderMcpConsole();
            }
        }

        /**
         * Handle workspaces update from WebSocket.
         */
        function handleWorkspacesUpdate(newWorkspaces) {
            console.log('WebSocket workspaces update:', newWorkspaces);

            // Merge runtime server data with static configs for each workspace
            const mergedWorkspaces = newWorkspaces.map(workspace => {
                const servers = workspace.lspServers.map(mergeServerData);

                // Synchronize extensions with their parent servers
                servers.forEach(server => {
                    console.log('Checking server:', server.id, 'parentServerId:', server.parentServerId);
                    if (server.parentServerId) {
                        const parent = servers.find(s => s.id === server.parentServerId);
                        console.log('Found parent:', parent ? parent.id : 'NOT FOUND', 'status:', parent?.status);
                        if (parent) {
                            server.status = parent.status;
                            server.isReady = parent.isReady;
                            server.statusMessage = parent.statusMessage;
                            server.pid = parent.pid;
                            server.command = parent.command;
                            console.log('Synced', server.id, 'status to', server.status);
                        }
                    }
                });

                return {
                    ...workspace,
                    lspServers: servers
                };
            });

            // Update if data changed OR if this is the first render
            if (!workspacesRendered || JSON.stringify(mergedWorkspaces) !== JSON.stringify(workspaces)) {
                workspaces = mergedWorkspaces;
                workspacesRendered = true;
                console.log('Workspaces updated, rendering...');
                renderWorkspaces();

                // If a workspace was selected, update its details
                if (selectedWorkspace) {
                    console.log('Selected workspace:', selectedWorkspace);
                    const workspace = workspaces.find(w => w.rootUri === selectedWorkspace);
                    console.log('Found workspace:', workspace);
                    if (workspace) {
                        console.log('Rendering servers:', workspace.lspServers);
                        renderServers(workspace.lspServers, workspace.dapSessions || [], workspace);
                    } else {
                        // Selected workspace no longer exists
                        selectedWorkspace = null;
                window.selectedWorkspace = null;
                        document.getElementById('servers-list').innerHTML = '<div class="servers-placeholder">No workspaces selected</div>';
                    }
                } else if (workspaces.length > 0 && currentTab === 'workspaces') {
                    // Auto-select first workspace on initial load
                    console.log('Auto-selecting first workspace');
                    selectWorkspace(workspaces[0].rootUri);
                }
            }
        }

        /**
         * Handle MCP clients update from WebSocket.
         */
        function handleMcpClientsUpdate(newClients) {
            // Only update if data actually changed
            if (JSON.stringify(newClients) !== JSON.stringify(mcpClients)) {
                mcpClients = newClients;
                renderMcpClients();
            }
        }

        /**
         * Handle server status change from WebSocket.
         */
        function handleServerStatusChanged(event) {
            console.log('Server status changed:', event);

            // Find the workspace
            const workspace = workspaces.find(w => w.rootUri === event.workspaceUri);
            if (!workspace) {
                return;
            }

            // Find the server that changed
            const changedServer = workspace.lspServers.find(s => s.id === event.serverId);
            if (!changedServer) {
                return;
            }

            // Update the server's status
            changedServer.status = event.newStatus;

            // If this server is a parent, update all its extensions
            const extensions = workspace.lspServers.filter(s => s.parentServerId === event.serverId);
            for (const ext of extensions) {
                ext.status = event.newStatus;
                ext.isReady = changedServer.isReady;
                ext.statusMessage = changedServer.statusMessage;
                ext.pid = changedServer.pid;
                ext.command = changedServer.command;
            }

            // Re-render if this workspace is selected
            if (selectedWorkspace === event.workspaceUri) {
                renderServers(workspace.lspServers, workspace.dapSessions || [], workspace);
            }
        }

        function switchTab(tab, element) {
            currentTab = tab;
            window.currentTab = tab; // Update global reference

            // Update tab UI
            document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
            if (element) {
                element.classList.add('active');
            }

            // Show/hide content and adjust layout
            const appContainer = document.querySelector('.app-container');
            const serversColumn = document.querySelector('.servers-sidebar');
            const consoleColumn = document.querySelector('.console-container');

            if (tab === 'workspaces') {
                document.getElementById('workspaces-list').style.display = 'block';
                document.getElementById('lsp-servers-list').style.display = 'none';
                document.getElementById('dap-servers-list').style.display = 'none';
                document.getElementById('mcp-traces-list').style.display = 'none';
                serversColumn.style.display = 'block';
                consoleColumn.style.display = 'flex';
                // 3 columns layout: workspaces | servers | console
                appContainer.style.gridTemplateColumns = '400px 300px 1fr';
                consoleColumn.style.gridColumn = '3';

                // Refresh the view: reload servers list and console for selected workspace
                // (workspace data comes via WebSocket)
                if (selectedWorkspace) {
                    loadServers(selectedWorkspace);
                    if (selectedServer) {
                        loadConsole(selectedServer);
                    } else {
                        // No server selected, show placeholder
                        document.getElementById('console-area').innerHTML = `
                            <div class="placeholder">
                                ← Select a workspace and LSP server to view console
                            </div>
                        `;
                    }
                } else {
                    // No workspace selected, show placeholder
                    document.getElementById('servers-list').innerHTML = '<div class="servers-placeholder">No workspaces selected</div>';
                    document.getElementById('console-area').innerHTML = `
                        <div class="placeholder">
                            ← Select a workspace and LSP server to view console
                        </div>
                    `;
                }
            } else if (tab === 'lsp-servers') {
                document.getElementById('workspaces-list').style.display = 'none';
                document.getElementById('lsp-servers-list').style.display = 'block';
                document.getElementById('dap-servers-list').style.display = 'none';
                document.getElementById('mcp-traces-list').style.display = 'none';
                serversColumn.style.display = 'none';
                consoleColumn.style.display = 'flex';
                // 2 columns layout: servers | console
                appContainer.style.gridTemplateColumns = '400px 1fr';
                consoleColumn.style.gridColumn = '2';

                // Always reload servers when switching to this tab (no caching)
                loadAllLspServers();
            } else if (tab === 'dap-servers') {
                document.getElementById('workspaces-list').style.display = 'none';
                document.getElementById('lsp-servers-list').style.display = 'none';
                document.getElementById('dap-servers-list').style.display = 'block';
                document.getElementById('mcp-traces-list').style.display = 'none';
                serversColumn.style.display = 'none';
                consoleColumn.style.display = 'flex';
                // 2 columns layout: servers | console
                appContainer.style.gridTemplateColumns = '400px 1fr';
                consoleColumn.style.gridColumn = '2';

                // Always reload DAP servers when switching to this tab
                loadAllDapServers();
            } else if (tab === 'mcp-traces') {
                document.getElementById('workspaces-list').style.display = 'none';
                document.getElementById('lsp-servers-list').style.display = 'none';
                document.getElementById('dap-servers-list').style.display = 'none';
                document.getElementById('mcp-traces-list').style.display = 'block';
                serversColumn.style.display = 'none';
                consoleColumn.style.display = 'flex';
                // 2 columns layout: mcp info | console
                appContainer.style.gridTemplateColumns = '400px 1fr';
                consoleColumn.style.gridColumn = '2';

                // MCP clients data comes via WebSocket
                // Auto-select first client or previously selected client
                if (mcpClients.length > 0) {
                    if (selectedMcpClient && mcpClients.find(c => c.id === selectedMcpClient)) {
                        // Re-select and refresh
                        loadMcpConsole(selectedMcpClient);
                    } else {
                        // Select first client
                        selectMcpClient(mcpClients[0].id);
                    }
                } else {
                    // No clients, show placeholder
                    loadMcpTracesConsole();
                }
            }
        }

        // ========== LSP Servers Tab ==========
        // (Code moved to admin-lsp.js)


        // ========== Debuggers Tab ==========
        // (Code moved to admin-dap.js)

        // Initialize: load server configs and DAP configs first, then connect WebSocket
        (async function init() {
            await loadServerConfigs();
            await loadDapConfigs();
            connectAdminWebSocket();
        })();
