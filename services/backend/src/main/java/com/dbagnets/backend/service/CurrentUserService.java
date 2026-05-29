package com.dbagnets.backend.service;

import com.dbagnets.backend.entity.User;
import com.dbagnets.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CurrentUserService {

    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_NAME = "name";
    private static final String CLAIM_PICTURE = "picture";

    private final UserRepository userRepository;

    /** Returns the local {@link User} for the JWT principal, creating one on first sight
     *  (JIT provisioning) and refreshing the profile snapshot whenever the JWT carries new
     *  email/name/picture values. */
    @Transactional
    public User resolve(Jwt jwt) {
        String externalId = jwt.getSubject();
        String email = jwt.getClaimAsString(CLAIM_EMAIL);
        String name = jwt.getClaimAsString(CLAIM_NAME);
        String picture = jwt.getClaimAsString(CLAIM_PICTURE);

        return userRepository.findByExternalId(externalId)
                .map(existing -> {
                    existing.refreshProfile(email, name, picture);
                    return existing;
                })
                .orElseGet(() -> userRepository.save(User.createFromJwtClaims(externalId, email, name, picture)));
    }
}
