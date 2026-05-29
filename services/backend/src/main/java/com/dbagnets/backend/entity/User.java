package com.dbagnets.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_id", nullable = false, unique = true)
    private String externalId;

    @Column(nullable = false)
    private String email;

    @Column
    private String name;

    @Column
    private String picture;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_login_at", nullable = false)
    private Instant lastLoginAt;

    public static User createFromJwtClaims(String externalId, String email, String name, String picture) {
        User user = new User();
        Instant now = Instant.now();
        user.externalId = externalId;
        user.email = email;
        user.name = name;
        user.picture = picture;
        user.createdAt = now;
        user.lastLoginAt = now;
        return user;
    }

    /** Refreshes mutable profile fields from the latest JWT and stamps {@code lastLoginAt}.
     *  Returns {@code true} when any profile field actually changed, so callers can decide
     *  whether to skip a write. */
    public boolean refreshProfile(String email, String name, String picture) {
        boolean changed = !equalsNullable(this.email, email)
                || !equalsNullable(this.name, name)
                || !equalsNullable(this.picture, picture);
        this.email = email;
        this.name = name;
        this.picture = picture;
        this.lastLoginAt = Instant.now();
        return changed;
    }

    private static boolean equalsNullable(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }
}
