package com.privatecloud.service;

import com.privatecloud.dto.TopologyNodeDTO;
import com.privatecloud.security.AuthContext;
import com.privatecloud.security.AuthSession;
import org.springframework.stereotype.Service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AccessControlService {

    private static final long TOPOLOGY_CACHE_TTL_MS = 10_000L;

    private final HostService hostService;

    private volatile List<TopologyNodeDTO> cachedTopology = List.of();
    private volatile long cachedTopologyAt = 0L;

    public AccessControlService(HostService hostService) {
        this.hostService = hostService;
    }

    public boolean isAdmin() {
        AuthSession session = AuthContext.get();
        if (session == null) {
            return false;
        }
        return session.getRoles().stream().anyMatch(role -> "ADMIN".equalsIgnoreCase(role));
    }

    public List<String> allowedHosts() {
        AuthSession session = AuthContext.get();
        if (session == null) {
            return List.of();
        }
        return session.getAllowedHosts();
    }

    public List<String> allowedEntityHosts() {
        List<TopologyNodeDTO> nodes = loadTopologySnapshot();
        if (isAdmin()) {
            return nodes.stream()
                    .flatMap(node -> java.util.stream.Stream.of(node.getHost(), node.getQueryHost()))
                    .filter(value -> value != null && !value.isBlank())
                    .distinct()
                    .collect(Collectors.toList());
        }
        List<String> allowedServers = allowedHosts();
        if (allowedServers == null || allowedServers.isEmpty()) {
            return List.of();
        }
        return expandAllowedEntityHosts(nodes, allowedServers);
    }

    public List<String> expandEntityHostsForServers(List<String> allowedServers) {
        if (allowedServers == null || allowedServers.isEmpty()) {
            return List.of();
        }
        return expandAllowedEntityHosts(loadTopologySnapshot(), allowedServers);
    }

    public boolean isHostAllowed(String host) {
        if (isAdmin()) {
            return true;
        }
        String decoded = normalizeHost(host);
        if (decoded.isBlank()) {
            return false;
        }
        List<String> allowed = allowedHosts();
        if (allowed.stream().anyMatch(h -> h.equalsIgnoreCase(decoded))) {
            return true;
        }
        return isRelatedToAllowedServer(decoded, allowed);
    }

    public String normalizeHost(String host) {
        if (host == null) {
            return "";
        }
        return URLDecoder.decode(host, StandardCharsets.UTF_8);
    }

    private boolean isRelatedToAllowedServer(String hostValue, List<String> allowed) {
        if (allowed == null || allowed.isEmpty()) {
            return false;
        }
        List<TopologyNodeDTO> nodes = loadTopologySnapshot();
        if (nodes.isEmpty()) {
            return false;
        }
        Set<String> authorizedServers = buildAuthorizedServerAliases(nodes, allowed);
        Map<String, String> vmToServer = buildVmToServerMap(nodes);

        for (TopologyNodeDTO node : nodes) {
            if (!matchesNode(node, hostValue)) {
                continue;
            }
            String role = normalizedRole(node.getRole());
            if ("host".equals(role)) {
                return authorizedServers.contains(lower(node.getHost()))
                        || authorizedServers.contains(lower(node.getQueryHost()));
            }
            if ("vm".equals(role)) {
                return authorizedServers.contains(lower(node.getParentHost()));
            }
            if ("container".equals(role)) {
                String parent = node.getParentHost();
                if (authorizedServers.contains(lower(parent))) {
                    return true;
                }
                String server = vmToServer.get(lower(parent));
                return authorizedServers.contains(server);
            }
        }
        return false;
    }

    private List<String> expandAllowedEntityHosts(List<TopologyNodeDTO> nodes, List<String> allowedServers) {
        Set<String> visible = new LinkedHashSet<>();
        allowedServers.forEach(host -> addVisible(visible, host));
        if (nodes == null || nodes.isEmpty()) {
            return visible.stream().toList();
        }

        Set<String> authorizedServers = buildAuthorizedServerAliases(nodes, allowedServers);
        Map<String, String> vmToServer = buildVmToServerMap(nodes);

        for (TopologyNodeDTO node : nodes) {
            String nodeHost = node.getHost();
            if (nodeHost == null || nodeHost.isBlank()) {
                continue;
            }
            String role = normalizedRole(node.getRole());
            if ("host".equals(role)) {
                if (authorizedServers.contains(lower(nodeHost)) || authorizedServers.contains(lower(node.getQueryHost()))) {
                    addVisible(visible, node.getHost());
                    addVisible(visible, node.getQueryHost());
                }
                continue;
            }
            if ("vm".equals(role)) {
                if (authorizedServers.contains(lower(node.getParentHost()))) {
                    addVisible(visible, node.getHost());
                    addVisible(visible, node.getQueryHost());
                }
                continue;
            }
            if ("container".equals(role)) {
                String parent = node.getParentHost();
                if (authorizedServers.contains(lower(parent))) {
                    addVisible(visible, node.getHost());
                    addVisible(visible, node.getQueryHost());
                    continue;
                }
                String parentServer = vmToServer.get(lower(parent));
                if (authorizedServers.contains(parentServer)) {
                    addVisible(visible, node.getHost());
                    addVisible(visible, node.getQueryHost());
                }
            }
        }
        return visible.stream().toList();
    }

    private String normalizedRole(String role) {
        if (role == null) {
            return "";
        }
        String r = role.trim().toLowerCase(Locale.ROOT);
        if ("server".equals(r)) {
            return "host";
        }
        return r;
    }

    private Set<String> buildAuthorizedServerAliases(List<TopologyNodeDTO> nodes, List<String> allowedServers) {
        Set<String> authorized = new HashSet<>();
        for (TopologyNodeDTO node : nodes) {
            String role = normalizedRole(node.getRole());
            if (!"host".equals(role)) {
                continue;
            }
            boolean matched = allowedServers.stream().anyMatch(item ->
                    equalsIgnoreCase(item, node.getHost()) || equalsIgnoreCase(item, node.getQueryHost()));
            if (!matched) {
                continue;
            }
            addAuthorized(authorized, node.getHost());
            addAuthorized(authorized, node.getQueryHost());
        }
        if (authorized.isEmpty()) {
            allowedServers.forEach(item -> addAuthorized(authorized, item));
        }
        return authorized;
    }

    private Map<String, String> buildVmToServerMap(List<TopologyNodeDTO> nodes) {
        Map<String, String> vmToServer = new HashMap<>();
        for (TopologyNodeDTO node : nodes) {
            if (!"vm".equals(normalizedRole(node.getRole()))) {
                continue;
            }
            String parent = lower(node.getParentHost());
            if (parent == null || parent.isBlank()) {
                continue;
            }
            String vmHost = lower(node.getHost());
            String vmQueryHost = lower(node.getQueryHost());
            if (vmHost != null && !vmHost.isBlank()) {
                vmToServer.put(vmHost, parent);
            }
            if (vmQueryHost != null && !vmQueryHost.isBlank()) {
                vmToServer.put(vmQueryHost, parent);
            }
        }
        return vmToServer;
    }

    private boolean matchesNode(TopologyNodeDTO node, String hostValue) {
        String target = hostValue.toLowerCase(Locale.ROOT);
        return equalsIgnoreCase(target, node.getHost())
                || equalsIgnoreCase(target, node.getQueryHost());
    }

    private boolean equalsIgnoreCase(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.equalsIgnoreCase(right);
    }

    private String lower(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private void addAuthorized(Set<String> target, String value) {
        String normalized = lower(value);
        if (normalized != null && !normalized.isBlank()) {
            target.add(normalized);
        }
    }

    private void addVisible(Set<String> target, String value) {
        if (value != null && !value.isBlank()) {
            target.add(value.trim());
        }
    }

    private List<TopologyNodeDTO> loadTopologySnapshot() {
        long now = System.currentTimeMillis();
        List<TopologyNodeDTO> snapshot = cachedTopology;
        if (!snapshot.isEmpty() && now - cachedTopologyAt <= TOPOLOGY_CACHE_TTL_MS) {
            return snapshot;
        }
        synchronized (this) {
            if (!cachedTopology.isEmpty() && now - cachedTopologyAt <= TOPOLOGY_CACHE_TTL_MS) {
                return cachedTopology;
            }
            List<TopologyNodeDTO> latest = hostService.getTopology("-7d", "now()");
            cachedTopology = latest == null ? List.of() : latest;
            cachedTopologyAt = now;
            return cachedTopology;
        }
    }
}
