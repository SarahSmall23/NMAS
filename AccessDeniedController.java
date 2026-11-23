package za.co.nmas.nmas.controller;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AccessDeniedController {

    @GetMapping("/access-denied")
    public String accessDenied(Authentication authentication, Model model) {
        if (authentication != null) {
            model.addAttribute("currentUser", authentication.getName());
            String role = authentication.getAuthorities().stream()
                    .findFirst()
                    .map(a -> a.getAuthority().replace("ROLE_", ""))
                    .orElse("UNKNOWN");
            model.addAttribute("currentRole", role);
        }
        return "access-denied";
    }
}
