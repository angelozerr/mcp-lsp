package com.redhat.mcp.languagetools.admin.dto;

import com.redhat.mcp.languagetools.lsp.server.LspServer;
import com.redhat.mcp.languagetools.lsp.server.LspServerConfig;
import com.redhat.mcp.languagetools.lsp.server.ServerStatus;
import com.redhat.mcp.languagetools.workspace.Workspace;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

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
            contributionBuilder.buildContributions(config)
        );
    }

    /**
     * Build ServerRuntimeDTO for a server in a workspace.
     */
    public ServerRuntimeDTO buildRuntime(LspServerConfig config, Workspace workspace) {
        String serverId = config.getId();
        LspServer lspServer = workspace.getLspServer(serverId);

        ServerRuntimeDTO.ExternalInstanceInfo externalInfo = null;
        Long pid = null;
        String command = null;

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
        }

        String statusMessage = lspServer != null ? lspServer.getStatusMessage() : null;
        if (statusMessage != null && statusMessage.length() > 100) {
            statusMessage = statusMessage.substring(0, 97) + "...";
        }

        boolean isReady = lspServer != null && lspServer.isReady();
        ServerStatus status = workspace.getServerStatus(serverId);

        return new ServerRuntimeDTO(
            serverId,
            status,
            statusMessage,
            isReady,
            pid,
            command,
            externalInfo
        );
    }
}
