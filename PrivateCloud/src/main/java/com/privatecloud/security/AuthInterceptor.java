package com.privatecloud.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatecloud.dto.ResultResponse;
import com.privatecloud.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final AuthService authService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuthInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        if (path.startsWith("/api/health")
                || path.equals("/api/auth/login")
                || path.equals("/api/alert-history/ingest")
                || path.equals("/api/alert-rules/runtime")
                || path.equals("/api/monitor-targets/runtime")) {
            return true;
        }
        if (path.equals("/api/alert-history/feedback-summary")) {
            String apiKey = request.getHeader("X-API-Key");
            if (apiKey != null && !apiKey.isBlank()) {
                return true;
            }
        }
        String header = request.getHeader("Authorization");
        String token = null;
        if (header != null && !header.isBlank()) {
            token = header.startsWith("Bearer ") ? header.substring(7) : header;
        }
        if (token == null || token.isBlank()) {
            return reject(response);
        }
        return authService.getSession(token)
                .map(session -> {
                    AuthContext.set(session);
                    return true;
                })
                .orElseGet(() -> {
                    try {
                        return reject(response);
                    } catch (IOException e) {
                        return false;
                    }
                });
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AuthContext.clear();
    }

    private boolean reject(HttpServletResponse response) throws IOException {
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        ResultResponse<?> payload = ResultResponse.fail("unauthorized");
        response.getWriter().write(objectMapper.writeValueAsString(payload));
        return false;
    }
}
