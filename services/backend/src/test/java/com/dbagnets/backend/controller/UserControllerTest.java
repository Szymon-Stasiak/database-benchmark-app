package com.dbagnets.backend.controller;

import com.dbagnets.backend.config.SecurityConfig;
import com.dbagnets.backend.entity.User;
import com.dbagnets.backend.service.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = UserController.class)
@Import(SecurityConfig.class)
class UserControllerTest {

    @Autowired
    MockMvc mockMvc;

    /** Replaces the auto-configured NimbusJwtDecoder so the test context never reaches Google's JWKS. */
    @MockBean
    JwtDecoder jwtDecoder;

    @MockBean
    CurrentUserService currentUserService;

    @Test
    void returnsProfileFromResolvedUser() throws Exception {
        User alice = User.createFromJwtClaims(
                "sub-alice", "alice@example.com", "Alice Example", "https://example.com/alice.png"
        );
        when(currentUserService.resolve(any(Jwt.class))).thenReturn(alice);

        mockMvc.perform(get("/api/user").with(jwt().jwt(builder -> builder
                .subject("sub-alice")
                .claim("email", "alice@example.com")
                .claim("name", "Alice Example")
                .claim("picture", "https://example.com/alice.png")
        )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.name").value("Alice Example"))
                .andExpect(jsonPath("$.picture").value("https://example.com/alice.png"));
    }

    @Test
    void unauthenticatedRequestReturns401() throws Exception {
        mockMvc.perform(get("/api/user"))
                .andExpect(status().isUnauthorized());
    }
}
