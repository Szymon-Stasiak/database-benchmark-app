package com.dbagnets.backend.shared.user;

public record UserInfo(
        String email,
        String name,
        String pictureUrl
) {}