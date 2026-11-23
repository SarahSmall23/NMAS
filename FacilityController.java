package za.co.nmas.nmas.controller;

import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import za.co.nmas.nmas.entity.Facility;
import za.co.nmas.nmas.service.AuditLogService;
import za.co.nmas.nmas.service.FacilityService;

@Controller
@RequestMapping("/facilities")
public class FacilityController {

    private final FacilityService facilityService;
    private final AuditLogService auditLogService;

    public FacilityController(FacilityService facilityService, AuditLogService auditLogService) {
        this.facilityService = facilityService;
        this.auditLogService = auditLogService;
    }

    @GetMapping("/register")
    public String showForm(Model model) {
        model.addAttribute("facility", new Facility());
        return "facility-register";
    }

    @PostMapping("/register")
    public String register(@Valid @ModelAttribute("facility") Facility facility,
                           BindingResult result,
                           Model model) {

        if (result.hasErrors()) {
            return "facility-register";
        }

        facilityService.register(facility);
        auditLogService.log("FACILITY_REGISTERED", "Facility", facility.getId(),
                facility.getFacilityName() + " (" + facility.getFacilityType() + ") — "
                + facility.getCity() + ", " + facility.getProvince());

        model.addAttribute("successMessage", "Facility registered successfully.");
        model.addAttribute("facility", new Facility());
        return "facility-register";
    }
}
