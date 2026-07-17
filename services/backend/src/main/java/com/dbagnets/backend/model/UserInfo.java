package com.dbagnets.backend.model;

public record UserInfo(
        String email,
        String name,
        String pictureUrl
) {}