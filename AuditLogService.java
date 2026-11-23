package za.co.nmas.nmas.service;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import za.co.nmas.nmas.entity.AuditLog;
import za.co.nmas.nmas.repository.AuditLogRepository;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    public AuditLogService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    public void log(String action, String entityName, Long entityId, String details) {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            String username = (auth != null) ? auth.getName() : "system";
            String role = (auth != null && !auth.getAuthorities().isEmpty())
                    ? auth.getAuthorities().iterator().next().getAuthority()
                    : "UNKNOWN";

            AuditLog entry = new AuditLog();
            entry.setUsername(username);
            entry.setRole(role);
            entry.setAction(action);
            entry.setEntityName(entityName);
            entry.setEntityId(entityId);
            entry.setDetails(details);
            auditLogRepository.save(entry);
        } catch (Exception ignored) {}
    }

    public void logAs(String username, String role, String action, String entityName, Long entityId, String details) {
        try {
            AuditLog entry = new AuditLog();
            entry.setUsername(username);
            entry.setRole(role);
            entry.setAction(action);
            entry.setEntityName(entityName);
            entry.setEntityId(entityId);
            entry.setDetails(details);
            auditLogRepository.save(entry);
        } catch (Exception ignored) {}
    }

    public List<AuditLog> findAll() {
        return auditLogRepository.findAllByOrderByTimestampDesc();
    }

    public List<AuditLog> findFiltered(String action, String role, String search) {
        String a = (action == null || action.isBlank()) ? null : action;
        String r = (role == null || role.isBlank()) ? null : role;
        String s = (search == null || search.isBlank()) ? null : search;
        String searchPat = (s != null) ? "%" + s.toLowerCase() + "%" : "%";
        return auditLogRepository.findFiltered(a, r, s, searchPat);
    }

    public long countToday() {
        return auditLogRepository.countByTimestampAfter(LocalDateTime.now().toLocalDate().atStartOfDay());
    }

    public long countTotal() {
        return auditLogRepository.count();
    }

    public List<String> distinctActions() {
        return auditLogRepository.findDistinctActions();
    }

    public List<String> distinctRoles() {
        return auditLogRepository.findDistinctRoles();
    }

    // ---- Facility-scoped views: only activity by people registered at one facility ----

    public List<AuditLog> findFilteredForUsers(java.util.Set<String> usernames, String action, String role, String search) {
        return findFiltered(action, role, search).stream()
                .filter(l -> l.getUsername() != null && usernames.contains(l.getUsername().toLowerCase()))
                .toList();
    }

    public List<AuditLog> findAllForUsers(java.util.Set<String> usernames) {
        return findAll().stream()
                .filter(l -> l.getUsername() != null && usernames.contains(l.getUsername().toLowerCase()))
                .toList();
    }
}
