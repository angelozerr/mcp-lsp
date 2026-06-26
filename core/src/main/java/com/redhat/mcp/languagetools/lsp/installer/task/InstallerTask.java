package com.redhat.mcp.languagetools.lsp.installer.task;

import com.redhat.mcp.languagetools.lsp.installer.InstallerContext;

/**
 * Base class for installer tasks.
 * Inspired by lsp4ij InstallerTask.
 */
public abstract class InstallerTask {

    private final String id;
    private final String name;
    private final InstallerTask onFail;
    private final InstallerTask onSuccess;

    public InstallerTask(String id, String name, InstallerTask onFail, InstallerTask onSuccess) {
        this.id = id;
        this.name = name;
        this.onFail = onFail;
        this.onSuccess = onSuccess;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public InstallerTask getOnFail() {
        return onFail;
    }

    public InstallerTask getOnSuccess() {
        return onSuccess;
    }

    /**
     * Execute this task and its success/failure chain.
     */
    public boolean execute(InstallerContext context) {
        context.log("- Step: " + getName());

        if (run(context)) {
            if (onSuccess != null) {
                return onSuccess.execute(context);
            }
            return true;
        }

        if (onFail != null) {
            return onFail.execute(context);
        }
        return false;
    }

    /**
     * Run this specific task.
     * @return true if successful, false otherwise
     */
    protected abstract boolean run(InstallerContext context);
}
