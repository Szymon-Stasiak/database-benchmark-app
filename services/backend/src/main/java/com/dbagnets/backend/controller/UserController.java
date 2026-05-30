package com.dbagnets.backend.controller;

import com.dbagnets.backend.entity.User;
import com.dbagnets.backend.model.UserInfo;
import com.dbagnets.backend.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class UserController {

    @GetMapping("/user")
    public UserInfo getCurrentUser(@CurrentUser User user) {
        return new UserInfo(user.getEmail(), user.getName(), user.getPictureUrl());
    }
}