package com.privatecloud.controller;

import com.privatecloud.dto.AuthMeResponse;
import com.privatecloud.dto.AuthRequest;
import com.privatecloud.dto.AuthResponse;
import com.privatecloud.dto.ResultResponse;
import com.privatecloud.entity.User;
import com.privatecloud.repository.UserRepository;
import com.privatecloud.security.AuthContext;
import com.privatecloud.security.AuthSession;
import com.privatecloud.service.AuthService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder, AuthService authService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResultResponse<AuthResponse> login(@RequestBody AuthRequest request) {
        if (request == null
                || request.getUsername() == null
                || request.getPassword() == null) {
            return ResultResponse.fail("用户名或密码不能为空");
        }

        User user = userRepository.findByUsername(request.getUsername()).orElse(null);
        if (user == null || !user.isEnabled()) {
            return ResultResponse.fail("用户名或密码错误");
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            return ResultResponse.fail("用户名或密码错误");
        }

        String token = UUID.randomUUID().toString();
        authService.createSession(user, token);
        AuthResponse response = new AuthResponse(user.getUsername(), token);
        return ResultResponse.success(response);
    }

    @PostMapping("/logout")
    public ResultResponse<?> logout(@RequestHeader(value = "Authorization", required = false) String header) {
        String token = header == null ? null : (header.startsWith("Bearer ") ? header.substring(7) : header);
        authService.removeSession(token);
        return ResultResponse.success(true);
    }

    @GetMapping("/me")
    public ResultResponse<?> me() {
        AuthSession session = AuthContext.get();
        if (session == null) {
            return ResultResponse.fail("unauthorized");
        }
        boolean admin = session.getRoles().stream().anyMatch(r -> "ADMIN".equalsIgnoreCase(r));
        List<String> servers = admin ? List.of() : session.getAllowedHosts();
        return ResultResponse.success(new AuthMeResponse(session.getUsername(), session.getRoles(), servers, admin));
    }
}
