package com.dbagnets.backend.shared.user;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dbagnets.backend.shared.entity.User;
import com.dbagnets.backend.shared.security.CurrentUser;

@RestController
@RequestMapping("/api")
public class UserController {

    @GetMapping("/user")
    public UserInfo getCurrentUser(@CurrentUser User user) {
        return new UserInfo(user.getEmail(), user.getName(), user.getPictureUrl());
    }
}
