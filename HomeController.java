package za.co.nmas.nmas.controller;

import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import za.co.nmas.nmas.service.DoctorService;
import za.co.nmas.nmas.service.FacilityService;
import za.co.nmas.nmas.service.PatientService;

@Controller
public class HomeController {

    private final FacilityService facilityService;
    private final DoctorService doctorService;
    private final PatientService patientService;

    public HomeController(FacilityService facilityService, DoctorService doctorService, PatientService patientService) {
        this.facilityService = facilityService;
        this.doctorService = doctorService;
        this.patientService = patientService;
    }

    /** Public landing page for the whole system. Counts are best-effort: the page still renders if they fail. */
    @GetMapping("/")
    public String home(Model model, Authentication authentication) {
        model.addAttribute("loggedIn", authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken));
        try {
            model.addAttribute("facilities", facilityService.findAll());
            model.addAttribute("facilityCount", facilityService.count());
            model.addAttribute("doctorCount", doctorService.count());
            model.addAttribute("patientCount", patientService.count());
        } catch (Exception e) {
            model.addAttribute("facilities", java.util.List.of());
        }
        return "index";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }
}
