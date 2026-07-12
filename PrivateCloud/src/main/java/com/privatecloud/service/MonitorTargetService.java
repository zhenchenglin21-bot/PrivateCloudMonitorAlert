package com.privatecloud.service;

import com.privatecloud.dto.MonitorTargetChangeView;
import com.privatecloud.dto.MonitorTargetRuntimeView;
import com.privatecloud.dto.MonitorTargetView;
import com.privatecloud.dto.TopologyNodeDTO;
import com.privatecloud.entity.MonitorTarget;
import com.privatecloud.entity.MonitorTargetChange;
import com.privatecloud.repository.MonitorTargetChangeRepository;
import com.privatecloud.repository.MonitorTargetRepository;
import com.privatecloud.security.AuthSession;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class MonitorTargetService {

    private final MonitorTargetRepository targetRepository;
    private final MonitorTargetChangeRepository changeRepository;
    private final HostService hostService;
    private final AccessControlService accessControlService;

    public MonitorTargetService(MonitorTargetRepository targetRepository,
                                MonitorTargetChangeRepository changeRepository,
                                HostService hostService,
                                AccessControlService accessControlService) {
        this.targetRepository = targetRepository;
        this.changeRepository = changeRepository;
        this.hostService = hostService;
        this.accessControlService = accessControlService;
    }

    public List<MonitorTargetView> listTargets(String start, String end, AuthSession session) {
        List<TopologyNodeDTO> nodes = hostService.getTopology(start, end);
        if (!accessControlService.isAdmin()) {
            nodes = nodes.stream().filter(this::canAccessNode).toList();
        }
        Map<String, MonitorTarget> stored = targetRepository.findAll()
                .stream()
                .collect(Collectors.toMap(MonitorTarget::getHost, item -> item));
        return nodes.stream()
                .map(node -> {
                    MonitorTarget target = stored.get(node.getHost());
                    boolean enabled = target == null || target.isEnabled();
                    return new MonitorTargetView(
                            node.getHost(),
                            normalizeRole(node.getRole()),
                            enabled,
                            target == null ? null : target.getUpdatedAt()
                    );
                })
                .sorted(Comparator.comparing(MonitorTargetView::getRole).thenComparing(MonitorTargetView::getHost))
                .collect(Collectors.toList());
    }

    public List<MonitorTargetRuntimeView> listRuntimeTargets(String start, String end) {
        List<TopologyNodeDTO> nodes = hostService.getTopology(start, end);
        Map<String, MonitorTarget> stored = targetRepository.findAll()
                .stream()
                .collect(Collectors.toMap(MonitorTarget::getHost, item -> item, (a, b) -> a));
        return nodes.stream()
                .map(node -> {
                    MonitorTarget target = stored.get(node.getHost());
                    boolean enabled = target == null || target.isEnabled();
                    return new MonitorTargetRuntimeView(
                            node.getHost(),
                            normalizeRole(node.getRole()),
                            node.getParentHost(),
                            node.getQueryHost(),
                            enabled
                    );
                })
                .filter(MonitorTargetRuntimeView::isEnabled)
                .sorted(Comparator.comparing(MonitorTargetRuntimeView::getRole).thenComparing(MonitorTargetRuntimeView::getHost))
                .collect(Collectors.toList());
    }

    public void toggleTarget(String host, String role, boolean enabled, AuthSession session) {
        MonitorTarget target = targetRepository.findByHost(host)
                .orElseGet(() -> new MonitorTarget(host, normalizeRole(role), enabled));
        target.setRole(normalizeRole(role));
        target.setEnabled(enabled);
        targetRepository.save(target);

        String username = session == null ? "unknown" : session.getUsername();
        changeRepository.save(new MonitorTargetChange(host, target.getRole(), enabled, username));
    }

    public List<MonitorTargetChangeView> listChanges(String host, String role, AuthSession session) {
        String filterHost = host == null ? null : host.trim();
        String filterRole = role == null ? null : normalizeRole(role);
        if (accessControlService.isAdmin()) {
            return queryChangesForAdmin(filterHost, filterRole);
        }

        if (accessControlService.allowedHosts().isEmpty()) {
            return List.of();
        }

        List<TopologyNodeDTO> visibleNodes = visibleNodesForCurrentUser();
        if (visibleNodes.isEmpty()) {
            return List.of();
        }

        Set<String> visibleHosts = visibleNodes.stream()
                .map(TopologyNodeDTO::getHost)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toSet());
        Set<String> visibleKeys = visibleNodes.stream()
                .map(node -> toKey(node.getHost(), node.getRole()))
                .collect(Collectors.toSet());

        if (filterHost != null && !filterHost.isBlank()) {
            return changeRepository.findByHostOrderByChangedAtDesc(filterHost)
                    .stream()
                    .filter(change -> visibleKeys.contains(toKey(change.getHost(), change.getRole())))
                    .filter(change -> filterRole == null || filterRole.equals(normalizeRole(change.getRole())))
                    .map(this::toChangeView)
                    .collect(Collectors.toList());
        }

        return changeRepository.findByHostInOrderByChangedAtDesc(visibleHosts.stream().toList())
                .stream()
                .filter(change -> visibleKeys.contains(toKey(change.getHost(), change.getRole())))
                .filter(change -> filterRole == null || filterRole.equals(normalizeRole(change.getRole())))
                .map(this::toChangeView)
                .collect(Collectors.toList());
    }

    private List<MonitorTargetChangeView> queryChangesForAdmin(String host, String role) {
        if (host != null && !host.isBlank()) {
            return changeRepository.findByHostOrderByChangedAtDesc(host)
                    .stream()
                    .filter(change -> role == null || role.equals(normalizeRole(change.getRole())))
                    .map(this::toChangeView)
                    .collect(Collectors.toList());
        }
        return changeRepository.findAll()
                .stream()
                .sorted(Comparator.comparing(MonitorTargetChange::getChangedAt).reversed())
                .filter(change -> role == null || role.equals(normalizeRole(change.getRole())))
                .map(this::toChangeView)
                .collect(Collectors.toList());
    }

    private List<TopologyNodeDTO> visibleNodesForCurrentUser() {
        List<TopologyNodeDTO> nodes = hostService.getTopology("-7d", "now()");
        return nodes.stream()
                .filter(this::canAccessNode)
                .collect(Collectors.toList());
    }

    private String toKey(String host, String role) {
        String hostPart = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        String rolePart = normalizeRole(role);
        return rolePart + "|" + hostPart;
    }

    private MonitorTargetChangeView toChangeView(MonitorTargetChange change) {
        return new MonitorTargetChangeView(
                change.getHost(),
                change.getRole(),
                change.isEnabled(),
                change.getChangedBy(),
                change.getChangedAt()
        );
    }

    private boolean canAccessNode(TopologyNodeDTO node) {
        if (node == null) {
            return false;
        }
        if (accessControlService.isHostAllowed(node.getHost())) {
            return true;
        }
        return node.getQueryHost() != null && accessControlService.isHostAllowed(node.getQueryHost());
    }

    private String normalizeRole(String role) {
        if (role == null) {
            return "host";
        }
        String r = role.trim().toLowerCase(Locale.ROOT);
        if (r.equals("server")) return "host";
        if (r.equals("host") || r.equals("vm") || r.equals("container")) return r;
        return "host";
    }
}
