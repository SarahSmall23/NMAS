package za.co.nmas.nmas.controller;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import za.co.nmas.nmas.service.AidenChatService;
import za.co.nmas.nmas.service.AuditLogService;

import java.util.Map;

/**
 * AidenChatController (formerly AiProxyController)
 *
 * Serves the AIDEN AI Symptom Checker chatbot for the NMAS patient
 * dashboard. AIDEN now runs natively inside this application (see
 * AidenChatService) instead of being proxied to a separate, external
 * GlassFish deployment. That external hop is what caused the chatbot to
 * constantly report itself as "unavailable" — it was trying to reach a
 * second server on the same port NMAS itself uses, so the connection
 * always failed.
 *
 * Frontend endpoints (unchanged, so no template changes were required):
 *   POST /api/ai/chat/start    { sessionId, language? }
 *   POST /api/ai/chat/message  { sessionId, message, language? }
 *   POST /api/ai/chat/reset    { sessionId }
 *   POST /api/ai/emergency-location   — logs a captured GPS fix during an emergency alert
 *
 * The logged-in patient's identity (from Authentication) is used only to
 * pull their own NMAS consultation history into AIDEN's context, so it can
 * recognise a documented pattern instead of treating every visit as new.
 * language is a free-text language name the widget's language picker sends
 * ("English", "isiZulu", "Afrikaans", ...); the model itself handles the
 * translation, so no per-language content needs to be maintained here.
 */
@RestController
@RequestMapping("/api/ai")
public class AiProxyController {

    private final AidenChatService aidenChatService;
    private final AuditLogService auditLogService;

    public AiProxyController(AidenChatService aidenChatService, AuditLogService auditLogService) {
        this.aidenChatService = aidenChatService;
        this.auditLogService = auditLogService;
    }

    @PostMapping("/chat/start")
    public Map<String, Object> chatStart(@RequestBody Map<String, Object> body, Authentication auth) {
        String sessionId = String.valueOf(body.get("sessionId"));
        String language = body.get("language") != null ? String.valueOf(body.get("language")) : null;
        boolean historyConsent = Boolean.TRUE.equals(body.get("historyConsent"));
        String patientEmail = auth != null ? auth.getName() : null;
        return aidenChatService.start(sessionId, patientEmail, language, historyConsent);
    }

    @PostMapping("/chat/message")
    public Map<String, Object> chatMessage(@RequestBody Map<String, Object> body, Authentication auth) {
        String sessionId = String.valueOf(body.get("sessionId"));
        String message = String.valueOf(body.get("message"));
        String language = body.get("language") != null ? String.valueOf(body.get("language")) : null;
        Double lat = toDouble(body.get("lat"));
        Double lng = toDouble(body.get("lng"));
        return aidenChatService.message(sessionId, message, language, lat, lng);
    }

    @PostMapping("/chat/reset")
    public Map<String, Object> chatReset(@RequestBody Map<String, Object> body) {
        String sessionId = String.valueOf(body.get("sessionId"));
        aidenChatService.reset(sessionId);
        return Map.of("ok", true);
    }

    /**
     * Called by the floating AIDEN widget the moment it detects an emergency
     * and the browser successfully retrieves the patient's GPS coordinates.
     * This creates a record in the audit log (tied to whoever is logged in)
     * so facility staff have a trace of when and where an emergency alert
     * fired, in case follow-up is needed.
     */
    @PostMapping("/emergency-location")
    public Map<String, Object> emergencyLocation(@RequestBody Map<String, Object> body) {
        Object lat = body.get("lat");
        Object lng = body.get("lng");
        String sessionId = String.valueOf(body.get("sessionId"));
        String details = "AIDEN emergency alert — captured location lat=" + lat + ", lng=" + lng
                + " (session " + sessionId + ")";
        auditLogService.log("EMERGENCY_LOCATION_CAPTURED", "AidenChat", null, details);
        Double la = toDouble(lat);
        Double lo = toDouble(lng);
        if (la == null || lo == null) return Map.of("ok", true);
        return aidenChatService.locationShared(sessionId, la, lo);
    }

    private static Double toDouble(Object o) {
        if (o == null) return null;
        try { return Double.valueOf(String.valueOf(o)); } catch (Exception e) { return null; }
    }

    /**
     * Diagnostic endpoint — visit this directly (while logged in) to see
     * exactly why AIDEN might be falling back: wrong/expired API key,
     * deprecated model, rate limiting, network egress blocked, etc. Shows
     * the real HTTP status and error body from Groq instead of the generic
     * in-chat fallback message.
     */
    @GetMapping("/diagnose")
    public Map<String, Object> diagnose() {
        return aidenChatService.diagnosePing();
    }
}
