package za.co.nmas.nmas.controller;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import za.co.nmas.nmas.entity.*;
import za.co.nmas.nmas.repository.StaffMemberRepository;
import za.co.nmas.nmas.service.AppointmentService;
import za.co.nmas.nmas.service.DoctorService;
import za.co.nmas.nmas.service.PatientService;
import za.co.nmas.nmas.service.PillCollectionService;
import za.co.nmas.nmas.service.WalkInVisitService;

import java.time.LocalDate;
import java.util.*;

/**
 * The reception -> nurse -> doctor flow:
 *   /reception/walk-ins  receptionist registers the visit (who came, for which doctor/service)
 *   /nurse/queue         nurse sees the list oldest-first, records vitals, sends to a doctor
 *   /doctor/queue        doctor sees patients sent to them (with vitals) and starts the consultation
 * Everything is scoped to the logged-in staff member's own facility.
 */
@Controller
public class WalkInController {

    private static final List<String> BASE_SERVICES = List.of(
            "General Practitioner", "Cardiology", "Neurology", "Dermatology", "Gynaecology",
            "Pediatrics", "Internal Medicine", "Emergency Medicine", "Other");

    private final WalkInVisitService walkIns;
    private final StaffMemberRepository staffMemberRepository;
    private final PatientService patientService;
    private final AppointmentService appointmentService;
    private final DoctorService doctorService;
    private final PillCollectionService pillCollectionService;
    @org.springframework.beans.factory.annotation.Autowired
    private za.co.nmas.nmas.repository.DoctorCheckInRepository checkInRepository;

    public WalkInController(WalkInVisitService walkIns, StaffMemberRepository staffMemberRepository,
                            PatientService patientService, AppointmentService appointmentService,
                            DoctorService doctorService, PillCollectionService pillCollectionService) {
        this.walkIns = walkIns;
        this.staffMemberRepository = staffMemberRepository;
        this.patientService = patientService;
        this.appointmentService = appointmentService;
        this.doctorService = doctorService;
        this.pillCollectionService = pillCollectionService;
    }

    private Facility facilityOf(Authentication auth) {
        return staffMemberRepository.findByEmail(auth.getName()).map(StaffMember::getFacility).orElse(null);
    }

    private List<Doctor> doctorsAt(Facility f) {
        return doctorService.findAll().stream()
                .filter(d -> d.getFacility() != null && d.getFacility().getId().equals(f.getId()))
                .sorted(Comparator.comparing(Doctor::getFullName)).toList();
    }

    // ============================================================= RECEPTION

    @GetMapping("/reception/walk-ins")
    public String reception(@RequestParam(required = false) String q, Authentication auth, Model model) {
        Facility facility = facilityOf(auth);
        model.addAttribute("facility", facility);
        if (facility == null) return "reception-walkins";

        // "Came for" lists only doctors who are checked in (on duty) at this facility today.
        java.time.LocalDateTime startOfDay = java.time.LocalDate.now().atStartOfDay();
        java.util.Map<String, java.util.List<String>> onDuty = new java.util.TreeMap<>();
        for (za.co.nmas.nmas.entity.DoctorCheckIn c : checkInRepository.findByCheckOutTimeIsNull()) {
            Doctor d = c.getDoctor();
            if (c.getCheckInTime() == null || c.getCheckInTime().isBefore(startOfDay)) continue;
            if (d == null || d.getFacility() == null || !d.getFacility().getId().equals(facility.getId())) continue;
            String spec = d.getSpecialization() != null ? d.getSpecialization() : "General Practitioner";
            onDuty.computeIfAbsent(spec, k -> new java.util.ArrayList<>()).add("Dr. " + d.getFullName());
        }
        java.util.Map<String, String> services = new java.util.LinkedHashMap<>();
        onDuty.forEach((spec, names) -> services.put(spec, spec + " - " + String.join(", ", names) + " (checked in)"));
        services.put("Other", "Other");
        model.addAttribute("services", services);
        model.addAttribute("noDoctorsCheckedIn", onDuty.isEmpty());

        String query = q == null ? "" : q.trim().toLowerCase();
        model.addAttribute("q", q == null ? "" : q.trim());
        if (!query.isEmpty()) {
            model.addAttribute("results", patientService.findAll().stream()
                    .filter(p -> (p.getFullName() != null && p.getFullName().toLowerCase().contains(query))
                            || (p.getIdNumber() != null && p.getIdNumber().contains(query))
                            || (p.getPassportNumber() != null && p.getPassportNumber().toLowerCase().contains(query))
                            || (p.getPhoneNumber() != null && p.getPhoneNumber().contains(query)))
                    .limit(15).toList());
        }

        model.addAttribute("queue", walkIns.activeAtFacility(facility.getId()));
        model.addAttribute("collectionsToday", pillCollectionService.dueToday(facility.getId()));
        model.addAttribute("collectedRecently", pillCollectionService.recentlyCollected(facility.getId()));

        LocalDate today = LocalDate.now();
        Set<Long> checkedIn = new HashSet<>();
        walkIns.todayAtFacility(facility.getId()).forEach(w -> { if (w.getAppointment() != null && !"CANCELLED".equals(w.getStatus())) checkedIn.add(w.getAppointment().getId()); });
        model.addAttribute("todaysAppointments", appointmentService.findAll().stream()
                .filter(a -> today.equals(a.getAppointmentDate()))
                .filter(a -> "PENDING".equals(a.getStatus()) || "APPROVED".equals(a.getStatus()))
                .filter(a -> (a.getFacility() != null && a.getFacility().getId().equals(facility.getId()))
                        || (a.getFacility() == null && a.getDoctor() != null && a.getDoctor().getFacility() != null
                            && a.getDoctor().getFacility().getId().equals(facility.getId())))
                .filter(a -> !checkedIn.contains(a.getId()))
                .sorted(Comparator.comparing(Appointment::getAppointmentTime, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList());
        return "reception-walkins";
    }

    @PostMapping("/reception/walk-ins")
    public String checkIn(@RequestParam Long patientId, @RequestParam String service,
                          @RequestParam(required = false) String reason,
                          Authentication auth, RedirectAttributes ra) {
        Facility facility = facilityOf(auth);
        try {
            if (facility == null) throw new IllegalArgumentException("Your account isn't linked to a facility.");
            WalkInVisit v = walkIns.checkInWalkIn(patientId, facility, service, reason, auth.getName());
            ra.addFlashAttribute("successMessage", v.getPatient().getFullName() + " checked in for " + v.getRequestedService() + " — sent to the nurse's list."
                    + (v.isPriorityElderly() ? " Elderly patient (age " + v.getPatientAge() + ") — prioritised, will be helped first." : ""));
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/reception/walk-ins";
    }

    @PostMapping("/reception/appointments/{id}/check-in")
    public String checkInAppointment(@PathVariable Long id, Authentication auth, RedirectAttributes ra) {
        Facility facility = facilityOf(auth);
        try {
            if (facility == null) throw new IllegalArgumentException("Your account isn't linked to a facility.");
            WalkInVisit v = walkIns.checkInAppointment(id, facility, auth.getName());
            ra.addFlashAttribute("successMessage", v.getPatient().getFullName() + " checked in for their booked appointment — sent to the nurse's list."
                    + (v.isPriorityElderly() ? " Elderly patient (age " + v.getPatientAge() + ") — prioritised, will be helped first." : ""));
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/reception/walk-ins";
    }

    @PostMapping("/reception/collections/{id}/confirm")
    public String confirmCollection(@PathVariable Long id, Authentication auth, RedirectAttributes ra) {
        Facility facility = facilityOf(auth);
        try {
            if (facility == null) throw new IllegalArgumentException("Your account isn't linked to a facility.");
            var c = pillCollectionService.confirmAtReception(id, facility, auth.getName());
            ra.addFlashAttribute("successMessage", c.getPatient().getFullName() + " confirmed for pill collection — the pharmacist has been told.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/reception/walk-ins";
    }

    @PostMapping("/reception/walk-ins/{id}/cancel")
    public String cancel(@PathVariable Long id, Authentication auth, RedirectAttributes ra) {
        Facility facility = facilityOf(auth);
        try {
            if (facility == null) throw new IllegalArgumentException("Your account isn't linked to a facility.");
            walkIns.cancel(id, facility.getId());
            ra.addFlashAttribute("successMessage", "Visit cancelled.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/reception/walk-ins";
    }

    // ================================================================= NURSE

    @GetMapping("/nurse/queue")
    public String nurseQueue(Authentication auth, Model model) {
        Facility facility = facilityOf(auth);
        model.addAttribute("facility", facility);
        if (facility != null) {
            model.addAttribute("waiting", walkIns.awaitingVitals(facility.getId()));
            model.addAttribute("sentToday", walkIns.todayAtFacility(facility.getId()).stream()
                    .filter(w -> !"WAITING_VITALS".equals(w.getStatus())).toList());
        }
        return "nurse-queue";
    }

    @GetMapping("/nurse/vitals/{id}")
    public String vitalsForm(@PathVariable Long id, Authentication auth, Model model, RedirectAttributes ra) {
        Facility facility = facilityOf(auth);
        try {
            if (facility == null) throw new IllegalArgumentException("Your account isn't linked to a facility.");
            WalkInVisit v = walkIns.forFacility(id, facility.getId());
            if (!"WAITING_VITALS".equals(v.getStatus()))
                throw new IllegalArgumentException("Vitals were already recorded for this visit.");
            model.addAttribute("visit", v);
            model.addAttribute("doctors", doctorsAt(facility));
            Long suggested = null;
            if (v.getAppointment() != null && v.getAppointment().getDoctor() != null) {
                suggested = v.getAppointment().getDoctor().getId();
            } else {
                for (Doctor d : doctorsAt(facility)) {
                    if (d.getSpecialization() != null && d.getSpecialization().equalsIgnoreCase(v.getRequestedService())) { suggested = d.getId(); break; }
                }
            }
            model.addAttribute("suggestedDoctorId", suggested);
            return "nurse-vitals";
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
            return "redirect:/nurse/queue";
        }
    }

    @PostMapping("/nurse/vitals/{id}")
    public String saveVitals(@PathVariable Long id,
                             @RequestParam(required = false) Integer bpSystolic, @RequestParam(required = false) Integer bpDiastolic,
                             @RequestParam(required = false) Integer heartRate, @RequestParam(required = false) Double temperature,
                             @RequestParam(required = false) Double weightKg, @RequestParam(required = false) Double heightCm,
                             @RequestParam(required = false) Integer oxygenSaturation, @RequestParam(required = false) Double bloodGlucose,
                             @RequestParam(required = false) String hivStatus, @RequestParam(required = false) String nurseNotes,
                             @RequestParam Long doctorId, Authentication auth, RedirectAttributes ra) {
        Facility facility = facilityOf(auth);
        try {
            if (facility == null) throw new IllegalArgumentException("Your account isn't linked to a facility.");
            WalkInVisit v = walkIns.saveVitalsAndSend(id, facility, bpSystolic, bpDiastolic, heartRate, temperature,
                    weightKg, heightCm, oxygenSaturation, bloodGlucose, hivStatus, nurseNotes, doctorId, auth.getName());
            ra.addFlashAttribute("successMessage", "Vitals saved — " + v.getPatient().getFullName() + " sent to Dr. " + v.getAssignedDoctor().getFullName() + ".");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
            return "redirect:/nurse/vitals/" + id;
        }
        return "redirect:/nurse/queue";
    }

    // =============================================================== DOCTOR

    @GetMapping("/doctor/queue")
    public String doctorQueue(Authentication auth, Model model) {
        doctorService.findByEmail(auth.getName()).ifPresent(d -> model.addAttribute("queue", walkIns.doctorQueue(d.getId())));
        return "doctor-queue";
    }

    @PostMapping("/doctor/queue/{id}/start")
    public String start(@PathVariable Long id, Authentication auth, RedirectAttributes ra) {
        try {
            Doctor doctor = doctorService.findByEmail(auth.getName())
                    .orElseThrow(() -> new IllegalArgumentException("Doctor account not found."));
            return "redirect:/consultations/start/" + walkIns.startConsultation(id, doctor);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
            return "redirect:/doctor/queue";
        }
    }
}
