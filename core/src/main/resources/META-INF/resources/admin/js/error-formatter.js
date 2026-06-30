/**
 * Common error formatting for all admin consoles (LSP, DAP, MCP).
 * Provides consistent error display with message and foldable stack trace.
 */

/**
 * Format an error response with consistent styling and folding.
 *
 * @param {string} title - Error title (e.g., "Failed to Launch")
 * @param {object} errorData - Error object with {message, type, stackTrace}
 * @returns {string} HTML for the formatted error
 */
function formatErrorWithFolding(title, errorData) {
    const message = errorData.message || 'Unknown error';
    const type = errorData.type || '';
    const stackTrace = errorData.stackTrace || '';

    const traceId = 'error-' + Date.now();

    // Format: Title + Type on first line, then foldable stack trace
    // Don't repeat the message if it's the same as type
    const body = (message === type || !message || message === 'null') ? stackTrace.trim() : (message + '\n' + stackTrace).trim();

    return `
        <div style="margin-bottom: 1rem; font-family: 'Consolas', 'Monaco', monospace;">
            <div class="trace-header folded" onclick="window.toggleErrorTrace('${traceId}')" style="padding: 0.25rem; cursor: pointer; user-select: none; display: flex; align-items: center;">
                <span class="trace-toggle" style="color: #f48771; margin-right: 0.25rem;">▶</span>
                <span class="trace-header-text" style="font-weight: bold; color: #f48771;">${title} - ${type}</span>
            </div>
            <div id="${traceId}" class="trace-body collapsed" style="padding-left: 1.5rem; font-size: 0.85rem; white-space: pre-wrap; word-wrap: break-word; color: #f48771;">${body}</div>
        </div>
    `;
}

/**
 * Toggle an error trace body between collapsed and expanded.
 */
function toggleErrorTrace(traceId) {
    const body = document.getElementById(traceId);
    if (!body) return;

    const header = body.previousElementSibling;
    const toggle = header ? header.querySelector('.trace-toggle') : null;

    if (body.classList.contains('collapsed')) {
        body.classList.remove('collapsed');
        body.classList.add('expanded');
        if (header) header.classList.remove('folded');
        if (toggle) toggle.textContent = '▼';
    } else {
        body.classList.add('collapsed');
        body.classList.remove('expanded');
        if (header) header.classList.add('folded');
        if (toggle) toggle.textContent = '▶';
    }
}

// Expose globally (ensure it's available)
if (typeof window !== 'undefined') {
    window.formatErrorWithFolding = formatErrorWithFolding;
    window.toggleErrorTrace = toggleErrorTrace;
    console.log('Error formatter loaded');
}
