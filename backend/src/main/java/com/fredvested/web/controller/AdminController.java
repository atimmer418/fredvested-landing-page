package com.fredvested.web.controller;

import com.fredvested.web.model.WaitlistEntry;
import com.fredvested.web.repository.WaitlistRepository;
import com.fredvested.web.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    @Value("${admin.secret}")
    private String adminSecret;

    @Autowired
    private WaitlistRepository repository;

    @Autowired
    private EmailService emailService;

    @PostMapping("/batch-email")
    public ResponseEntity<?> sendBatchEmail(@RequestHeader("X-Admin-Secret") String secret) {
        if (!adminSecret.equals(secret)) {
            return ResponseEntity.status(403).body(Map.of("message", "Forbidden"));
        }

        List<WaitlistEntry> entries = repository.findAll();
        int sent = 0, skipped = 0, errors = 0;

        for (WaitlistEntry entry : entries) {
            if (entry.getEmail() == null || entry.getEmail().isEmpty()) {
                skipped++;
                continue;
            }
            try {
                emailService.sendConfirmationEmail(entry.getEmail());
                sent++;
            } catch (Exception e) {
                log.error("Batch send failed for {}: {}", entry.getEmail(), e.getMessage());
                errors++;
            }
        }

        return ResponseEntity.ok(Map.of("sent", sent, "skipped", skipped, "errors", errors));
    }
}
