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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Imports the real SecurityConfig (not just the controller) so the 409/400/201
// tests here also exercise the actual authorizeHttpRequests/csrf rules from
// Phase 3, rather than a hand-rolled stand-in that could drift from prod.
@WebMvcTest(RegistrationController.class)
@Import(SecurityConfig.class)
class RegistrationControllerTest {

    @MockitoBean
    private GoogleOidcUserService googleOidcUserService;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserRegistrationService userRegistrationService;

    private static final String VALID_BODY = """
            {
              "username": "jdoe",
              "email": "jdoe@example.com",
              "password": "password123"
            }
            """;

    @Test
    void returns201OnValidInput() throws Exception {
        when(userRegistrationService.register(any(RegistrationRequest.class)))
                .thenReturn(new RegistrationResponse("jdoe", "jdoe@example.com"));

        mockMvc.perform(post("/api/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("jdoe"))
                .andExpect(jsonPath("$.email").value("jdoe@example.com"));
    }

    @Test
    void returns400WithFieldErrorsOnInvalidInput() throws Exception {
        String invalidBody = """
                {
                  "username": "a",
                  "email": "not-an-email",
                  "password": "short"
                }
                """;

        mockMvc.perform(post("/api/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.username").exists())
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.password").exists());
    }

    @Test
    void returns409OnDuplicate() throws Exception {
        when(userRegistrationService.register(any(RegistrationRequest.class)))
                .thenThrow(new DuplicateUserException("username", "Username is already taken"));

        mockMvc.perform(post("/api/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.field").value("username"));
    }

    @Test
    void endpointIsReachableUnauthenticatedWithNoCsrfToken() throws Exception {
        // @WebMvcTest loads the real security filter chain if one is on the
        // classpath; this specifically pins the Phase 3 trap: no .with(csrf())
        // and no authenticated user, yet the request must not be rejected by
        // Spring Security before reaching the controller.
        when(userRegistrationService.register(any(RegistrationRequest.class)))
                .thenReturn(new RegistrationResponse("jdoe", "jdoe@example.com"));

        mockMvc.perform(post("/api/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated());
    }
}
