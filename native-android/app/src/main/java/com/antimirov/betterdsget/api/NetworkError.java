package com.antimirov.betterdsget.api;

/**
 * Network-level error — the request never reached the NAS or timed out.
 *
 * KEY FIX: The original Synology DS Get app treated SocketTimeoutException and
 * ConnectTimeoutException as session timeouts, logging the user out every ~2 minutes.
 * NetworkError is ALWAYS transient and NEVER a session error.
 */
public class NetworkError extends Exception {
    public enum Category {
        TIMEOUT,
        NETWORK,
        OFFLINE
    }

    private final Category category;

    public NetworkError(String message, Category category) {
        super(message);
        this.category = category != null ? category : Category.NETWORK;
    }

    public NetworkError(String message, Category category, Throwable cause) {
        super(message, cause);
        this.category = category != null ? category : Category.NETWORK;
    }

    public Category getCategory() {
        return category;
    }

    public boolean isTransient() {
        return true;
    }

    public boolean isSessionError() {
        return false;
    }
}

