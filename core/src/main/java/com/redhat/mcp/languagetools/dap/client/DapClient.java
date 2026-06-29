package com.redhat.mcp.languagetools.dap.client;

import org.eclipse.lsp4j.debug.*;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.jboss.logging.Logger;

/**
 * DAP client implementation that receives events from the debug adapter.
 * Routes events to a registered DapEventListener (typically a DapSession).
 */
public class DapClient implements IDebugProtocolClient {

    private static final Logger LOG = Logger.getLogger(DapClient.class);

    private DapEventListener eventListener;

    public DapClient() {
    }

    public void setEventListener(DapEventListener listener) {
        this.eventListener = listener;
    }

    // ========== Event Notifications from Debug Adapter ==========

    @Override
    public void stopped(StoppedEventArguments args) {
        LOG.debugf("Stopped event: reason=%s, threadId=%d", args.getReason(), args.getThreadId());
        if (eventListener != null) {
            eventListener.onStopped(args);
        }
    }

    @Override
    public void continued(ContinuedEventArguments args) {
        LOG.debugf("Continued event: threadId=%d", args.getThreadId());
        if (eventListener != null) {
            eventListener.onContinued(args);
        }
    }

    @Override
    public void exited(ExitedEventArguments args) {
        LOG.infof("Exited event: exitCode=%d", args.getExitCode());
        // No listener callback - this is informational only
    }

    @Override
    public void terminated(TerminatedEventArguments args) {
        LOG.info("Terminated event");
        if (eventListener != null) {
            eventListener.onTerminated(args);
        }
    }

    @Override
    public void thread(ThreadEventArguments args) {
        LOG.debugf("Thread event: reason=%s, threadId=%d", args.getReason(), args.getThreadId());
        if (eventListener != null) {
            eventListener.onThread(args);
        }
    }

    @Override
    public void output(OutputEventArguments args) {
        LOG.debugf("Output event: category=%s, output=%s", args.getCategory(), args.getOutput());
        if (eventListener != null) {
            eventListener.onOutput(args);
        }
    }

    @Override
    public void breakpoint(BreakpointEventArguments args) {
        LOG.debugf("Breakpoint event: reason=%s", args.getReason());
        if (eventListener != null) {
            eventListener.onBreakpoint(args);
        }
    }

    @Override
    public void module(ModuleEventArguments args) {
        LOG.debugf("Module event: reason=%s", args.getReason());
        if (eventListener != null) {
            eventListener.onModule(args);
        }
    }

    @Override
    public void loadedSource(LoadedSourceEventArguments args) {
        LOG.debugf("LoadedSource event: reason=%s", args.getReason());
        if (eventListener != null) {
            eventListener.onLoadedSource(args);
        }
    }

    @Override
    public void process(ProcessEventArguments args) {
        LOG.debugf("Process event: name=%s", args.getName());
        if (eventListener != null) {
            eventListener.onProcess(args);
        }
    }

    @Override
    public void capabilities(CapabilitiesEventArguments args) {
        LOG.debug("Capabilities event");
        if (eventListener != null) {
            eventListener.onCapabilities(args);
        }
    }

    @Override
    public void progressStart(ProgressStartEventArguments args) {
        LOG.debugf("ProgressStart event: progressId=%s, title=%s", args.getProgressId(), args.getTitle());
        if (eventListener != null) {
            eventListener.onProgressStart(args);
        }
    }

    @Override
    public void progressUpdate(ProgressUpdateEventArguments args) {
        LOG.debugf("ProgressUpdate event: progressId=%s", args.getProgressId());
        if (eventListener != null) {
            eventListener.onProgressUpdate(args);
        }
    }

    @Override
    public void progressEnd(ProgressEndEventArguments args) {
        LOG.debugf("ProgressEnd event: progressId=%s", args.getProgressId());
        if (eventListener != null) {
            eventListener.onProgressEnd(args);
        }
    }

    @Override
    public void invalidated(InvalidatedEventArguments args) {
        LOG.debugf("Invalidated event: areas=%s", (Object) args.getAreas());
        if (eventListener != null) {
            eventListener.onInvalidated(args);
        }
    }

    @Override
    public void memory(MemoryEventArguments args) {
        LOG.debugf("Memory event: memoryReference=%s", args.getMemoryReference());
        if (eventListener != null) {
            eventListener.onMemory(args);
        }
    }
}
