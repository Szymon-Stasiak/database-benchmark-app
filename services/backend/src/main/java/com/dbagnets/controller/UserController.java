package com.dbagnets.backend.controller;

import com.dbagnets.backend.model.UserInfo;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class UserController {

    @GetMapping("/user")
    public UserInfo getCurrentUser(@AuthenticationPrincipal Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        String name = jwt.getClaimAsString("name");
        String picture = jwt.getClaimAsString("picture");

        return new UserInfo(email, name, picture);
    }
}
