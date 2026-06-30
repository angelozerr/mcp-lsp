package com.redhat.mcp.languagetools.dap.session;

import com.redhat.mcp.languagetools.ApplicationContext;
import com.redhat.mcp.languagetools.WorkspaceContext;
import com.redhat.mcp.languagetools.dap.client.DapEventListener;
import com.redhat.mcp.languagetools.dap.server.DapServer;
import com.redhat.mcp.languagetools.dap.server.DapServerConfig;
import org.eclipse.lsp4j.debug.*;
import org.eclipse.lsp4j.debug.Thread;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.jboss.logging.Logger;

import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a single debug session.
 * One session = one program being debugged via DAP.
 *
 * Lifecycle:
 * 1. create() - Create session with language/name
 * 2. setBreakpoint() - Set breakpoints before launch
 * 3. launch() or attach() - Start debugging
 * 4. step/continue/evaluate operations
 * 5. terminate() - End session
 */
public class DapSession implements DapEventListener {

    private static final Logger LOG = Logger.getLogger(DapSession.class);

    private final String sessionId;
    private final String language;
    private final String sessionName;
    private final DapServerConfig serverConfig;
    private final DapServer dapServer;
    private final ApplicationContext appContext;
    private final WorkspaceContext workspaceContext;
    private final com.redhat.mcp.languagetools.dap.trace.DapTraceCollector traceCollector;

    private SessionState state = SessionState.CREATED;
    private final Map<String, BreakpointInfo> breakpoints = new ConcurrentHashMap<>();
    private Thread[] threads = new Thread[0];
    private StackFrame[] currentStackFrames = new StackFrame[0];
    private Integer currentThreadId;

    public enum SessionState {
        CREATED,
        INITIALIZED,
        RUNNING,
        PAUSED,
        TERMINATED,
        ERROR
    }

    public static class BreakpointInfo {
        public final String breakpointId;
        public final String file;
        public final int line;
        public final String condition;
        public boolean verified;
        public Breakpoint dapBreakpoint;

        public BreakpointInfo(String breakpointId, String file, int line, String condition) {
            this.breakpointId = breakpointId;
            this.file = file;
            this.line = line;
            this.condition = condition;
            this.verified = false;
        }
    }

    public DapSession(String sessionId,
                      String language,
                      String sessionName,
                      DapServerConfig serverConfig,
                      ApplicationContext appContext,
                      WorkspaceContext workspaceContext,
                      com.redhat.mcp.languagetools.dap.trace.DapTraceCollector traceCollector) {
        this.sessionId = sessionId;
        this.language = language;
        this.sessionName = sessionName;
        this.serverConfig = serverConfig;
        this.appContext = appContext;
        this.workspaceContext = workspaceContext;
        this.traceCollector = traceCollector;

        // Create DapServer instance with context
        Path serverHome = appContext.getPathManager().getDapServerHome(serverConfig.getId());

        // Create DAP server context
        com.redhat.mcp.languagetools.dap.server.DapServerContext dapContext = new com.redhat.mcp.languagetools.dap.server.DapServerContext() {
            @Override
            public com.redhat.mcp.languagetools.ApplicationContext getApplicationContext() {
                return appContext;
            }

            @Override
            public com.redhat.mcp.languagetools.PathManager getPathManager() {
                return appContext.getPathManager();
            }

            @Override
            public java.nio.file.Path getDapServerHome() {
                return serverHome;
            }

            @Override
            public com.redhat.mcp.languagetools.dap.trace.DapTraceCollector getTraceCollector() {
                return traceCollector;
            }

            @Override
            public String getSessionId() {
                return sessionId;
            }

            @Override
            public String getSessionName() {
                return sessionName;
            }

            @Override
            public java.net.URI getWorkspaceRoot() {
                return workspaceContext.getWorkspaceRoot();
            }

            @Override
            public java.nio.file.Path getWorkspaceDataDir() {
                return workspaceContext.getWorkspaceDataDir();
            }
        };

        this.dapServer = new com.redhat.mcp.languagetools.dap.server.DapServer(serverConfig, dapContext);

        // Register this session as the event listener
        this.dapServer.setEventListener(this);
    }

    // ========== Lifecycle ==========

    /**
     * Initialize the DAP server and establish connection.
     * Automatically installs the DAP server if not already installed.
     */
    public CompletableFuture<Void> initialize() {
        LOG.infof("Initializing DAP session: %s (%s)", sessionName, sessionId);

        // Check if installation is needed
        com.redhat.mcp.languagetools.installer.ServerInstaller installer = serverConfig.getInstaller();

        if (installer != null) {
            LOG.infof("DAP server has installer, ensuring installation: %s", serverConfig.getId());

            // Send installation start message to traces
            traceCollector.addTrace(
                sessionId,
                serverConfig.getId(),
                serverConfig.getName(),
                com.redhat.mcp.languagetools.dap.trace.DapTraceMessage.MessageDirection.SENT,
                "INSTALL: Ensuring " + serverConfig.getName() + " is installed..."
            );

            // Create installation context
            java.nio.file.Path installDir = appContext.getPathManager().getDapServerHome(serverConfig.getId());
            com.redhat.mcp.languagetools.installer.TraceProgressIndicator progress =
                new com.redhat.mcp.languagetools.installer.TraceProgressIndicator(serverConfig.getTraceCollector());
            com.redhat.mcp.languagetools.installer.InstallerContext context =
                new com.redhat.mcp.languagetools.installer.InstallerContext(serverConfig, installDir, progress);

            // Run installation
            return installer.ensureInstalled(context)
            .thenCompose(installResult -> {
                if (installResult != null) {
                    LOG.infof("DAP server installation complete: %s (status: %s)",
                        installResult.getInstallDir(), installResult.getStatus());

                    // Update command if installer provided one
                    if (installResult.getCommand() != null) {
                        // For DAP, command is in launch config - would need to update it
                        LOG.debugf("Installer provided command: %s", installResult.getCommand());
                    }
                }
                return startDapServer();
            });
        } else {
            // No installer, just start
            return startDapServer();
        }
    }

    private CompletableFuture<Void> startDapServer() {
        return dapServer.start()
            .thenAccept(v -> {
                state = SessionState.INITIALIZED;
                LOG.infof("DAP session initialized: %s", sessionId);
            })
            .exceptionally(ex -> {
                LOG.errorf(ex, "Failed to initialize DAP session: %s", sessionId);
                state = SessionState.ERROR;

                // Send error to traces
                traceCollector.addTrace(
                    workspaceContext.getWorkspaceRoot().toString(),
                    sessionId,
                    sessionName,
                    com.redhat.mcp.languagetools.dap.trace.DapTraceMessage.MessageDirection.RECEIVED,
                    "❌ Failed to initialize: " + ex.getMessage()
                );

                // Propagate exception instead of returning null
                if (ex instanceof RuntimeException) {
                    throw (RuntimeException) ex;
                }
                throw new RuntimeException("Failed to initialize DAP session", ex);
            });
    }

    /**
     * Launch a program for debugging.
     *
     * @param scriptPath Path to the script/program to debug
     * @param args Additional launch arguments
     */
    /**
     * Launch with full launch configuration (from launch.json).
     */
    public CompletableFuture<Map<String, Object>> launch(Map<String, Object> launchConfig) {
        LOG.infof("Launching debug session: %s with config: %s", sessionId, launchConfig);

        // Initialize if not already done (includes installation if needed)
        CompletableFuture<Void> initFuture;
        if (state == SessionState.CREATED) {
            initFuture = initialize();
        } else {
            initFuture = CompletableFuture.completedFuture(null);
        }

        return initFuture.thenCompose(v -> {
            IDebugProtocolServer server = dapServer.getDebugServer();
            if (server == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("DAP server not initialized"));
            }

            // Add workspace folder if not present
            if (!launchConfig.containsKey("cwd")) {
                launchConfig.put("cwd", workspaceContext.getWorkspaceRoot().getPath());
            }

            return server.launch(launchConfig)
                .thenApply(result -> {
                    state = SessionState.RUNNING;
                    return Map.of(
                        "success", true,
                        "state", "running",
                        "message", "Debugging started"
                    );
                });
        });
    }

    /**
     * Attach to a running process.
     *
     * @param processId Process ID to attach to
     */
    public CompletableFuture<Map<String, Object>> attach(int processId) {
        LOG.infof("Attaching debug session: %s to process: %d", sessionId, processId);

        Map<String, Object> attachArgs = new HashMap<>();
        attachArgs.put("processId", processId);

        IDebugProtocolServer server = dapServer.getDebugServer();
        if (server == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("DAP server not initialized"));
        }

        return server.attach(attachArgs)
            .thenApply(v -> {
                state = SessionState.RUNNING;
                return Map.of(
                    "success", true,
                    "state", "attached",
                    "message", "Attached to process " + processId
                );
            });
    }

    /**
     * Terminate the debug session.
     */
    public CompletableFuture<Void> terminate() {
        LOG.infof("Terminating DAP session: %s", sessionId);

        IDebugProtocolServer server = dapServer.getDebugServer();
        if (server != null) {
            DisconnectArguments args = new DisconnectArguments();
            args.setTerminateDebuggee(true);
            return server.disconnect(args)
                .thenCompose(v -> dapServer.stop2())
                .thenAccept(v -> {
                    state = SessionState.TERMINATED;
                    LOG.infof("DAP session terminated: %s", sessionId);
                });
        }

        return dapServer.stop2()
            .thenAccept(v -> state = SessionState.TERMINATED);
    }

    // ========== Breakpoints ==========

    /**
     * Set a breakpoint at a specific file and line.
     */
    public CompletableFuture<BreakpointInfo> setBreakpoint(String file, int line, String condition) {
        String breakpointId = UUID.randomUUID().toString();
        BreakpointInfo info = new BreakpointInfo(breakpointId, file, line, condition);
        breakpoints.put(breakpointId, info);

        LOG.infof("Setting breakpoint: %s at %s:%d", breakpointId, file, line);

        // Send to DAP server
        IDebugProtocolServer server = dapServer.getDebugServer();
        if (server == null) {
            return CompletableFuture.completedFuture(info);
        }

        SetBreakpointsArguments args = new SetBreakpointsArguments();
        Source source = new Source();
        source.setPath(file);
        args.setSource(source);

        SourceBreakpoint bp = new SourceBreakpoint();
        bp.setLine(line);
        if (condition != null && !condition.isEmpty()) {
            bp.setCondition(condition);
        }
        args.setBreakpoints(new SourceBreakpoint[]{bp});

        return server.setBreakpoints(args)
            .thenApply(response -> {
                if (response.getBreakpoints() != null && response.getBreakpoints().length > 0) {
                    Breakpoint dapBp = response.getBreakpoints()[0];
                    info.verified = dapBp.isVerified();
                    info.dapBreakpoint = dapBp;
                    LOG.infof("Breakpoint set and verified: %s", breakpointId);
                }
                return info;
            });
    }

    /**
     * Remove a breakpoint.
     */
    public CompletableFuture<Boolean> removeBreakpoint(String breakpointId) {
        BreakpointInfo info = breakpoints.remove(breakpointId);
        if (info == null) {
            return CompletableFuture.completedFuture(false);
        }

        LOG.infof("Removing breakpoint: %s", breakpointId);

        // Send updated breakpoint list to DAP server (without this breakpoint)
        IDebugProtocolServer server = dapServer.getDebugServer();
        if (server == null) {
            return CompletableFuture.completedFuture(true);
        }

        SetBreakpointsArguments args = new SetBreakpointsArguments();
        Source source = new Source();
        source.setPath(info.file);
        args.setSource(source);
        args.setBreakpoints(new SourceBreakpoint[0]); // Empty = remove all from this file

        return server.setBreakpoints(args)
            .thenApply(response -> true);
    }

    /**
     * List all breakpoints.
     */
    public List<BreakpointInfo> listBreakpoints() {
        return new ArrayList<>(breakpoints.values());
    }

    // ========== Execution Control ==========

    /**
     * Continue execution.
     */
    public CompletableFuture<Map<String, Object>> continueExecution() {
        LOG.infof("Continue execution: %s", sessionId);

        IDebugProtocolServer server = dapServer.getDebugServer();
        if (server == null || currentThreadId == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No thread to continue"));
        }

        ContinueArguments args = new ContinueArguments();
        args.setThreadId(currentThreadId);

        return server.continue_(args)
            .thenApply(response -> {
                state = SessionState.RUNNING;
                return Map.of(
                    "success", true,
                    "allThreadsContinued", response.getAllThreadsContinued() != null ? response.getAllThreadsContinued() : false
                );
            });
    }

    /**
     * Step over (next line).
     */
    public CompletableFuture<Void> stepOver() {
        LOG.infof("Step over: %s", sessionId);

        IDebugProtocolServer server = dapServer.getDebugServer();
        if (server == null || currentThreadId == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No thread to step"));
        }

        NextArguments args = new NextArguments();
        args.setThreadId(currentThreadId);

        return server.next(args);
    }

    /**
     * Step into function.
     */
    public CompletableFuture<Void> stepIn() {
        LOG.infof("Step in: %s", sessionId);

        IDebugProtocolServer server = dapServer.getDebugServer();
        if (server == null || currentThreadId == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No thread to step"));
        }

        StepInArguments args = new StepInArguments();
        args.setThreadId(currentThreadId);

        return server.stepIn(args);
    }

    /**
     * Step out of current function.
     */
    public CompletableFuture<Void> stepOut() {
        LOG.infof("Step out: %s", sessionId);

        IDebugProtocolServer server = dapServer.getDebugServer();
        if (server == null || currentThreadId == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No thread to step"));
        }

        StepOutArguments args = new StepOutArguments();
        args.setThreadId(currentThreadId);

        return server.stepOut(args);
    }

    /**
     * Pause execution.
     */
    public CompletableFuture<Void> pause() {
        LOG.infof("Pause execution: %s", sessionId);

        IDebugProtocolServer server = dapServer.getDebugServer();
        if (server == null || currentThreadId == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No thread to pause"));
        }

        PauseArguments args = new PauseArguments();
        args.setThreadId(currentThreadId);

        return server.pause(args)
            .thenAccept(v -> state = SessionState.PAUSED);
    }

    // ========== Inspection ==========

    /**
     * Get stack trace for current thread.
     */
    public CompletableFuture<StackFrame[]> getStackTrace() {
        LOG.infof("Get stack trace: %s", sessionId);

        IDebugProtocolServer server = dapServer.getDebugServer();
        if (server == null || currentThreadId == null) {
            return CompletableFuture.completedFuture(new StackFrame[0]);
        }

        StackTraceArguments args = new StackTraceArguments();
        args.setThreadId(currentThreadId);

        return server.stackTrace(args)
            .thenApply(response -> {
                currentStackFrames = response.getStackFrames();
                return currentStackFrames;
            });
    }

    /**
     * Get threads list.
     */
    public CompletableFuture<Thread[]> getThreads() {
        LOG.infof("Get threads: %s", sessionId);

        IDebugProtocolServer server = dapServer.getDebugServer();
        if (server == null) {
            return CompletableFuture.completedFuture(new Thread[0]);
        }

        return server.threads()
            .thenApply(response -> {
                threads = response.getThreads();
                if (threads.length > 0 && currentThreadId == null) {
                    currentThreadId = threads[0].getId();
                }
                return threads;
            });
    }

    /**
     * Get scopes for a stack frame.
     */
    public CompletableFuture<Scope[]> getScopes(int frameId) {
        LOG.infof("Get scopes for frame %d: %s", frameId, sessionId);

        IDebugProtocolServer server = dapServer.getDebugServer();
        if (server == null) {
            return CompletableFuture.completedFuture(new Scope[0]);
        }

        ScopesArguments args = new ScopesArguments();
        args.setFrameId(frameId);

        return server.scopes(args)
            .thenApply(ScopesResponse::getScopes);
    }

    /**
     * Get variables from a scope or variable reference.
     */
    public CompletableFuture<Variable[]> getVariables(int variablesReference) {
        LOG.infof("Get variables for reference %d: %s", variablesReference, sessionId);

        IDebugProtocolServer server = dapServer.getDebugServer();
        if (server == null) {
            return CompletableFuture.completedFuture(new Variable[0]);
        }

        VariablesArguments args = new VariablesArguments();
        args.setVariablesReference(variablesReference);

        return server.variables(args)
            .thenApply(VariablesResponse::getVariables);
    }

    /**
     * Evaluate an expression in the current debug context.
     */
    public CompletableFuture<EvaluateResponse> evaluate(String expression, Integer frameId) {
        LOG.infof("Evaluate expression '%s': %s", expression, sessionId);

        IDebugProtocolServer server = dapServer.getDebugServer();
        if (server == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("DAP server not initialized"));
        }

        EvaluateArguments args = new EvaluateArguments();
        args.setExpression(expression);
        if (frameId != null) {
            args.setFrameId(frameId);
        }
        args.setContext("watch");

        return server.evaluate(args);
    }

    // ========== Event Handlers (DapEventListener implementation) ==========

    @Override
    public void onStopped(StoppedEventArguments event) {
        LOG.infof("Session %s stopped: %s (thread %d)", sessionId, event.getReason(), event.getThreadId());
        state = SessionState.PAUSED;
        currentThreadId = event.getThreadId();
    }

    @Override
    public void onContinued(ContinuedEventArguments event) {
        LOG.infof("Session %s continued (thread %d)", sessionId, event.getThreadId());
        state = SessionState.RUNNING;
    }

    @Override
    public void onTerminated(TerminatedEventArguments event) {
        LOG.infof("Session %s terminated", sessionId);
        state = SessionState.TERMINATED;
    }

    @Override
    public void onThread(ThreadEventArguments event) {
        LOG.infof("Session %s thread event: %s (thread %d)", sessionId, event.getReason(), event.getThreadId());
    }

    @Override
    public void onOutput(OutputEventArguments event) {
        LOG.debugf("Session %s output [%s]: %s", sessionId, event.getCategory(), event.getOutput());
    }

    @Override
    public void onBreakpoint(BreakpointEventArguments event) {
        LOG.infof("Session %s breakpoint event: %s", sessionId, event.getReason());
        // Update breakpoint verification status if needed
        if (event.getBreakpoint() != null) {
            Breakpoint bp = event.getBreakpoint();
            // Find and update corresponding BreakpointInfo
            for (BreakpointInfo info : breakpoints.values()) {
                if (info.dapBreakpoint != null && info.dapBreakpoint.getId() != null
                    && info.dapBreakpoint.getId().equals(bp.getId())) {
                    info.verified = bp.isVerified();
                    info.dapBreakpoint = bp;
                    break;
                }
            }
        }
    }

    @Override
    public void onModule(ModuleEventArguments event) {
        LOG.debugf("Session %s module event: %s", sessionId, event.getReason());
    }

    @Override
    public void onLoadedSource(LoadedSourceEventArguments event) {
        LOG.debugf("Session %s loaded source: %s", sessionId, event.getReason());
    }

    @Override
    public void onProcess(ProcessEventArguments event) {
        LOG.infof("Session %s process event: %s", sessionId, event.getName());
    }

    @Override
    public void onCapabilities(CapabilitiesEventArguments event) {
        LOG.debugf("Session %s capabilities changed", sessionId);
    }

    @Override
    public void onProgressStart(ProgressStartEventArguments event) {
        LOG.debugf("Session %s progress start: %s", sessionId, event.getTitle());
    }

    @Override
    public void onProgressUpdate(ProgressUpdateEventArguments event) {
        LOG.debugf("Session %s progress update: %s", sessionId, event.getMessage());
    }

    @Override
    public void onProgressEnd(ProgressEndEventArguments event) {
        LOG.debugf("Session %s progress end", sessionId);
    }

    @Override
    public void onInvalidated(InvalidatedEventArguments event) {
        LOG.debugf("Session %s invalidated: %s", sessionId, event.getAreas());
        // Clear cached stack frames when invalidated
        currentStackFrames = new StackFrame[0];
    }

    @Override
    public void onMemory(MemoryEventArguments event) {
        LOG.debugf("Session %s memory changed: %s", sessionId, event.getMemoryReference());
    }

    // ========== Getters ==========

    public String getSessionId() {
        return sessionId;
    }

    public String getLanguage() {
        return language;
    }

    public String getSessionName() {
        return sessionName;
    }

    public SessionState getState() {
        return state;
    }

    public DapServer getDapServer() {
        return dapServer;
    }

    public com.redhat.mcp.languagetools.dap.server.DapServerConfig getServerConfig() {
        return dapServer != null ? dapServer.getConfig() : null;
    }

    public WorkspaceContext getWorkspaceContext() {
        return workspaceContext;
    }
}
