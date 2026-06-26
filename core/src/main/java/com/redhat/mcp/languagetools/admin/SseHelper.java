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
package com.redhat.mcp.languagetools.admin;

import io.smallrye.mutiny.Multi;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * Helper for SSE streams to prevent HTTP/1.1 connection pool exhaustion.
 */
public class SseHelper {

    /**
     * Wrap a Multi stream with auto-close after inactivity.
     * This prevents SSE connections from staying open forever and exhausting
     * the HTTP/1.1 connection pool (max 6 connections per domain).
     *
     * @param stream the source stream
     * @param inactivityTimeout timeout duration (e.g., Duration.ofSeconds(30))
     * @param <T> stream item type
     * @return wrapped stream that auto-closes after inactivity
     */
    public static <T> Multi<T> withAutoClose(Multi<T> stream, Duration inactivityTimeout) {
        return stream
                .onOverflow().drop()
                .ifNoItem().after(inactivityTimeout).failWith(new TimeoutException("No activity"))
                .onFailure(TimeoutException.class).recoverWithCompletion();
    }

    /**
     * Default auto-close with 30 seconds timeout.
     */
    public static <T> Multi<T> withAutoClose(Multi<T> stream) {
        return withAutoClose(stream, Duration.ofSeconds(30));
    }
}
