package za.co.nmas.nmas.controller;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardRedirectController {

    private final za.co.nmas.nmas.service.LoginActivityService loginActivityService;

    public DashboardRedirectController(za.co.nmas.nmas.service.LoginActivityService loginActivityService) {
        this.loginActivityService = loginActivityService;
    }

    @GetMapping("/redirect-dashboard")
    public String redirectDashboard(Authentication authentication) {

        loginActivityService.record(authentication);
        String authorities = authentication.getAuthorities().toString();
        System.out.println("[NMAS Dashboard Redirect] user=" + authentication.getName() + " authorities=" + authorities);

        if (authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_DOCTOR"))) {
            return "redirect:/doctor-dashboard";
        }

        if (authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_NURSE"))) {
            return "redirect:/nurse-dashboard";
        }

        if (authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_PHARMACIST"))) {
            return "redirect:/pharmacist-dashboard";
        }

        if (authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_FACILITY_ADMIN"))) {
            return "redirect:/facility-admin-dashboard";
        }

        if (authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SYSTEM_ADMIN"))) {
            return "redirect:/system-admin-dashboard";
        }

        if (authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_RECEPTIONIST"))) {
            return "redirect:/receptionist-dashboard";
        }

        if (authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_PATIENT"))) {
            return "redirect:/patient-dashboard";
        }

        return "redirect:/login";
    }
}