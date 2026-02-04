package com.openbooking.common.util;

/**
 * Common constants used across all services.
 */
public final class Constants {
    private Constants() {
        // Utility class
    }

    public static final String LOCK_PREFIX = "lock:room:";
    public static final String CACHE_ROOM_PREFIX = "room:availability:";
    
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
}
