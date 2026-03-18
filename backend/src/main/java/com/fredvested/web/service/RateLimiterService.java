package com.fredvested.web.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Simple in-memory rate limiter.
 * Limits each IP to a configurable number of POST requests per time window.
 * Automatically cleans up stale entries every 5 minutes.
 */
@Service
public class RateLimiterService {

    private static final int MAX_REQUESTS = 3;         // max attempts per window
    private static final long WINDOW_MS = 60_000L;     // 1 minute window

    private final ConcurrentHashMap<String, Deque<Long>> requestLog = new ConcurrentHashMap<>();

    /**
     * Check if a request from this key (IP hash) is allowed.
     * Returns true if under the limit, false if rate-limited.
     */
    public boolean isAllowed(String key) {
        long now = System.currentTimeMillis();
        Deque<Long> timestamps = requestLog.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());

        // Evict expired timestamps outside the window
        while (!timestamps.isEmpty() && now - timestamps.peekFirst() > WINDOW_MS) {
            timestamps.pollFirst();
        }

        if (timestamps.size() >= MAX_REQUESTS) {
            return false;
        }

        timestamps.addLast(now);
        return true;
    }

    /**
     * Periodic cleanup of stale entries to prevent memory leaks.
     * Runs every 5 minutes.
     */
    @Scheduled(fixedRate = 300_000)
    public void cleanup() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Deque<Long>>> it = requestLog.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Deque<Long>> entry = it.next();
            Deque<Long> timestamps = entry.getValue();

            // Evict old timestamps
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() > WINDOW_MS) {
                timestamps.pollFirst();
            }

            // Remove the key entirely if no timestamps remain
            if (timestamps.isEmpty()) {
                it.remove();
            }
        }
    }
}
