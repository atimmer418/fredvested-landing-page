package com.fredvested.web.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Per-address limiter for confirmation resends: 1 per 10 minutes and 3 per day.
 * Keyed by a hash of the address, in memory, and applied whether or not the
 * address exists, so the limit itself cannot be used to probe the list.
 */
@Service
public class AddressRateLimiter {

    static final long SHORT_WINDOW_MS = 10 * 60_000L;
    static final int SHORT_MAX = 1;
    static final long LONG_WINDOW_MS = 24 * 60 * 60_000L;
    static final int LONG_MAX = 3;

    private final ConcurrentHashMap<String, Deque<Long>> attempts = new ConcurrentHashMap<>();

    public boolean allow(String email) {
        return allow(email, System.currentTimeMillis());
    }

    boolean allow(String email, long now) {
        Deque<Long> log = attempts.computeIfAbsent(key(email), k -> new ConcurrentLinkedDeque<>());
        synchronized (log) {
            while (!log.isEmpty() && now - log.peekFirst() > LONG_WINDOW_MS) log.pollFirst();
            if (log.size() >= LONG_MAX) return false;
            long recent = log.stream().filter(t -> now - t <= SHORT_WINDOW_MS).count();
            if (recent >= SHORT_MAX) return false;
            log.addLast(now);
            return true;
        }
    }

    @Scheduled(fixedRate = 3_600_000)
    public void cleanup() {
        long now = System.currentTimeMillis();
        attempts.entrySet().removeIf(e -> {
            Deque<Long> log = e.getValue();
            synchronized (log) {
                while (!log.isEmpty() && now - log.peekFirst() > LONG_WINDOW_MS) log.pollFirst();
                return log.isEmpty();
            }
        });
    }

    private static String key(String email) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(email.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
