# MCP Language Tools

> **Note**: This is a Proof of Concept (POC) project.

MCP Language Tools is an [MCP (Model Context Protocol)](https://modelcontextprotocol.io/) server that provides AI assistants with [LSP (Language Server Protocol)](https://microsoft.github.io/language-server-protocol/) capabilities through MCP tools.

## What is it?

**MCP Language Tools acts as a container for language servers**, similar to platforms like VS Code or [Bob](https://bob.ibm.com/). Just as these platforms host and manage language servers to provide IDE features, MCP Language Tools manages LSP servers to make their capabilities available to AI assistants through the Model Context Protocol.

This allows AI assistants and tools that support MCP to leverage the same powerful language tooling that developers use in their IDEs - diagnostics, code navigation, symbol information, and more.

**MCP Language Tools also provides an admin console** (`http://localhost:7654/admin`) to visualize workspaces, manage LSP servers (install, start, stop), monitor their status, and view LSP communication traces for debugging.

![Admin Console](docs/images/admin-console.png)

**Compatible with any MCP client**, including:
- [Claude Desktop](https://claude.ai/download)
- [Bob IDE](https://bob.ibm.com/docs/ide)
- And any other MCP-compatible AI assistant

## Overview

This MCP server exposes LSP features as MCP tools. Currently available tools:
- **`get_diagnostics`**: Get errors, warnings, and other diagnostics from code files
- **`find_references`**: Find all references to a symbol at a specific position

More LSP capabilities (hover information, go to definition, code completion, etc.) will be added in future versions.

## Key Features

### LSP Server Management
- **Declarative Configuration**: LSP servers are registered via JSON descriptors (similar to VS Code's language server extensions)
- **Automatic Installation**: Servers can be automatically downloaded and installed on first use
- **Dual Connection Mode**:
  - **MCP-Managed**: Start and control LSP servers directly from the MCP server
  - **IDE Connection**: Connect to existing LSP servers launched by IDEs (VS Code, IntelliJ IDEA, etc.) via socket

### Admin Console
Access the admin UI at `http://localhost:7654/admin` to:
- **Manage Workspaces**: View all active workspaces and connected MCP clients
- **Control LSP Servers**: Install, start, stop, restart servers
- **Monitor Status**: See real-time status of each LSP server (Starting, Running, Connected to IDE, etc.)
- **View LSP Traces**: Debug LSP communication with detailed request/response logs
- **Switch Modes**: Disconnect from IDE and start MCP-managed server, or vice versa

### Multi-Workspace Support
- Multiple workspaces can be managed simultaneously
- Each workspace can have multiple LSP servers running
- Multiple MCP clients can connect to the same workspace

## Technology Stack

This project is built with:
- **[Quarkus MCP Server](https://docs.quarkiverse.io/quarkus-mcp-server/dev/index.html)**: MCP (Model Context Protocol) implementation for Quarkus
- **[Quarkus](https://quarkus.io/)**: Supersonic Subatomic Java Framework
- **[LSP4J](https://github.com/eclipse-lsp4j/lsp4j)**: Java implementation of the Language Server Protocol

## Project Structure

This project uses a multi-module Maven architecture to support a flexible extension system:

```
mcp-lsp/
├── pom.xml                      # Parent POM
├── core/                        # Core framework (LSP integration, MCP tools, admin UI)
├── extensions/                  # LSP server extensions
│   ├── lemminx/                 # XML language server (LemMinX)
│   ├── microprofile/            # MicroProfile language server
│   ├── quarkus/                 # Quarkus language server
│   └── jdtls/                   # Java language server (JDT.LS) with custom code
└── dev/                         # Development distribution (core + all extensions)
```

### Module Roles

- **`core/`**: The MCP Language Tools framework without any bundled LSP servers. Contains:
  - MCP server implementation and tools (`get_diagnostics`, `find_references`, etc.)
  - LSP server lifecycle management
  - Admin console UI
  - Extension discovery and loading system

- **`extensions/`**: LSP server extensions, each containing:
  - `server.json`: Server configuration (command, document selectors, etc.)
  - `installer.json`: Installation steps (download URL, extraction, etc.)
  - Optional Java code for advanced server implementations (like JDT.LS)
  - Optional SPI (`META-INF/services`) for custom server factories

- **`dev/`**: Development distribution that bundles core + all extensions for easy local development with hot-reload

### Extension System

This modular architecture enables:
- **Development mode**: All extensions loaded via classpath (hot-reload enabled)
- **Production mode** (future): Core can load extensions dynamically from JARs
- **Custom extensions**: Developers can create their own LSP server extensions by packaging them as JARs with `server.json` + optional code

## Getting Started

### 1. Running the application in dev mode

You can run your application in dev mode that enables live coding using:

```shell script for Windows
cd dev; ../mvnw quarkus:dev
```

```shell script for Mac/Unix
cd dev && ../mvnw quarkus:dev
```

> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at <http://localhost:7654/q/dev/>.

### 2. Configure your MCP client

Add the following configuration to your MCP client settings:

**For Claude Desktop** (`claude_desktop_config.json`):
```json
{
  "mcpServers": {
    "mcp-languagetools": {
      "type": "http",
      "url": "http://localhost:7654/mcp"
    }
  }
}
```

**For Bob IDE**: Configure the MCP server connection in Bob's settings with the same URL: `http://localhost:7654/mcp`

### 3. Access the Admin Console

Open your browser and navigate to: <http://localhost:7654/admin>

## Packaging and running the application

The application can be packaged using:

```shell script
./mvnw package
```

It produces the `quarkus-run.jar` file in the `target/quarkus-app/` directory.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `target/quarkus-app/lib/` directory.

The application is now runnable using `java -jar target/quarkus-app/quarkus-run.jar`.

If you want to build an _über-jar_, execute the following command:

```shell script
./mvnw package -Dquarkus.package.jar.type=uber-jar
```

The application, packaged as an _über-jar_, is now runnable using `java -jar target/*-runner.jar`.

## Creating a native executable

You can create a native executable using:

```shell script
./mvnw package -Dnative
```

Or, if you don't have GraalVM installed, you can run the native executable build in a container using:

```shell script
./mvnw package -Dnative -Dquarkus.native.container-build=true
```

You can then execute your native executable with: `./dev/target/mcp-language-tools-dev-*-runner`

If you want to learn more about building native executables, please consult <https://quarkus.io/guides/maven-tooling>.

