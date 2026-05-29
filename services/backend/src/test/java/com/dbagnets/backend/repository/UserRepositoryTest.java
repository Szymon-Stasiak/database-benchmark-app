package com.dbagnets.backend.repository;

import com.dbagnets.backend.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserRepositoryTest {

    @Autowired
    UserRepository userRepository;

    @Test
    void findByExternalIdReturnsPersistedUser() {
        User saved = userRepository.save(User.createFromJwtClaims(
                "sub-123", "alice@example.com", "Alice", "https://example.com/a.png"
        ));

        Optional<User> found = userRepository.findByExternalId("sub-123");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
        assertThat(found.get().getEmail()).isEqualTo("alice@example.com");
        assertThat(found.get().getName()).isEqualTo("Alice");
    }

    @Test
    void findByExternalIdReturnsEmptyWhenAbsent() {
        assertThat(userRepository.findByExternalId("unknown-sub")).isEmpty();
    }
}
