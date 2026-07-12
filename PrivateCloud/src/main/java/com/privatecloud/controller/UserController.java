package com.privatecloud.controller;

import com.privatecloud.dto.PasswordUpdateRequest;
import com.privatecloud.dto.ResultResponse;
import com.privatecloud.dto.UserCreateRequest;
import com.privatecloud.dto.UserResponse;
import com.privatecloud.dto.UserUpdateRequest;
import com.privatecloud.security.AuthContext;
import com.privatecloud.security.AuthSession;
import com.privatecloud.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public ResultResponse<?> listUsers() {
        AuthSession session = AuthContext.get();
        if (session == null) {
            return ResultResponse.fail("unauthorized");
        }
        List<UserResponse> users = userService.listUsersForSession(session);
        return ResultResponse.success(users);
    }

    @PostMapping
    public ResultResponse<?> create(@Valid @RequestBody UserCreateRequest request) {
        if (!isAdmin()) {
            return ResultResponse.fail("forbidden");
        }
        return ResultResponse.success(userService.createUser(request));
    }

    @PutMapping("/{id}")
    public ResultResponse<?> update(@PathVariable Long id, @RequestBody UserUpdateRequest request) {
        if (!isAdmin()) {
            return ResultResponse.fail("forbidden");
        }
        return ResultResponse.success(userService.updateUser(id, request));
    }

    @PutMapping("/{id}/password")
    public ResultResponse<?> updatePassword(@PathVariable Long id, @Valid @RequestBody PasswordUpdateRequest request) {
        if (!isAdmin() && !isSelf(id)) {
            return ResultResponse.fail("forbidden");
        }
        userService.updatePassword(id, request.getPassword());
        return ResultResponse.success(true);
    }

    @DeleteMapping("/{id}")
    public ResultResponse<?> delete(@PathVariable Long id) {
        if (!isAdmin()) {
            return ResultResponse.fail("forbidden");
        }
        userService.deleteUser(id);
        return ResultResponse.success(true);
    }

    private boolean isAdmin() {
        AuthSession session = AuthContext.get();
        if (session == null) {
            return false;
        }
        return session.getRoles().stream().anyMatch(role -> "ADMIN".equalsIgnoreCase(role));
    }

    private boolean isSelf(Long userId) {
        AuthSession session = AuthContext.get();
        if (session == null || userId == null) {
            return false;
        }
        return userId.equals(session.getUserId());
    }
}
