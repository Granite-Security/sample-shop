package org.granitesecurity.authserver.user;

import org.granitesecurity.authserver.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Both endpoints are unauthenticated, unlike AccountControllerTest's
// /api/me/password — no jwt() request post-processor needed here.
@WebMvcTest(PasswordResetController.class)
@Import(SecurityConfig.class)
class PasswordResetControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PasswordResetService passwordResetService;

    // defaultSecurityFilterChain (part of the imported SecurityConfig)
    // requires this bean to construct.
    @MockitoBean
    private GoogleOidcUserService googleOidcUserService;

    @Test
    void requestReturns200RegardlessOfOutcome() throws Exception {
        doNothing().when(passwordResetService).requestReset(eq("alice@example.com"));

        mockMvc.perform(post("/api/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "email": "alice@example.com" }
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void requestReturns400ForInvalidEmail() throws Exception {
        mockMvc.perform(post("/api/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "email": "not-an-email" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").exists());
    }

    @Test
    void confirmReturns204OnSuccess() throws Exception {
        doNothing().when(passwordResetService).confirmReset(eq("some-token"), eq("new-password-123"));

        mockMvc.perform(post("/api/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "token": "some-token", "newPassword": "new-password-123" }
                                """))
                .andExpect(status().isNoContent());
    }

    @Test
    void confirmReturns400ForInvalidToken() throws Exception {
        doThrow(new InvalidResetTokenException("This reset link is invalid or has expired."))
                .when(passwordResetService).confirmReset(any(), any());

        mockMvc.perform(post("/api/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "token": "bogus", "newPassword": "new-password-123" }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void confirmReturns400WithFieldErrorsOnInvalidInput() throws Exception {
        mockMvc.perform(post("/api/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "token": "", "newPassword": "short" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.token").exists())
                .andExpect(jsonPath("$.errors.newPassword").exists());
    }
}
