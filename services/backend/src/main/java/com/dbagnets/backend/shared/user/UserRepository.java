package com.dbagnets.backend.shared.user;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dbagnets.backend.shared.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByExternalId(String externalId);
}
