package org.granitesecurity.authserver.user;

import org.granitesecurity.authserver.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InternalUserController.class)
@Import(SecurityConfig.class)
class InternalUserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserAdminService userAdminService;

    // defaultSecurityFilterChain (a different @Order chain of the imported
    // SecurityConfig) needs this bean to construct.
    @MockitoBean
    private GoogleOidcUserService googleOidcUserService;

    private static final String BLOCK_BODY = """
            { "actor": "admin" }
            """;

    private static org.springframework.test.web.servlet.request.RequestPostProcessor identityAdmin() {
        return jwt().jwt(builder -> builder.claim("scope", "identity.admin"));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor internalScope() {
        return jwt().jwt(builder -> builder.claim("scope", "internal"));
    }

    private static AdminUserResponse sampleUser() {
        return new AdminUserResponse(1L, "alice", "alice@example.com", "Alice", "A",
                true, "LOCAL", null, List.of("ROLE_USER"), null, null, OffsetDateTime.now());
    }

    @Test
    void returns401WithoutAToken() throws Exception {
        mockMvc.perform(get("/api/internal/users"))
                .andExpect(status().isUnauthorized());
    }

    // The security property of §3.1: internal-service is a shared identity, so
    // holding it must never amount to holding the identity store. If this test
    // ever goes green on a 2xx, the blast radius of an internal-service leak has
    // silently grown to include deleting users.
    @Test
    void rejectsATokenCarryingOnlyScopeInternal() throws Exception {
        mockMvc.perform(get("/api/internal/users").with(internalScope()))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/internal/users/alice/block")
                        .with(internalScope())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BLOCK_BODY))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/internal/users/alice/unblock").with(internalScope()))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/internal/users/alice").with(internalScope()))
                .andExpect(status().isForbidden());

        verify(userAdminService, never()).delete(eq("alice"));
    }

    @Test
    void listsUsersForIdentityAdminScope() throws Exception {
        when(userAdminService.listUsers()).thenReturn(List.of(sampleUser()));

        mockMvc.perform(get("/api/internal/users").with(identityAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("alice"))
                .andExpect(jsonPath("$[0].provider").value("LOCAL"))
                .andExpect(jsonPath("$[0].roles[0]").value("ROLE_USER"));
    }

    @Test
    void blocksRecordingTheActor() throws Exception {
        when(userAdminService.block("alice", "admin")).thenReturn(sampleUser());

        mockMvc.perform(post("/api/internal/users/alice/block")
                        .with(identityAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BLOCK_BODY))
                .andExpect(status().isOk());

        verify(userAdminService).block("alice", "admin");
    }

    @Test
    void rejectsBlockWithoutAnActor() throws Exception {
        mockMvc.perform(post("/api/internal/users/alice/block")
                        .with(identityAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"actor\": \"\" }"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returns409WhenUnblockingAUserThatIsNotBlocked() throws Exception {
        doThrow(new UserNotBlockedException("User is not blocked: alice"))
                .when(userAdminService).unblock("alice");

        mockMvc.perform(post("/api/internal/users/alice/unblock").with(identityAdmin()))
                .andExpect(status().isConflict());
    }

    @Test
    void returns404ForAnUnknownUser() throws Exception {
        doThrow(new UserNotFoundException("No such user: nobody"))
                .when(userAdminService).delete("nobody");

        mockMvc.perform(delete("/api/internal/users/nobody").with(identityAdmin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletesAndReturns204() throws Exception {
        mockMvc.perform(delete("/api/internal/users/alice").with(identityAdmin()))
                .andExpect(status().isNoContent());

        verify(userAdminService).delete("alice");
    }
}
