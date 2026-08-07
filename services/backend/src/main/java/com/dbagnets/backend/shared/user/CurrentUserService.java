package com.dbagnets.backend.shared.user;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dbagnets.backend.shared.entity.User;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CurrentUserService {

    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_NAME = "name";
    private static final String CLAIM_PICTURE = "pictureUrl";

    private final UserRepository userRepository;

    @Transactional
    public User resolve(Jwt jwt) {
        String externalId = jwt.getSubject();
        String email = jwt.getClaimAsString(CLAIM_EMAIL);
        String name = jwt.getClaimAsString(CLAIM_NAME);
        String picture = jwt.getClaimAsString(CLAIM_PICTURE);

        return userRepository
                .findByExternalId(externalId)
                .map(
                        existing -> {
                            existing.refreshProfile(email, name, picture);
                            return existing;
                        })
                .orElseGet(
                        () ->
                                userRepository.save(
                                        User.createFromJwtClaims(
                                                externalId, email, name, picture)));
    }
}
