package za.co.nmas.nmas.controller;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import za.co.nmas.nmas.entity.VideoConsultationRequest;
import za.co.nmas.nmas.service.AppointmentService;
import za.co.nmas.nmas.service.DoctorAvailabilityService;
import za.co.nmas.nmas.service.DoctorService;
import za.co.nmas.nmas.service.MedicationService;
import za.co.nmas.nmas.service.NotificationService;
import za.co.nmas.nmas.service.PatientService;
import java.util.List;
import za.co.nmas.nmas.service.VideoConsultationService;

@Controller
@RequestMapping("/video")
public class VideoController {

    private final VideoConsultationService videoService;
    private final PatientService patientService;
    private final DoctorService doctorService;
    private final AppointmentService appointmentService;
    private final MedicationService medicationService;
    private final NotificationService notificationService;
    private final DoctorAvailabilityService availabilityService;

    public VideoController(VideoConsultationService videoService,
                           PatientService patientService,
                           DoctorService doctorService,
                           AppointmentService appointmentService,
                           MedicationService medicationService,
                           NotificationService notificationService,
                           DoctorAvailabilityService availabilityService) {
        this.availabilityService = availabilityService;
        this.videoService = videoService;
        this.patientService = patientService;
        this.doctorService = doctorService;
        this.appointmentService = appointmentService;
        this.medicationService = medicationService;
        this.notificationService = notificationService;
    }

    @GetMapping("/my-requests")
    public String myRequests(Authentication auth, Model model) {
        String loginEmail = auth.getName();
        patientService.findByEmail(loginEmail)
            .or(() -> patientService.findAll().stream()
                    .filter(p -> loginEmail.equals(p.getEmail()))
                    .findFirst())
            .ifPresent(patient -> {
                model.addAttribute("requests", videoService.findByPatient(patient.getId()));
                model.addAttribute("patient", patient);
                java.util.List<za.co.nmas.nmas.entity.Doctor> docs = doctorService.findAll();
                model.addAttribute("doctors", docs);
                java.util.Map<Long, Boolean> onShift = new java.util.HashMap<>();
                for (za.co.nmas.nmas.entity.Doctor d : docs) onShift.put(d.getId(), availabilityService.isOnShiftNow(d.getId()));
                model.addAttribute("onShift", onShift);
                model.addAttribute("appointments", appointmentService.findByPatientId(patient.getId()));
            });
        return "patient-video-requests";
    }

    @PostMapping("/request")
    public String request(@RequestParam Long doctorId,
                          @RequestParam(required = false) Long appointmentId,
                          @RequestParam(required = false) String notes,
                          Authentication auth,
                          org.springframework.web.servlet.mvc.support.RedirectAttributes ra) {
        String loginEmail = auth.getName();
        if (!availabilityService.isOnShiftNow(doctorId)) {
            String who = doctorService.findById(doctorId).map(d -> "Dr. " + d.getFullName()).orElse("This doctor");
            java.util.Map<String, Object> sched = availabilityService.getScheduleSummary(doctorId, null);
            Object next = sched.get("nextAvailable");
            String hint = "";
            if (next instanceof java.util.Map<?, ?> m) {
                hint = " Next available: " + m.get("dayName") + " " + m.get("date") + " from " + m.get("startTime") + ".";
            }
            ra.addFlashAttribute("errorMessage", who + " is not on a video-consultation shift right now, so your request was not sent." + hint
                    + " Please request again during their schedule or choose another doctor.");
            return "redirect:/video/my-requests";
        }
        patientService.findByEmail(loginEmail)
            .or(() -> patientService.findAll().stream()
                    .filter(p -> loginEmail.equals(p.getEmail()))
                    .findFirst())
            .ifPresent(patient -> {
                videoService.request(patient.getId(), doctorId, appointmentId, notes);
                doctorService.findById(doctorId).ifPresent(doctor ->
                        notificationService.notify(doctor.getEmail(), "VIDEO_REQUESTED",
                                patient.getFullName() + " requested a video call.",
                                "/video/incoming"));
            });
        return "redirect:/video/my-requests";
    }

    @GetMapping("/incoming")
    public String incoming(Authentication auth, Model model) {
        doctorService.findByEmail(auth.getName()).ifPresent(doctor -> {
            // Show REQUESTED (new) + ACCEPTED (active rooms) — not ENDED/CANCELLED
            List<za.co.nmas.nmas.entity.VideoConsultationRequest> all =
                videoService.findAllByDoctor(doctor.getId()).stream()
                    .filter(r -> r.getStatus().equals("REQUESTED") || r.getStatus().equals("ACCEPTED"))
                    .toList();
            model.addAttribute("requests", all);
            model.addAttribute("doctor", doctor);
        });
        return "doctor-incoming-video-calls";
    }

    @GetMapping("/accept/{id}")
    public String accept(@PathVariable Long id, Authentication auth) {
        videoService.accept(id, auth.getName());
        VideoConsultationRequest req = videoService.findById(id);
        if (req.getPatient() != null) {
            notificationService.notify(req.getPatient().getEmail(), "VIDEO_ACCEPTED",
                    "Your video call request was accepted"
                            + (req.getDoctor() != null ? " by Dr. " + req.getDoctor().getFullName() : "")
                            + " — you can join whenever you are ready.",
                    "/video/room/" + req.getRoomId());
        }
        return "redirect:/video/room/" + req.getRoomId();
    }

    @GetMapping("/room/{roomId}")
    public String room(@PathVariable String roomId, Authentication auth, Model model) {
        VideoConsultationRequest req = videoService.findByRoomId(roomId);
        model.addAttribute("request", req);
        model.addAttribute("currentUser", auth.getName());
        boolean isDoctor = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_DOCTOR"));
        model.addAttribute("currentUserRole", isDoctor ? "DOCTOR" : "PATIENT");

        if (isDoctor) {
            // Prepare the structured prescription panel: a real Consultation
            // record to attach the prescription to, and the medication
            // formulary to populate the picker.
            var consultation = videoService.ensureConsultation(req.getId());
            model.addAttribute("consultationId", consultation.getId());
            model.addAttribute("allMedications", medicationService.findAllActiveInInventory(req.getDoctor() != null ? req.getDoctor().getFacility() : null));
        }

        // Serve different template per role
        return isDoctor ? "video-room-doctor" : "video-room-patient";
    }

    /** Save prescription from video consultation into the patient's prescription record */
    @PostMapping("/save-prescription")
    @ResponseBody
    public org.springframework.http.ResponseEntity<String> savePrescription(
            @RequestBody java.util.Map<String, Object> body,
            Authentication auth) {
        try {
            Long requestId = Long.valueOf(body.get("requestId").toString());
            String prescriptionText = body.get("prescription").toString();
            videoService.savePrescription(requestId, prescriptionText, auth.getName());
            return org.springframework.http.ResponseEntity.ok("saved");
        } catch (Exception e) {
            return org.springframework.http.ResponseEntity.status(500).body("error: " + e.getMessage());
        }
    }

    @GetMapping("/end/{id}")
    public String end(@PathVariable Long id, Authentication auth) {
        VideoConsultationRequest req = videoService.findById(id);
        boolean isDoctor = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_DOCTOR"));
        videoService.end(id);
        if (req.getRoomId() != null) {
            za.co.nmas.nmas.websocket.ConsultationSignalingServer
                    .broadcastCallEnded(req.getRoomId(), isDoctor ? "doctor" : "patient");
        }
        return isDoctor ? "redirect:/video/incoming" : "redirect:/video/my-requests";
    }

    @GetMapping("/cancel/{id}")
    public String cancel(@PathVariable Long id) {
        videoService.cancel(id);
        return "redirect:/video/my-requests";
    }
}
