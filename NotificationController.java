package za.co.nmas.nmas.controller;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import za.co.nmas.nmas.entity.Notification;
import za.co.nmas.nmas.service.NotificationService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public List<Map<String, Object>> list(Authentication auth) {
        return notificationService.findRecent(auth.getName()).stream()
                .map(n -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", n.getId());
                    m.put("type", n.getType());
                    m.put("message", n.getMessage());
                    m.put("link", n.getLink());
                    m.put("read", n.isRead());
                    m.put("createdAt", n.getCreatedAt().toString());
                    return m;
                }).toList();
    }

    @GetMapping("/unread-count")
    public Map<String, Object> unreadCount(Authentication auth) {
        return Map.of("count", notificationService.countUnread(auth.getName()));
    }

    @PostMapping("/{id}/read")
    public Map<String, Object> markRead(@PathVariable Long id) {
        notificationService.markRead(id);
        return Map.of("ok", true);
    }

    @PostMapping("/read-all")
    public Map<String, Object> markAllRead(Authentication auth) {
        notificationService.markAllRead(auth.getName());
        return Map.of("ok", true);
    }
}
