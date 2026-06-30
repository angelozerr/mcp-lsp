package com.redhat.mcp.languagetools.installer.download;

/**
 * Interface for progress indicators that can receive content length information.
 */
public interface ContentLengthAware {
    void setContentLength(long contentLength);
}
