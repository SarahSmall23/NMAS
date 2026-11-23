package za.co.nmas.nmas.controller;

import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import za.co.nmas.nmas.dto.DoctorForm;
import za.co.nmas.nmas.entity.Doctor;
import za.co.nmas.nmas.entity.Facility;
import za.co.nmas.nmas.entity.StaffMember;
import za.co.nmas.nmas.repository.StaffMemberRepository;
import za.co.nmas.nmas.service.AuditLogService;
import za.co.nmas.nmas.service.DoctorService;
import za.co.nmas.nmas.service.FacilityService;

import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/doctors")
public class DoctorController {

    private final DoctorService doctorService;
    private final FacilityService facilityService;
    private final AuditLogService auditLogService;
    private final StaffMemberRepository staffMemberRepository;

    public DoctorController(DoctorService doctorService, FacilityService facilityService,
                            AuditLogService auditLogService, StaffMemberRepository staffMemberRepository) {
        this.doctorService = doctorService;
        this.facilityService = facilityService;
        this.auditLogService = auditLogService;
        this.staffMemberRepository = staffMemberRepository;
    }

    /**
     * The facility this logged-in user is scoped to, for staff-management
     * purposes — null means no scoping (system admins see and manage every
     * facility's doctors; everyone else only ever sees and manages their
     * own facility's).
     */
    private Facility scopeFacility(Authentication auth) {
        boolean isSystemAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_SYSTEM_ADMIN"));
        if (isSystemAdmin) return null;
        return staffMemberRepository.findByEmail(auth.getName())
                .map(StaffMember::getFacility)
                .orElse(null);
    }

    private void populateModel(Model model) {
        List<Doctor> doctors = doctorService.findAll();
        model.addAttribute("doctors", doctors);
        model.addAttribute("suspendedMap", doctorService.getSuspendedMap(doctors));
        model.addAttribute("facilities", facilityService.findAll());
    }

    @GetMapping
    public String list(Authentication auth, Model model) {
        Facility scope = scopeFacility(auth);
        List<Doctor> doctors = scope != null
                ? doctorService.findAll().stream()
                        .filter(d -> d.getFacility() != null && d.getFacility().getId().equals(scope.getId()))
                        .toList()
                : doctorService.findAll();
        Map<Long, Boolean> suspendedMap = doctorService.getSuspendedMap(doctors);
        long suspendedCount = suspendedMap.values().stream().filter(v -> v).count();
        model.addAttribute("doctors", doctors);
        model.addAttribute("suspendedMap", suspendedMap);
        model.addAttribute("suspendedCount", suspendedCount);
        model.addAttribute("activeCount", doctors.size() - suspendedCount);
        model.addAttribute("scopedToFacility", scope);
        return "doctors-list";
    }

    @GetMapping("/register")
    public String showForm(Authentication auth, Model model) {
        model.addAttribute("doctorForm", new DoctorForm());
        populateModel(model);
        model.addAttribute("scopedToFacility", scopeFacility(auth));
        return "doctor-register";
    }

    @PostMapping("/register")
    public String register(@Valid @ModelAttribute("doctorForm") DoctorForm form,
                           BindingResult result,
                           Authentication auth,
                           Model model) {

        if (doctorService.existsByEmail(form.getEmail())) {
            result.rejectValue("email", "error.doctor", "Email already registered");
        }
        if (doctorService.loginExists(form.getEmail())) {
            result.rejectValue("email", "error.doctor", "A login account already exists for this email");
        }
        if (doctorService.existsByHpcsaNumber(form.getHpcsaNumber())) {
            result.rejectValue("hpcsaNumber", "error.doctor", "HPCSA number already registered");
        }

        if (result.hasErrors()) {
            populateModel(model);
            model.addAttribute("scopedToFacility", scopeFacility(auth));
            return "doctor-register";
        }

        // A facility-scoped user (facility admin, receptionist) can only ever
        // register a doctor into their own facility — the facility dropdown
        // is a convenience for system admins, not a way around this.
        Facility scope = scopeFacility(auth);
        if (scope != null) form.setFacilityId(scope.getId());

        Doctor doctor = doctorService.register(form);
        auditLogService.log("DOCTOR_REGISTERED", "Doctor", doctor.getId(),
                "Dr. " + form.getFullName() + " (" + form.getEmail() + ") — HPCSA: " + form.getHpcsaNumber());

        model.addAttribute("successMessage",
                "Doctor registered successfully. They can now log in with their email address.");
        model.addAttribute("doctorForm", new DoctorForm());
        populateModel(model);
        model.addAttribute("scopedToFacility", scope);
        return "doctor-register";
    }

    @PostMapping("/suspend/{id}")
    public String suspend(@PathVariable Long id, Authentication auth, RedirectAttributes ra) {
        doctorService.findById(id).ifPresentOrElse(doctor -> {
            if (!inScope(doctor, auth)) {
                ra.addFlashAttribute("errorMessage", "You can only manage doctors registered at your own facility.");
                return;
            }
            doctorService.suspend(id);
            auditLogService.log("DOCTOR_SUSPENDED", "Doctor", id,
                    "Dr. " + doctor.getFullName() + " (" + doctor.getEmail() + ") — HPCSA: " + doctor.getHpcsaNumber());
            ra.addFlashAttribute("successMessage", "Dr. " + doctor.getFullName() + " has been suspended.");
        }, () -> {});
        return "redirect:/doctors";
    }

    @PostMapping("/unsuspend/{id}")
    public String unsuspend(@PathVariable Long id, Authentication auth, RedirectAttributes ra) {
        doctorService.findById(id).ifPresentOrElse(doctor -> {
            if (!inScope(doctor, auth)) {
                ra.addFlashAttribute("errorMessage", "You can only manage doctors registered at your own facility.");
                return;
            }
            doctorService.unsuspend(id);
            auditLogService.log("DOCTOR_REACTIVATED", "Doctor", id,
                    "Dr. " + doctor.getFullName() + " (" + doctor.getEmail() + ") — HPCSA: " + doctor.getHpcsaNumber());
            ra.addFlashAttribute("successMessage", "Dr. " + doctor.getFullName() + " has been reactivated.");
        }, () -> {});
        return "redirect:/doctors";
    }

    @PostMapping("/delete/{id}")
    public String delete(@PathVariable Long id, Authentication auth, RedirectAttributes ra) {
        doctorService.findById(id).ifPresentOrElse(doctor -> {
            if (!inScope(doctor, auth)) {
                ra.addFlashAttribute("errorMessage", "You can only manage doctors registered at your own facility.");
                return;
            }
            auditLogService.log("DOCTOR_DELETED", "Doctor", id,
                    "Dr. " + doctor.getFullName() + " (" + doctor.getEmail() + ") — HPCSA: " + doctor.getHpcsaNumber());
            doctorService.delete(id);
            ra.addFlashAttribute("successMessage", "Dr. " + doctor.getFullName() + " has been removed from the system.");
        }, () -> {});
        return "redirect:/doctors";
    }

    private boolean inScope(Doctor doctor, Authentication auth) {
        Facility scope = scopeFacility(auth);
        if (scope == null) return true; // system admin — unrestricted
        return doctor.getFacility() != null && doctor.getFacility().getId().equals(scope.getId());
    }
}
