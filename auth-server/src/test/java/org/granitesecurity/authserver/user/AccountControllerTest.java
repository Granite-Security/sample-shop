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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountController.class)
@Import(SecurityConfig.class)
class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PasswordChangeService passwordChangeService;

    // defaultSecurityFilterChain (still part of the imported SecurityConfig,
    // just a different @Order chain) requires this bean to construct.
    @MockitoBean
    private GoogleOidcUserService googleOidcUserService;

    private static final String VALID_BODY = """
            {
              "currentPassword": "old-password",
              "newPassword": "new-password-123"
            }
            """;

    @Test
    void returns401WithoutAToken() throws Exception {
        mockMvc.perform(put("/api/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returns204OnSuccess() throws Exception {
        doNothing().when(passwordChangeService).changePassword(eq("alice"), any(ChangePasswordRequest.class));

        mockMvc.perform(put("/api/me/password")
                        .with(jwt().jwt(builder -> builder.subject("alice")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isNoContent());
    }

    @Test
    void returns400OnIncorrectCurrentPassword() throws Exception {
        doThrow(new IncorrectPasswordException("Current password is incorrect"))
                .when(passwordChangeService).changePassword(eq("alice"), any(ChangePasswordRequest.class));

        mockMvc.perform(put("/api/me/password")
                        .with(jwt().jwt(builder -> builder.subject("alice")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returns409ForNonLocalAccount() throws Exception {
        doThrow(new NonLocalAccountException("This account signs in with Google; there is no password to change."))
                .when(passwordChangeService).changePassword(eq("alice"), any(ChangePasswordRequest.class));

        mockMvc.perform(put("/api/me/password")
                        .with(jwt().jwt(builder -> builder.subject("alice")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void returns400WithFieldErrorsOnInvalidInput() throws Exception {
        String invalidBody = """
                {
                  "currentPassword": "",
                  "newPassword": "short"
                }
                """;

        mockMvc.perform(put("/api/me/password")
                        .with(jwt().jwt(builder -> builder.subject("alice")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.errors.currentPassword").exists())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.errors.newPassword").exists());
    }
}
