package com.privatecloud.service;

import com.privatecloud.entity.Role;
import com.privatecloud.entity.User;
import com.privatecloud.repository.UserRepository;
import com.privatecloud.security.AuthSession;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class AuthService {

    private static final long SESSION_SYNC_INTERVAL_MS = 5_000L;

    private final UserRepository userRepository;
    private final Map<String, AuthSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Long> sessionSyncedAt = new ConcurrentHashMap<>();

    public AuthService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public AuthSession createSession(User user, String token) {
        AuthSession session = buildSession(user);
        sessions.put(token, session);
        sessionSyncedAt.put(token, System.currentTimeMillis());
        return session;
    }

    public Optional<AuthSession> getSession(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        AuthSession current = sessions.get(token);
        if (current == null) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        long lastSyncedAt = sessionSyncedAt.getOrDefault(token, 0L);
        if (now - lastSyncedAt < SESSION_SYNC_INTERVAL_MS) {
            return Optional.of(current);
        }
        Optional<User> latestUser = userRepository.findById(current.getUserId());
        if (latestUser.isEmpty() || !latestUser.get().isEnabled()) {
            sessions.remove(token);
            sessionSyncedAt.remove(token);
            return Optional.empty();
        }
        AuthSession refreshed = buildSession(latestUser.get());
        if (!sameSession(current, refreshed)) {
            sessions.put(token, refreshed);
            current = refreshed;
        }
        sessionSyncedAt.put(token, now);
        return Optional.of(current);
    }

    public void removeSession(String token) {
        if (token != null) {
            sessions.remove(token);
            sessionSyncedAt.remove(token);
        }
    }

    public Optional<User> findUserById(Long id) {
        return userRepository.findById(id);
    }

    public void refreshSessionsForUser(Long userId) {
        if (userId == null) {
            return;
        }
        Optional<User> latestUser = userRepository.findById(userId);
        if (latestUser.isEmpty() || !latestUser.get().isEnabled()) {
            revokeSessionsForUser(userId);
            return;
        }
        AuthSession refreshed = buildSession(latestUser.get());
        sessions.forEach((token, session) -> {
            if (userId.equals(session.getUserId())) {
                sessions.put(token, refreshed);
                sessionSyncedAt.put(token, System.currentTimeMillis());
            }
        });
    }

    public void revokeSessionsForUser(Long userId) {
        if (userId == null) {
            return;
        }
        sessions.entrySet().removeIf(entry -> {
            if (userId.equals(entry.getValue().getUserId())) {
                sessionSyncedAt.remove(entry.getKey());
                return true;
            }
            return false;
        });
    }

    private AuthSession buildSession(User user) {
        List<String> roles = user.getRoles().stream()
                .map(Role::getName)
                .filter(Objects::nonNull)
                .sorted(String::compareToIgnoreCase)
                .collect(Collectors.toList());
        boolean admin = roles.stream().anyMatch(r -> "ADMIN".equalsIgnoreCase(r));
        List<String> hosts = admin
                ? List.of()
                : user.getServers().stream()
                        .map(s -> s.getServerHost())
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(v -> !v.isBlank())
                        .distinct()
                        .sorted(String::compareToIgnoreCase)
                        .collect(Collectors.toList());
        return new AuthSession(user.getId(), user.getUsername(), roles, hosts);
    }

    private boolean sameSession(AuthSession left, AuthSession right) {
        if (left == null || right == null) {
            return false;
        }
        return Objects.equals(left.getUserId(), right.getUserId())
                && Objects.equals(left.getUsername(), right.getUsername())
                && Objects.equals(left.getRoles(), right.getRoles())
                && Objects.equals(left.getAllowedHosts(), right.getAllowedHosts());
    }
}
