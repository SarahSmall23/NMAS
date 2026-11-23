package za.co.nmas.nmas.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * When NMAS is started on a developer machine, open the public home page ("/")
 * in the default browser so it is the first thing that appears.
 *
 * Spring Boot runs "headless" by default, so java.awt.Desktop cannot be used here;
 * the operating system's own "open this URL" command is used instead.
 *
 * It does nothing on hosted servers (for example Render) and can be switched off
 * with nmas.open-home-on-start=false.
 */
@Component
public class OpenHomePageOnStart {

    @Value("${nmas.open-home-on-start:true}")
    private boolean enabled;

    @Value("${server.port:8080}")
    private int port;

    @EventListener(ApplicationReadyEvent.class)
    public void openHome() {
        if (!enabled) return;
        if (System.getenv("RENDER") != null || System.getenv("DYNO") != null || System.getenv("KUBERNETES_SERVICE_HOST") != null) return;

        String url = "http://localhost:" + port + "/";
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", url).start();
            } else if (System.getenv("DISPLAY") != null) {
                new ProcessBuilder("xdg-open", url).start();
            }
        } catch (Exception e) {
            System.err.println("[NMAS] Could not open the home page automatically: " + e.getMessage());
        }
    }
}
