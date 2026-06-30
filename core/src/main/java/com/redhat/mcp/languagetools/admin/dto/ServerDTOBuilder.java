package com.redhat.mcp.languagetools.admin.dto;

import com.redhat.mcp.languagetools.lsp.server.LspServer;
import com.redhat.mcp.languagetools.lsp.server.LspServerConfig;
import com.redhat.mcp.languagetools.server.ServerStatus;
import com.redhat.mcp.languagetools.workspace.Workspace;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Builder for Server DTOs (Config and Runtime).
 */
@ApplicationScoped
public class ServerDTOBuilder {

    @Inject
    ContributionDTOBuilder contributionBuilder;

    /**
     * Build ServerConfigDTO from LspServerConfig.
     */
    public ServerConfigDTO buildConfig(LspServerConfig config) {
        return new ServerConfigDTO(
            config.getId(),
            config.getName(),
            config.getDescription(),
            config.getDocumentSelector(),
            config.getCommand(),
            config.getArgs(),
            config.getEnv(),
            config.getWorkingDirectory(),
            config.getInitializationOptions(),
            contributionBuilder.buildContributions(config),
            config.isExtension()
        );
    }

    /**
     * Build ServerRuntimeDTO for a server in a workspace.
     */
    public ServerRuntimeDTO buildRuntime(LspServerConfig config, Workspace workspace) {
        String serverId = config.getId();
        LspServer lspServer = workspace.getLspServer(serverId);

        // Check if this is an extension with a parent server
        String parentServerId = null;
        var contributionManager = workspace.getLspContributionManager();
        if (contributionManager != null) {
            parentServerId = contributionManager.getParentServerId(serverId);
        }

        ServerRuntimeDTO.ExternalInstanceInfo externalInfo = null;
        Long pid = null;
        String command = null;
        ServerStatus status;
        String statusMessage = null;
        boolean isReady = false;

        if (parentServerId != null) {
            // Extension: use parent server's status
            LspServer parentServer = workspace.getLspServer(parentServerId);
            status = workspace.getServerStatus(parentServerId);
            isReady = parentServer != null && parentServer.isReady();
            statusMessage = parentServer != null ? parentServer.getStatusMessage() : null;
            pid = parentServer != null ? parentServer.getPid() : null;
            command = parentServer != null ? parentServer.getStartCommand() : null;

            if (parentServer != null) {
                var currentInstance = parentServer.getCurrentInstance();
                if (currentInstance != null) {
                    externalInfo = new ServerRuntimeDTO.ExternalInstanceInfo(
                        currentInstance.port,
                        currentInstance.pid,
                        true,
                        currentInstance.clientName,
                        currentInstance.clientVersion
                    );
                }
            }
        } else {
            // Normal server: use its own status
            if (lspServer != null) {
                var currentInstance = lspServer.getCurrentInstance();
                if (currentInstance != null) {
                    externalInfo = new ServerRuntimeDTO.ExternalInstanceInfo(
                        currentInstance.port,
                        currentInstance.pid,
                        true,
                        currentInstance.clientName,
                        currentInstance.clientVersion
                    );
                }

                pid = lspServer.getPid();
                command = lspServer.getStartCommand();
                statusMessage = lspServer.getStatusMessage();
                isReady = lspServer.isReady();
            }

            status = workspace.getServerStatus(serverId);
        }

        if (statusMessage != null && statusMessage.length() > 100) {
            statusMessage = statusMessage.substring(0, 97) + "...";
        }

        return new ServerRuntimeDTO(
            serverId,
            status,
            statusMessage,
            isReady,
            pid,
            command,
            externalInfo,
            parentServerId
        );
    }
}
