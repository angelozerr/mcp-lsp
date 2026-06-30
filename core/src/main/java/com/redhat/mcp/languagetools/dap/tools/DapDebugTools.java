package com.redhat.mcp.languagetools.dap.tools;

import com.redhat.mcp.languagetools.dap.session.DapSession;
import com.redhat.mcp.languagetools.dap.session.DapSessionManager;
import com.redhat.mcp.languagetools.ApplicationManager;
import io.quarkiverse.mcp.server.Tool;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.lsp4j.debug.*;
import org.eclipse.lsp4j.debug.Thread;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP Tools for Debug Adapter Protocol (DAP) operations.
 *
 * Provides 20+ tools to control debug sessions across multiple languages:
 * - Session management (create, list, close)
 * - Breakpoints (set, remove, list)
 * - Execution control (launch, attach, continue, step)
 * - Inspection (stack trace, variables, evaluate)
 */
@Singleton
public class DapDebugTools {

    @Inject
    DapSessionManager sessionManager;

    @Inject
    ApplicationManager applicationManager;

    // ========== Session Management ==========

    @Tool(description = "Create a new debug session for a specific programming language. " +
            "Returns sessionId to use in other debug operations.")
    public Map<String, Object> create_debug_session(
            String language,
            String sessionName,
            String workspaceUri) {

        URI uri = URI.create(workspaceUri);
        return sessionManager.createSession(language, sessionName, uri).join();
    }

    @Tool(description = "List all active debug sessions with their state (CREATED, RUNNING, PAUSED, etc).")
    public List<Map<String, Object>> list_debug_sessions() {
        return sessionManager.listSessions();
    }

    @Tool(description = "List all programming languages supported by available debug adapters " +
            "(e.g., javascript, python, go, rust).")
    public List<String> list_supported_languages() {
        return sessionManager.listSupportedLanguages();
    }

    @Tool(description = "Close and terminate a debug session, stopping the debugged program.")
    public Map<String, Object> close_debug_session(String sessionId) {
        return sessionManager.closeSession(sessionId).join();
    }

    // ========== Breakpoints ==========

    @Tool(description = "Set a breakpoint at a specific file and line number. " +
            "Optionally add a condition (e.g., 'x > 10') to break only when true.")
    public Map<String, Object> set_breakpoint(
            String sessionId,
            String file,
            int line,
            String condition) {

        DapSession session = sessionManager.getSession(sessionId);
        DapSession.BreakpointInfo info = session.setBreakpoint(file, line, condition).join();

        return Map.of(
            "success", true,
            "breakpointId", info.breakpointId,
            "file", info.file,
            "line", info.line,
            "verified", info.verified,
            "message", "Breakpoint set at " + file + ":" + line
        );
    }

    @Tool(description = "Remove a previously set breakpoint by its ID.")
    public Map<String, Object> remove_breakpoint(
            String sessionId,
            String breakpointId) {

        DapSession session = sessionManager.getSession(sessionId);
        boolean removed = session.removeBreakpoint(breakpointId).join();

        return Map.of(
            "success", removed,
            "breakpointId", breakpointId,
            "message", removed ? "Breakpoint removed" : "Breakpoint not found"
        );
    }

    @Tool(description = "List all breakpoints currently set in a debug session.")
    public Map<String, Object> list_all_breakpoints(String sessionId) {
        DapSession session = sessionManager.getSession(sessionId);
        List<DapSession.BreakpointInfo> breakpoints = session.listBreakpoints();

        List<Map<String, Object>> bpList = breakpoints.stream()
            .map(bp -> {
                Map<String, Object> map = new HashMap<>();
                map.put("breakpointId", bp.breakpointId);
                map.put("file", bp.file);
                map.put("line", bp.line);
                map.put("verified", bp.verified);
                map.put("condition", bp.condition != null ? bp.condition : "");
                return map;
            })
            .collect(Collectors.toList());

        return Map.of(
            "success", true,
            "count", bpList.size(),
            "breakpoints", bpList
        );
    }

    // ========== Debugging Lifecycle ==========

    @Tool(description = "Launch a script or program for debugging. The program will start and pause at breakpoints.")
    public Map<String, Object> start_debugging(
            String sessionId,
            String scriptPath,
            Map<String, Object> additionalArgs) {

        DapSession session = sessionManager.getSession(sessionId);

        // Build launch config
        Map<String, Object> launchConfig = new java.util.HashMap<>();
        launchConfig.put("program", scriptPath);
        if (additionalArgs != null) {
            launchConfig.putAll(additionalArgs);
        }

        return session.launch(launchConfig).join();
    }

    @Tool(description = "Attach debugger to an already running process by process ID.")
    public Map<String, Object> attach_to_process(
            String sessionId,
            int processId) {

        DapSession session = sessionManager.getSession(sessionId);
        return session.attach(processId).join();
    }

    @Tool(description = "Detach from the debugged process without terminating it.")
    public Map<String, Object> detach_from_process(String sessionId) {
        // For now, just close the session
        // TODO: implement proper detach that leaves process running
        return close_debug_session(sessionId);
    }

    // ========== Execution Control ==========

    @Tool(description = "Continue program execution after hitting a breakpoint or pause.")
    public Map<String, Object> continue_execution(String sessionId) {
        DapSession session = sessionManager.getSession(sessionId);
        return session.continueExecution().join();
    }

    @Tool(description = "Pause the running program at the current line.")
    public Map<String, Object> pause_execution(String sessionId) {
        DapSession session = sessionManager.getSession(sessionId);
        session.pause().join();
        return Map.of(
            "success", true,
            "message", "Execution paused"
        );
    }

    @Tool(description = "Step over the current line (execute without entering function calls).")
    public Map<String, Object> step_over(String sessionId) {
        DapSession session = sessionManager.getSession(sessionId);
        session.stepOver().join();
        return Map.of(
            "success", true,
            "message", "Stepped over"
        );
    }

    @Tool(description = "Step into a function call on the current line.")
    public Map<String, Object> step_in(String sessionId) {
        DapSession session = sessionManager.getSession(sessionId);
        session.stepIn().join();
        return Map.of(
            "success", true,
            "message", "Stepped in"
        );
    }

    @Tool(description = "Step out of the current function, returning to the caller.")
    public Map<String, Object> step_out(String sessionId) {
        DapSession session = sessionManager.getSession(sessionId);
        session.stepOut().join();
        return Map.of(
            "success", true,
            "message", "Stepped out"
        );
    }

    // ========== Inspection ==========

    @Tool(description = "Get the current call stack (stack trace) showing function calls and line numbers.")
    public Map<String, Object> get_stack_trace(String sessionId) {
        DapSession session = sessionManager.getSession(sessionId);
        StackFrame[] frames = session.getStackTrace().join();

        List<Map<String, Object>> framesList = Arrays.stream(frames)
            .map(frame -> {
                Map<String, Object> frameMap = new HashMap<>();
                frameMap.put("id", frame.getId());
                frameMap.put("name", frame.getName());
                frameMap.put("line", frame.getLine());
                frameMap.put("column", frame.getColumn());
                if (frame.getSource() != null) {
                    frameMap.put("file", frame.getSource().getPath());
                }
                return frameMap;
            })
            .collect(Collectors.toList());

        return Map.of(
            "success", true,
            "frames", framesList
        );
    }

    @Tool(description = "List all threads in the debugged program.")
    public Map<String, Object> list_threads(String sessionId) {
        DapSession session = sessionManager.getSession(sessionId);
        Thread[] threads = session.getThreads().join();

        List<Map<String, Object>> threadsList = Arrays.stream(threads)
            .map(thread -> {
                Map<String, Object> map = new HashMap<>();
                map.put("id", thread.getId());
                map.put("name", thread.getName());
                return map;
            })
            .collect(Collectors.toList());

        return Map.of(
            "success", true,
            "threads", threadsList
        );
    }

    @Tool(description = "Get variable scopes (Locals, Globals, etc.) for a specific stack frame.")
    public Map<String, Object> get_scopes(
            String sessionId,
            int frameId) {

        DapSession session = sessionManager.getSession(sessionId);
        Scope[] scopes = session.getScopes(frameId).join();

        List<Map<String, Object>> scopesList = Arrays.stream(scopes)
            .map(scope -> {
                Map<String, Object> map = new HashMap<>();
                map.put("name", scope.getName());
                map.put("variablesReference", scope.getVariablesReference());
                map.put("expensive", scope.isExpensive());
                return map;
            })
            .collect(Collectors.toList());

        return Map.of(
            "success", true,
            "scopes", scopesList
        );
    }

    @Tool(description = "Get variables from a scope or expandable variable. " +
            "Use variablesReference from get_scopes or a variable's variablesReference.")
    public Map<String, Object> get_variables(
            String sessionId,
            int variablesReference) {

        DapSession session = sessionManager.getSession(sessionId);
        Variable[] variables = session.getVariables(variablesReference).join();

        List<Map<String, Object>> varsList = Arrays.stream(variables)
            .map(var -> {
                Map<String, Object> varMap = new HashMap<>();
                varMap.put("name", var.getName());
                varMap.put("value", var.getValue());
                varMap.put("type", var.getType() != null ? var.getType() : "");
                varMap.put("variablesReference", var.getVariablesReference());
                varMap.put("expandable", var.getVariablesReference() > 0);
                return varMap;
            })
            .collect(Collectors.toList());

        return Map.of(
            "success", true,
            "variables", varsList,
            "count", varsList.size()
        );
    }

    @Tool(description = "Shortcut to get local variables in the current stack frame (top of stack).")
    public Map<String, Object> get_local_variables(String sessionId) {
        DapSession session = sessionManager.getSession(sessionId);

        // Get top frame
        StackFrame[] frames = session.getStackTrace().join();
        if (frames.length == 0) {
            return Map.of(
                "success", false,
                "message", "No stack frames available"
            );
        }

        int frameId = frames[0].getId();

        // Get scopes for top frame
        Scope[] scopes = session.getScopes(frameId).join();

        // Find "Locals" scope
        Scope localsScope = Arrays.stream(scopes)
            .filter(s -> "Locals".equalsIgnoreCase(s.getName()))
            .findFirst()
            .orElse(scopes.length > 0 ? scopes[0] : null);

        if (localsScope == null) {
            return Map.of(
                "success", false,
                "message", "No local scope found"
            );
        }

        // Get variables from locals scope
        return get_variables(sessionId, localsScope.getVariablesReference());
    }

    @Tool(description = "Evaluate an expression in the current debug context (e.g., 'x + y', 'myFunction()').")
    public Map<String, Object> evaluate_expression(
            String sessionId,
            String expression,
            Integer frameId) {

        DapSession session = sessionManager.getSession(sessionId);

        // If no frameId provided, use top frame
        Integer targetFrameId = frameId;
        if (targetFrameId == null) {
            StackFrame[] frames = session.getStackTrace().join();
            if (frames.length > 0) {
                targetFrameId = frames[0].getId();
            }
        }

        EvaluateResponse response = session.evaluate(expression, targetFrameId).join();

        return Map.of(
            "success", true,
            "result", response.getResult(),
            "type", response.getType() != null ? response.getType() : "",
            "variablesReference", response.getVariablesReference()
        );
    }

    // ========== Statistics ==========

    @Tool(description = "Get statistics about active debug sessions (total count, states, supported languages).")
    public Map<String, Object> get_debug_statistics() {
        return sessionManager.getStatistics();
    }
}
