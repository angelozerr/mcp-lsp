/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 * Red Hat, Inc. - initial API and implementation
 ******************************************************************************/
package com.redhat.mcp.languagetools.tools;

import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkiverse.mcp.server.ToolResponseEncoder;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.CompletableFuture;

/**
 * Encoder for CompletableFuture<String> tool responses.
 * Allows tools to return CompletableFuture<String> instead of blocking with .join().
 */
@ApplicationScoped
public class CompletableFutureEncoder implements ToolResponseEncoder<CompletableFuture> {

    @Override
    public boolean supports(Class<?> runtimeType) {
        return CompletableFuture.class.equals(runtimeType);
    }

    @Override
    public ToolResponse encode(CompletableFuture value) {
        return ToolResponse.success(value.join().toString());
    }
}
