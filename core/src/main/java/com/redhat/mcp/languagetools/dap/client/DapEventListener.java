package com.redhat.mcp.languagetools.dap.client;

import org.eclipse.lsp4j.debug.*;

/**
 * Listener interface for DAP events.
 * Typically implemented by DapSession to handle events from the debug adapter.
 */
public interface DapEventListener {

    /**
     * Called when execution stops (breakpoint hit, step complete, pause, etc.).
     */
    void onStopped(StoppedEventArguments event);

    /**
     * Called when execution continues after being stopped.
     */
    void onContinued(ContinuedEventArguments event);

    /**
     * Called when the debug session terminates.
     */
    void onTerminated(TerminatedEventArguments event);

    /**
     * Called when a thread is created, started, exited, etc.
     */
    void onThread(ThreadEventArguments event);

    /**
     * Called when the debug adapter sends output (console, stdout, stderr).
     */
    void onOutput(OutputEventArguments event);

    /**
     * Called when a breakpoint is added, removed, or changes state.
     */
    void onBreakpoint(BreakpointEventArguments event);

    /**
     * Called when a module is loaded or unloaded.
     */
    void onModule(ModuleEventArguments event);

    /**
     * Called when a source file is loaded or unloaded.
     */
    void onLoadedSource(LoadedSourceEventArguments event);

    /**
     * Called when process information changes.
     */
    void onProcess(ProcessEventArguments event);

    /**
     * Called when debug adapter capabilities change.
     */
    void onCapabilities(CapabilitiesEventArguments event);

    /**
     * Called when a long-running operation starts.
     */
    void onProgressStart(ProgressStartEventArguments event);

    /**
     * Called when a long-running operation updates progress.
     */
    void onProgressUpdate(ProgressUpdateEventArguments event);

    /**
     * Called when a long-running operation ends.
     */
    void onProgressEnd(ProgressEndEventArguments event);

    /**
     * Called when cached data should be invalidated.
     */
    void onInvalidated(InvalidatedEventArguments event);

    /**
     * Called when memory contents change.
     */
    void onMemory(MemoryEventArguments event);
}
