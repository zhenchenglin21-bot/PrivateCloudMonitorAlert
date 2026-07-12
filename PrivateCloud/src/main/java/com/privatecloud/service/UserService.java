package com.privatecloud.service;

import com.privatecloud.dto.UserCreateRequest;
import com.privatecloud.dto.UserResponse;
import com.privatecloud.dto.UserUpdateRequest;
import com.privatecloud.entity.Role;
import com.privatecloud.entity.User;
import com.privatecloud.entity.UserServer;
import com.privatecloud.repository.RoleRepository;
import com.privatecloud.repository.UserRepository;
import com.privatecloud.security.AuthSession;
import jakarta.transaction.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;

    public UserService(
            UserRepository userRepository,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder,
            AuthService authService
    ) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.authService = authService;
    }

    public List<UserResponse> listUsers() {
        return userRepository.findAll().stream().map(this::toResponse).toList();
    }

    public List<UserResponse> listUsersForSession(AuthSession session) {
        if (session == null) {
            return List.of();
        }
        boolean admin = session.getRoles().stream().anyMatch(role -> "ADMIN".equalsIgnoreCase(role));
        if (admin) {
            return listUsers();
        }
        return userRepository.findByUsername(session.getUsername())
                .map(this::toResponse)
                .stream()
                .toList();
    }

    @Transactional
    public UserResponse createUser(UserCreateRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new IllegalArgumentException("用户名已存在");
        }
        User user = new User(request.getUsername(), passwordEncoder.encode(request.getPassword()));
        applyRoles(user, request.getRoles());
        applyServers(user, request.getServerHosts());
        User saved = userRepository.save(user);
        return toResponse(saved);
    }

    @Transactional
    public UserResponse updateUser(Long id, UserUpdateRequest request) {
        User user = userRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (request.getEnabled() != null) {
            user.setEnabled(request.getEnabled());
        }
        if (request.getRoles() != null) {
            applyRoles(user, request.getRoles());
        }
        if (request.getServerHosts() != null) {
            applyServers(user, request.getServerHosts());
        }
        User saved = userRepository.save(user);
        authService.refreshSessionsForUser(saved.getId());
        return toResponse(saved);
    }

    @Transactional
    public void updatePassword(Long id, String rawPassword) {
        User user = userRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        userRepository.save(user);
    }

    @Transactional
    public void deleteUser(Long id) {
        authService.revokeSessionsForUser(id);
        userRepository.deleteById(id);
    }

    private void applyRoles(User user, List<String> roleNames) {
        List<String> normalized = normalizeRoles(roleNames);
        Set<Role> roles = new HashSet<>();
        for (String roleName : normalized) {
            Role role = roleRepository.findByName(roleName).orElseGet(() -> roleRepository.save(new Role(roleName)));
            roles.add(role);
        }
        user.setRoles(roles);
        if (roles.stream().anyMatch(role -> "ADMIN".equals(role.getName()))) {
            // 管理员默认拥有全部服务器权限，无需绑定具体列表
            user.getServers().clear();
        }
    }

    private void applyServers(User user, List<String> serverHosts) {
        if (user.getRoles().stream().anyMatch(role -> "ADMIN".equals(role.getName()))) {
            user.getServers().clear();
            return;
        }
        user.getServers().clear();
        if (serverHosts == null) {
            return;
        }
        for (String host : serverHosts) {
            if (host == null || host.isBlank()) {
                continue;
            }
            user.getServers().add(new UserServer(user, host.trim()));
        }
    }

    private List<String> normalizeRoles(List<String> roleNames) {
        if (roleNames == null || roleNames.isEmpty()) {
            return List.of("USER");
        }
        List<String> result = new ArrayList<>();
        for (String roleName : roleNames) {
            if (roleName == null || roleName.isBlank()) {
                continue;
            }
            result.add(roleName.trim().toUpperCase(Locale.ROOT));
        }
        return result.isEmpty() ? List.of("USER") : result;
    }

    private UserResponse toResponse(User user) {
        List<String> roles = user.getRoles().stream().map(Role::getName).sorted().collect(Collectors.toList());
        List<String> servers = user.getServers().stream().map(UserServer::getServerHost).sorted().collect(Collectors.toList());
        return new UserResponse(user.getId(), user.getUsername(), user.isEnabled(), user.getCreatedAt(), roles, servers);
    }
}
