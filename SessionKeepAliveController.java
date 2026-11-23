package za.co.nmas.nmas.controller;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Called periodically from the video consultation room (both doctor and
 * patient views) while a call is in progress. Its only job is to touch the
 * HTTP session so it doesn't expire mid-call — WebSocket activity does not
 * reset a servlet container's session timeout, so a long call with no plain
 * HTTP requests could otherwise let the session quietly expire even while
 * the video/audio connection kept working fine, causing anything saved
 * afterward (e.g. a prescription) to fail with an authentication error.
 */
@RestController
public class SessionKeepAliveController {

    @GetMapping("/api/session/ping")
    public Map<String, Object> ping(Authentication auth) {
        return Map.of("ok", true, "user", auth != null ? auth.getName() : "anonymous");
    }

    /**
     * Lets a page detect when the session behind this browser tab has
     * silently changed identity — this happens because browser tabs share
     * cookies, so logging in as a different account in one tab can switch
     * every other open tab of the same browser to that new identity without
     * any visible warning. Pages poll this and compare against the identity
     * they last knew about (kept in sessionStorage, which IS per-tab) so
     * they can show a clear "you're now viewing this as a different
     * account" message instead of silently rendering the wrong dashboard.
     */
    @GetMapping("/api/session/whoami")
    public Map<String, Object> whoami(Authentication auth) {
        if (auth == null) return Map.of("authenticated", false);
        String role = auth.getAuthorities().stream().findFirst()
                .map(a -> a.getAuthority().replace("ROLE_", "")).orElse("UNKNOWN");
        return Map.of("authenticated", true, "email", auth.getName(), "role", role);
    }
}
