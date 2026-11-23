package za.co.nmas.nmas.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import za.co.nmas.nmas.entity.Visit;
import za.co.nmas.nmas.service.DoctorService;
import za.co.nmas.nmas.service.FacilityService;
import za.co.nmas.nmas.service.PatientService;
import za.co.nmas.nmas.service.VisitService;

@Controller
public class VisitController {

    private final VisitService visitService;
    private final PatientService patientService;
    private final DoctorService doctorService;
    private final FacilityService facilityService;

    public VisitController(VisitService visitService,
                           PatientService patientService,
                           DoctorService doctorService,
                           FacilityService facilityService) {
        this.visitService = visitService;
        this.patientService = patientService;
        this.doctorService = doctorService;
        this.facilityService = facilityService;
    }

    @GetMapping("/visits")
    public String listVisits(Model model) {
        model.addAttribute("visits", visitService.findAll());
        model.addAttribute("totalVisits", visitService.count());
        return "visits";
    }

    @GetMapping("/visits/new")
    public String showVisitForm(Model model) {
        model.addAttribute("visit", new Visit());
        model.addAttribute("patients", patientService.findAll());
        model.addAttribute("doctors", doctorService.findAll());
        model.addAttribute("facilities", facilityService.findAll());
        return "visit-form";
    }

    @PostMapping("/visits/save")
    public String saveVisit(@RequestParam Long patientId,
                            @RequestParam(required = false) Long doctorId,
                            @RequestParam(required = false) Long facilityId,
                            @RequestParam String symptoms,
                            @RequestParam String diagnosis,
                            @RequestParam(required = false) String treatment,
                            @RequestParam(required = false) String notes) {

        visitService.save(patientId, doctorId, facilityId, symptoms, diagnosis, treatment, notes);
        return "redirect:/visits";
    }

    @GetMapping("/visits/{id}/close")
    public String closeVisit(@PathVariable Long id) {
        visitService.close(id);
        return "redirect:/visits";
    }
}
