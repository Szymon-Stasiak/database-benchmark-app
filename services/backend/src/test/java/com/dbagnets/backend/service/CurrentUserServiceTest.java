package com.dbagnets.backend.service;

import com.dbagnets.backend.entity.User;
import com.dbagnets.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CurrentUserServiceTest {

    @Mock
    UserRepository userRepository;

    @InjectMocks
    CurrentUserService currentUserService;

    @Test
    void createsUserOnFirstSight() {
        Jwt jwt = jwtFor("sub-new", "alice@example.com", "Alice", "https://example.com/a.png");
        when(userRepository.findByExternalId("sub-new")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = currentUserService.resolve(jwt);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User created = captor.getValue();
        assertThat(created.getExternalId()).isEqualTo("sub-new");
        assertThat(created.getEmail()).isEqualTo("alice@example.com");
        assertThat(created.getName()).isEqualTo("Alice");
        assertThat(created.getPicture()).isEqualTo("https://example.com/a.png");
        assertThat(result).isSameAs(created);
    }

    @Test
    void refreshesProfileWhenJwtClaimsChanged() {
        User existing = User.createFromJwtClaims("sub-1", "old@example.com", "Old Name", "old-pic");
        when(userRepository.findByExternalId("sub-1")).thenReturn(Optional.of(existing));

        Jwt jwt = jwtFor("sub-1", "new@example.com", "New Name", "new-pic");
        User result = currentUserService.resolve(jwt);

        assertThat(result).isSameAs(existing);
        assertThat(result.getEmail()).isEqualTo("new@example.com");
        assertThat(result.getName()).isEqualTo("New Name");
        assertThat(result.getPicture()).isEqualTo("new-pic");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void returnsExistingUserWithoutCreatingDuplicate() {
        User existing = User.createFromJwtClaims("sub-1", "alice@example.com", "Alice", "pic");
        when(userRepository.findByExternalId("sub-1")).thenReturn(Optional.of(existing));

        Jwt jwt = jwtFor("sub-1", "alice@example.com", "Alice", "pic");
        User result = currentUserService.resolve(jwt);

        assertThat(result).isSameAs(existing);
        verify(userRepository, never()).save(any(User.class));
    }

    private static Jwt jwtFor(String subject, String email, String name, String picture) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(subject)
                .claim("email", email)
                .claim("name", name)
                .claim("picture", picture)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }
}
