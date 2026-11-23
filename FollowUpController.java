package za.co.nmas.nmas.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import za.co.nmas.nmas.entity.Appointment;
import za.co.nmas.nmas.entity.Doctor;
import za.co.nmas.nmas.entity.Patient;
import za.co.nmas.nmas.entity.StaffMember;
import za.co.nmas.nmas.repository.AppointmentRepository;
import za.co.nmas.nmas.repository.StaffMemberRepository;
import za.co.nmas.nmas.service.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/** Doctor-booked follow-up / check-up appointments (patients no longer book physical visits themselves). */
@Controller
public class FollowUpController {

    private final FollowUpService followUpService;
    private final DoctorService doctorService;
    private final PatientService patientService;
    private final AppointmentRepository appointmentRepository;
    private final AppointmentPdfService pdfService;
    private final StaffMemberRepository staffMemberRepository;

    public FollowUpController(FollowUpService followUpService, DoctorService doctorService, PatientService patientService,
                              AppointmentRepository appointmentRepository, AppointmentPdfService pdfService,
                              StaffMemberRepository staffMemberRepository) {
        this.followUpService = followUpService;
        this.doctorService = doctorService;
        this.patientService = patientService;
        this.appointmentRepository = appointmentRepository;
        this.pdfService = pdfService;
        this.staffMemberRepository = staffMemberRepository;
    }

    @GetMapping("/follow-up")
    public String list(Authentication auth, Model model) {
        Doctor d = doctorService.findByEmail(auth.getName()).orElse(null);
        model.addAttribute("followUps", d != null ? followUpService.forDoctor(d.getId()) : List.of());
        model.addAttribute("doctor", d);
        return "follow-up-list";
    }

    @GetMapping("/follow-up/book")
    public String form(@RequestParam(required = false) Long patientId, Authentication auth, Model model) {
        Doctor d = doctorService.findByEmail(auth.getName()).orElse(null);
        model.addAttribute("doctor", d);
        if (patientId != null) {
            try { model.addAttribute("selected", patientService.findById(patientId)); } catch (Exception ignored) { }
        }
        model.addAttribute("minDate", LocalDate.now());
        return "follow-up-book";
    }

    /**
     * Patient search for the booking form. Searches ALL registered patients nationally (a patient registered at
     * another facility may walk in here), and returns the registered facility so the doctor can see it.
     */
    @GetMapping("/follow-up/patients")
    @ResponseBody
    public List<Map<String, String>> searchPatients(@RequestParam String q) {
        String needle = q == null ? "" : q.trim();
        if (needle.length() < 2) return List.of();
        return patientService.searchAll(needle).stream()
                .limit(10)
                .map(p -> Map.of("id", String.valueOf(p.getId()),
                        "name", p.getFullName() != null ? p.getFullName() : "",
                        "idNumber", p.getIdNumber() != null ? p.getIdNumber() : "",
                        "facility", p.getRegisteredFacility() != null ? p.getRegisteredFacility().getFacilityName() : "Not linked to a facility"))
                .toList();
    }

    @PostMapping("/follow-up/book")
    public String book(@RequestParam Long patientId,
                       @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate date,
                       @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.TIME) LocalTime time,
                       @RequestParam(required = false) String reason,
                       Authentication auth, RedirectAttributes ra) {
        try {
            Doctor d = doctorService.findByEmail(auth.getName()).orElse(null);
            Patient p = patientService.findById(patientId);
            Appointment a = followUpService.book(p, d, date, time, reason, auth.getName());
            ra.addFlashAttribute("successMessage", "Follow-up booked for " + p.getFullName() + " on " + a.getAppointmentDate()
                    + " at " + a.getAppointmentTime() + ". The patient and reception have been notified and an email slip was sent.");
            return "redirect:/follow-up";
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
            return "redirect:/follow-up/book" + (patientId != null ? "?patientId=" + patientId : "");
        }
    }

    @GetMapping("/appointments/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable Long id, Authentication auth) {
        Appointment a = appointmentRepository.findById(id).orElse(null);
        if (a == null) return ResponseEntity.notFound().build();
        boolean ok = false;
        boolean patient = auth.getAuthorities().stream().anyMatch(x -> x.getAuthority().equals("ROLE_PATIENT"));
        if (patient) {
            ok = a.getPatient() != null && auth.getName().equalsIgnoreCase(a.getPatient().getEmail());
        } else if (auth.getAuthorities().stream().anyMatch(x -> x.getAuthority().equals("ROLE_SYSTEM_ADMIN"))) {
            ok = true;
        } else {
            StaffMember sm = staffMemberRepository.findByEmail(auth.getName()).orElse(null);
            Doctor doc = doctorService.findByEmail(auth.getName()).orElse(null);
            Long myFacility = sm != null && sm.getFacility() != null ? sm.getFacility().getId()
                    : (doc != null && doc.getFacility() != null ? doc.getFacility().getId() : null);
            Long apptFacility = a.getFacility() != null ? a.getFacility().getId()
                    : (a.getDoctor() != null && a.getDoctor().getFacility() != null ? a.getDoctor().getFacility().getId() : null);
            ok = myFacility != null && myFacility.equals(apptFacility);
        }
        if (!ok) return ResponseEntity.status(403).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"NMAS-Appointment-" + id + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfService.generate(a));
    }

    @PostMapping("/patient/appointments/{id}/confirm")
    public String confirm(@PathVariable Long id, Authentication auth, RedirectAttributes ra) {
        try {
            followUpService.patientConfirm(id, auth.getName());
            ra.addFlashAttribute("successMessage", "Thank you - your appointment is confirmed.");
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/patient-dashboard";
    }
}
