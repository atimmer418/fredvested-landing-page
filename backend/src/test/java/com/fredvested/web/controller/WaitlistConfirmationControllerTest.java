package com.fredvested.web.controller;

import com.fredvested.web.service.AddressRateLimiter;
import com.fredvested.web.service.LandingUrls;
import com.fredvested.web.service.RateLimiterService;
import com.fredvested.web.service.SignupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(WaitlistConfirmationController.class)
@Import(LandingUrls.class)
class WaitlistConfirmationControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean SignupService signupService;
    @MockBean RateLimiterService ipLimiter;
    @MockBean AddressRateLimiter addressLimiter;

    @BeforeEach
    void allowThrough() {
        when(ipLimiter.isAllowed(anyString())).thenReturn(true);
        when(addressLimiter.allow(anyString())).thenReturn(true);
    }

    @Test
    void confirmed_redirectsToTheConfirmedPage_withTheHoursBand() throws Exception {
        when(signupService.confirm("tok")).thenReturn(new SignupService.Confirmation(SignupService.ConfirmOutcome.CONFIRMED, "<1"));
        mockMvc.perform(get("/api/waitlist/confirm").param("token", "tok"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://fredvested.com/confirmed?status=confirmed&hours=%3C1"));
        when(signupService.confirm("tok2")).thenReturn(new SignupService.Confirmation(SignupService.ConfirmOutcome.CONFIRMED, "72+"));
        mockMvc.perform(get("/api/waitlist/confirm").param("token", "tok2"))
                .andExpect(header().string("Location", "https://fredvested.com/confirmed?status=confirmed&hours=72%2B"));
    }

    @Test
    void expired_redirectsToTheExpiredState() throws Exception {
        when(signupService.confirm("old")).thenReturn(new SignupService.Confirmation(SignupService.ConfirmOutcome.EXPIRED, null));
        mockMvc.perform(get("/api/waitlist/confirm").param("token", "old"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://fredvested.com/confirmed?status=expired"));
    }

    @Test
    void unknownAndAlreadyUsedTokens_produceByteIdenticalResponses() throws Exception {
        when(signupService.confirm(anyString())).thenReturn(new SignupService.Confirmation(SignupService.ConfirmOutcome.INVALID, null));
        MockHttpServletResponse unknown = mockMvc.perform(get("/api/waitlist/confirm").param("token", "never-issued")).andReturn().getResponse();
        MockHttpServletResponse used = mockMvc.perform(get("/api/waitlist/confirm").param("token", "used-before")).andReturn().getResponse();
        assertEquals(unknown.getStatus(), used.getStatus());
        assertEquals(unknown.getHeaderNames(), used.getHeaderNames());
        for (String h : unknown.getHeaderNames()) assertEquals(unknown.getHeaders(h), used.getHeaders(h), h);
        assertEquals(unknown.getContentAsString(), used.getContentAsString());
        assertEquals("https://fredvested.com/confirmed?status=invalid", unknown.getRedirectedUrl());
    }

    @Test
    void missingToken_isTheSameInvalidResponse() throws Exception {
        when(signupService.confirm(isNull())).thenReturn(new SignupService.Confirmation(SignupService.ConfirmOutcome.INVALID, null));
        mockMvc.perform(get("/api/waitlist/confirm"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://fredvested.com/confirmed?status=invalid"));
    }

    @Test
    void resend_answersIdentically_forKnownAndUnknownAddresses() throws Exception {
        MockHttpServletResponse known = mockMvc.perform(post("/api/waitlist/resend-confirmation")
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"On-List@Example.com\"}")).andReturn().getResponse();
        MockHttpServletResponse unknown = mockMvc.perform(post("/api/waitlist/resend-confirmation")
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"nobody@example.com\"}")).andReturn().getResponse();
        assertEquals(200, known.getStatus());
        assertEquals(known.getStatus(), unknown.getStatus());
        assertEquals(known.getContentAsString(), unknown.getContentAsString());
        verify(signupService).requestResend("on-list@example.com");
        verify(signupService).requestResend("nobody@example.com");
    }

    @Test
    void resend_isRateLimited_perAddress_andPerIp_beforeAnyLookup() throws Exception {
        when(addressLimiter.allow("spam@example.com")).thenReturn(false);
        mockMvc.perform(post("/api/waitlist/resend-confirmation")
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"spam@example.com\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("rate_limited"));
        when(ipLimiter.isAllowed(anyString())).thenReturn(false);
        mockMvc.perform(post("/api/waitlist/resend-confirmation")
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"fine@example.com\"}"))
                .andExpect(status().isTooManyRequests());
        verify(signupService, never()).requestResend(anyString());
    }

    @Test
    void resend_rejectsAMalformedAddress_withTheStandardCode() throws Exception {
        mockMvc.perform(post("/api/waitlist/resend-confirmation")
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_email"));
        verify(signupService, never()).requestResend(anyString());
    }

    @Test
    void headOnConfirm_isANoOp_forLinkScanners() throws Exception {
        mockMvc.perform(head("/api/waitlist/confirm").param("token", "tok"))
                .andExpect(status().isOk());
        verify(signupService, never()).confirm(anyString());
    }

    @Test
    void unsubscribeGet_onlyShowsAButton_andNeverSuppresses() throws Exception {
        when(signupService.hasUnsubscribeToken("good")).thenReturn(true);
        String page = mockMvc.perform(get("/api/waitlist/unsubscribe").param("token", "good"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(page.contains("method=\"post\""));
        assertTrue(page.contains("name=\"token\" value=\"good\""));
        assertTrue(page.contains("Unsubscribe"));
        assertFalse(page.contains("<link") || page.contains("<script") || page.contains("googleapis"), "self-contained page");
        verify(signupService, never()).unsubscribe(anyString());
    }

    @Test
    void unsubscribeGet_withAnUnknownToken_showsANotice_withoutAForm() throws Exception {
        when(signupService.hasUnsubscribeToken("bad<script>")).thenReturn(false);
        String page = mockMvc.perform(get("/api/waitlist/unsubscribe").param("token", "bad<script>"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(page.contains("isn't valid"));
        assertFalse(page.contains("<form"));
        assertFalse(page.contains("<script>"), "token is never reflected unescaped");
    }

    @Test
    void unsubscribePost_suppresses_andRedirects_forKnownAndUnknownTokens() throws Exception {
        when(signupService.unsubscribe("good")).thenReturn(true);
        when(signupService.unsubscribe("bad")).thenReturn(false);
        mockMvc.perform(post("/api/waitlist/unsubscribe").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("token", "good"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://fredvested.com/confirmed?status=unsubscribed"));
        mockMvc.perform(post("/api/waitlist/unsubscribe").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("token", "bad"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://fredvested.com/confirmed?status=invalid"));
    }
}
