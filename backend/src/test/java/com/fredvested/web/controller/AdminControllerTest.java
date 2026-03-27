package com.fredvested.web.controller;

import com.fredvested.web.model.WaitlistEntry;
import com.fredvested.web.repository.WaitlistRepository;
import com.fredvested.web.service.EmailService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminController.class)
@TestPropertySource(properties = "admin.secret=test-secret")
class AdminControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean WaitlistRepository repository;
    @MockBean EmailService emailService;

    @Test
    void batchEmail_returns403_whenSecretIsWrong() throws Exception {
        mockMvc.perform(post("/api/admin/batch-email")
                .header("X-Admin-Secret", "wrong-secret"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(emailService);
    }

    @Test
    void batchEmail_sendsToAllEntriesWithEmail_andReturnsCount() throws Exception {
        WaitlistEntry e1 = new WaitlistEntry();
        e1.setEmail("a@example.com");
        e1.setStatus(WaitlistEntry.WaitlistStatus.WAITLISTFOUNDER);

        WaitlistEntry e2 = new WaitlistEntry();
        e2.setEmail("b@example.com");
        e2.setStatus(WaitlistEntry.WaitlistStatus.WAITLISTNORMAL);

        WaitlistEntry e3 = new WaitlistEntry(); // null email — should be skipped
        e3.setStatus(WaitlistEntry.WaitlistStatus.WAITLISTFOUNDER);

        when(repository.findAll()).thenReturn(List.of(e1, e2, e3));

        mockMvc.perform(post("/api/admin/batch-email")
                .header("X-Admin-Secret", "test-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(2))
                .andExpect(jsonPath("$.skipped").value(1))
                .andExpect(jsonPath("$.errors").value(0));

        verify(emailService).sendConfirmationEmail("a@example.com");
        verify(emailService).sendConfirmationEmail("b@example.com");
    }

    @Test
    void batchEmail_countsErrors_andContinuesLoop() throws Exception {
        WaitlistEntry e1 = new WaitlistEntry();
        e1.setEmail("good@example.com");
        e1.setStatus(WaitlistEntry.WaitlistStatus.WAITLISTFOUNDER);

        WaitlistEntry e2 = new WaitlistEntry();
        e2.setEmail("bad@example.com");
        e2.setStatus(WaitlistEntry.WaitlistStatus.WAITLISTNORMAL);

        when(repository.findAll()).thenReturn(List.of(e1, e2));
        doThrow(new RuntimeException("Resend error"))
                .when(emailService).sendConfirmationEmail("bad@example.com");

        mockMvc.perform(post("/api/admin/batch-email")
                .header("X-Admin-Secret", "test-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(1))
                .andExpect(jsonPath("$.skipped").value(0))
                .andExpect(jsonPath("$.errors").value(1));
    }
}
