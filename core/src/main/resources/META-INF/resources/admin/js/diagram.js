/**
 * Server dependency diagram using vis.js
 */

let serverDiagramNetwork = null;
let workspaceDiagramNetwork = null;

/**
 * Render server dependency diagram.
 * Shows only the current server + its direct dependencies (contributors and contributed-to).
 * @param {Array} servers - All servers with their contributions
 * @param {string} currentServerId - The currently selected server to highlight
 */
function renderServerDiagram(servers, currentServerId) {
    const container = document.getElementById('server-diagram-container');
    if (!container) {
        console.error('Diagram container not found');
        return;
    }

    // Filter: only current server + direct dependencies
    const relevantServerIds = new Set([currentServerId]);

    // Find servers this server contributes to
    const currentServer = servers.find(s => s.id === currentServerId);
    if (currentServer && currentServer.contributions) {
        Object.keys(currentServer.contributions).forEach(targetId => {
            relevantServerIds.add(targetId);
        });
    }

    // Find servers that contribute to this server
    servers.forEach(server => {
        if (server.contributions && server.contributions[currentServerId]) {
            relevantServerIds.add(server.id);
        }
    });

    const filteredServers = servers.filter(s => relevantServerIds.has(s.id));

    // Build nodes (one per server)
    const nodes = filteredServers.map(server => {
        const icon = server.isExtension ? '🧩' : '🚀';
        const label = `${icon} ${server.name || server.id}`;
        return {
            id: server.id,
            label: label,
            title: server.id === currentServerId
                ? (server.description || server.name || server.id)
                : `${server.description || server.name || server.id}\n\n💡 Double-click to open`, // tooltip
            color: server.id === currentServerId
                ? '#005a9e'  // Current server: blue
                : (server.isExtension ? '#6b6b6b' : '#3a8070'),  // Extension: gray, Server: green
            font: {
                color: '#ffffff',
                size: 14
            },
            shape: 'box',
            margin: 10
        };
    });

    // Build edges (contributions between servers)
    // Group by (from, to) pair to combine multiple contribution types
    const edgeMap = new Map();
    const edgeColors = {
        bundles: '#4a7ba7',      // blue (less bright)
        classpath: '#3a9178',    // green (less bright)
        bindRequest: '#a67656',  // orange (less bright)
        bindNotification: '#8e6b8a' // purple (less bright)
    };

    filteredServers.forEach(server => {
        if (!server.contributions) return;

        // For each target server this server contributes to
        Object.keys(server.contributions).forEach(targetServerId => {
            const contributionData = server.contributions[targetServerId];
            const edgeKey = `${server.id}->${targetServerId}`;

            if (!edgeMap.has(edgeKey)) {
                edgeMap.set(edgeKey, {
                    from: server.id,
                    to: targetServerId,
                    contributions: []
                });
            }

            // Collect all contribution types
            Object.keys(contributionData).forEach(type => {
                const items = contributionData[type];
                if (!items || items.length === 0) return;

                edgeMap.get(edgeKey).contributions.push({
                    type: type,
                    count: items.length,
                    color: edgeColors[type] || '#888888'
                });
            });
        });
    });

    // Create edges from map
    const edges = [];
    edgeMap.forEach((edgeData) => {
        if (edgeData.contributions.length === 0) return;

        // Combine all contribution types in label
        const label = edgeData.contributions
            .map(c => `${c.type} (${c.count})`)
            .join('\n');

        // Use first contribution color (or mix them)
        const color = edgeData.contributions[0].color;

        edges.push({
            from: edgeData.from,
            to: edgeData.to,
            label: label,
            color: {
                color: color,
                highlight: '#ffffff'
            },
            arrows: {
                to: {
                    enabled: true,
                    scaleFactor: 0.3
                }
            },
            font: {
                color: '#cccccc',
                size: 11,
                strokeWidth: 0,
                multi: true,
                align: 'horizontal'
            },
            smooth: {
                type: 'curvedCW',
                roundness: 0.2
            }
        });
    });

    // vis.js data
    const data = {
        nodes: new vis.DataSet(nodes),
        edges: new vis.DataSet(edges)
    };

    // vis.js options
    const options = {
        layout: {
            hierarchical: false  // Disable hierarchical for better spread
        },
        physics: {
            enabled: true,
            solver: 'forceAtlas2Based',
            forceAtlas2Based: {
                gravitationalConstant: -50,
                centralGravity: 0.01,
                springLength: 200,
                springConstant: 0.08,
                damping: 0.4,
                avoidOverlap: 1
            },
            stabilization: {
                enabled: true,
                iterations: 200,
                updateInterval: 25
            }
        },
        interaction: {
            hover: true,
            navigationButtons: true,
            keyboard: true
        },
        nodes: {
            borderWidth: 2,
            borderWidthSelected: 3,
            color: {
                border: '#3a3a3a',
                background: '#3a8070',
                highlight: {
                    border: '#005a9e',
                    background: '#005a9e'
                }
            }
        },
        edges: {
            width: 1,
            selectionWidth: 2,
            font: {
                strokeWidth: 3,
                strokeColor: '#1e1e1e',
                background: '#1e1e1e',
                size: 11
            },
            labelHighlightBold: false
        }
    };

    // Destroy previous network if exists
    if (serverDiagramNetwork) {
        serverDiagramNetwork.destroy();
    }

    // Create network
    serverDiagramNetwork = new vis.Network(container, data, options);

    // Change cursor on hover
    serverDiagramNetwork.on('hoverNode', function() {
        container.style.cursor = 'pointer';
    });
    serverDiagramNetwork.on('blurNode', function() {
        container.style.cursor = 'default';
    });

    // Event: double-click on node -> switch to details of that server
    serverDiagramNetwork.on('doubleClick', function(params) {
        if (params.nodes.length > 0) {
            const clickedServerId = params.nodes[0];

            // Don't switch if clicking on the same server
            if (clickedServerId === currentServerId) {
                console.log('Already viewing this server:', clickedServerId);
                return;
            }

            console.log('Double-clicked on server:', clickedServerId);
            showServerDetails(clickedServerId);
        }
    });

    // Center and fit the diagram after rendering
    setTimeout(() => {
        serverDiagramNetwork.fit({
            animation: {
                duration: 500,
                easingFunction: 'easeInOutQuad'
            }
        });
    }, 100);

    console.log('Server diagram rendered with', nodes.length, 'nodes and', edges.length, 'edges');
}

/**
 * Render workspace server diagram.
 * Shows only servers that have at least one contribution (connected servers).
 * @param {Array} servers - All servers in workspace with their contributions
 * @param {string} currentServerId - The currently selected server to highlight
 */
function renderWorkspaceDiagram(servers, currentServerId) {
    const container = document.getElementById('workspace-diagram-container');
    if (!container) {
        console.error('Workspace diagram container not found');
        return;
    }

    // Filter: only servers with contributions (either contributing or being contributed to)
    const connectedServerIds = new Set();

    // Find all servers that contribute
    servers.forEach(server => {
        if (server.contributions && Object.keys(server.contributions).length > 0) {
            connectedServerIds.add(server.id);
            Object.keys(server.contributions).forEach(targetId => {
                connectedServerIds.add(targetId);
            });
        }
    });

    const filteredServers = servers.filter(s => connectedServerIds.has(s.id));

    // Build nodes (one per server)
    const nodes = filteredServers.map(server => {
        const icon = server.isExtension ? '🧩' : '🚀';
        const label = `${icon} ${server.name || server.id}`;
        return {
            id: server.id,
            label: label,
            title: server.id === currentServerId
                ? (server.description || server.name || server.id)
                : `${server.description || server.name || server.id}\n\n💡 Double-click to open`, // tooltip
            color: server.id === currentServerId
                ? '#005a9e'  // Current server: blue
                : (server.isExtension ? '#6b6b6b' : '#3a8070'),  // Extension: gray, Server: green
            font: {
                color: '#ffffff',
                size: 14
            },
            shape: 'box',
            margin: 10
        };
    });

    // Build edges (contributions between servers)
    // Group by (from, to) pair to combine multiple contribution types
    const edgeMap = new Map();
    const edgeColors = {
        bundles: '#4a7ba7',      // blue (less bright)
        classpath: '#3a9178',    // green (less bright)
        bindRequest: '#a67656',  // orange (less bright)
        bindNotification: '#8e6b8a' // purple (less bright)
    };

    filteredServers.forEach(server => {
        if (!server.contributions) return;

        // For each target server this server contributes to
        Object.keys(server.contributions).forEach(targetServerId => {
            const contributionData = server.contributions[targetServerId];
            const edgeKey = `${server.id}->${targetServerId}`;

            if (!edgeMap.has(edgeKey)) {
                edgeMap.set(edgeKey, {
                    from: server.id,
                    to: targetServerId,
                    contributions: []
                });
            }

            // Collect all contribution types
            Object.keys(contributionData).forEach(type => {
                const items = contributionData[type];
                if (!items || items.length === 0) return;

                edgeMap.get(edgeKey).contributions.push({
                    type: type,
                    count: items.length,
                    color: edgeColors[type] || '#888888'
                });
            });
        });
    });

    // Create edges from map
    const edges = [];
    edgeMap.forEach((edgeData) => {
        if (edgeData.contributions.length === 0) return;

        // Combine all contribution types in label
        const label = edgeData.contributions
            .map(c => `${c.type} (${c.count})`)
            .join('\n');

        // Use first contribution color (or mix them)
        const color = edgeData.contributions[0].color;

        edges.push({
            from: edgeData.from,
            to: edgeData.to,
            label: label,
            color: {
                color: color,
                highlight: '#ffffff'
            },
            arrows: {
                to: {
                    enabled: true,
                    scaleFactor: 0.3
                }
            },
            font: {
                color: '#cccccc',
                size: 11,
                strokeWidth: 0,
                multi: true,
                align: 'horizontal'
            },
            smooth: {
                type: 'curvedCW',
                roundness: 0.2
            }
        });
    });

    // vis.js data
    const data = {
        nodes: new vis.DataSet(nodes),
        edges: new vis.DataSet(edges)
    };

    // vis.js options
    const options = {
        layout: {
            hierarchical: false  // Disable hierarchical for better spread
        },
        physics: {
            enabled: true,
            solver: 'forceAtlas2Based',
            forceAtlas2Based: {
                gravitationalConstant: -50,
                centralGravity: 0.01,
                springLength: 200,
                springConstant: 0.08,
                damping: 0.4,
                avoidOverlap: 1
            },
            stabilization: {
                enabled: true,
                iterations: 200,
                updateInterval: 25
            }
        },
        interaction: {
            hover: true,
            navigationButtons: true,
            keyboard: true
        },
        nodes: {
            borderWidth: 2,
            borderWidthSelected: 3,
            color: {
                border: '#3a3a3a',
                background: '#3a8070',
                highlight: {
                    border: '#005a9e',
                    background: '#005a9e'
                }
            }
        },
        edges: {
            width: 1,
            selectionWidth: 2,
            font: {
                strokeWidth: 3,
                strokeColor: '#1e1e1e',
                background: '#1e1e1e',
                size: 11
            },
            labelHighlightBold: false
        }
    };

    // Destroy previous network if exists
    if (workspaceDiagramNetwork) {
        workspaceDiagramNetwork.destroy();
    }

    // Create network
    workspaceDiagramNetwork = new vis.Network(container, data, options);

    // Change cursor on hover
    workspaceDiagramNetwork.on('hoverNode', function() {
        container.style.cursor = 'pointer';
    });
    workspaceDiagramNetwork.on('blurNode', function() {
        container.style.cursor = 'default';
    });

    // Event: double-click on node -> switch to details of that server
    workspaceDiagramNetwork.on('doubleClick', function(params) {
        if (params.nodes.length > 0) {
            const clickedServerId = params.nodes[0];

            // Don't switch if clicking on the same server
            if (clickedServerId === currentServerId) {
                console.log('Already viewing this server:', clickedServerId);
                return;
            }

            console.log('Double-clicked on workspace server:', clickedServerId);
            switchConsoleTab('overview');
            loadServerDetails(clickedServerId);
        }
    });

    // Center and fit the diagram after rendering
    setTimeout(() => {
        workspaceDiagramNetwork.fit({
            animation: {
                duration: 500,
                easingFunction: 'easeInOutQuad'
            }
        });
    }, 100);

    console.log('Workspace diagram rendered with', nodes.length, 'nodes and', edges.length, 'edges');
}
