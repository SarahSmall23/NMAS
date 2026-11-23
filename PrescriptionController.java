package za.co.nmas.nmas.controller;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import za.co.nmas.nmas.entity.Consultation;
import za.co.nmas.nmas.entity.Doctor;
import za.co.nmas.nmas.entity.Prescription;
import za.co.nmas.nmas.service.*;
import za.co.nmas.nmas.repository.StaffMemberRepository;
import za.co.nmas.nmas.entity.Facility;

import java.util.Collections;

@Controller
public class PrescriptionController {

    private final PrescriptionService prescriptionService;
    private final ConsultationService consultationService;
    private final DoctorService doctorService;
    private final MedicationService medicationService;
    private final PrescriptionMedicationService prescriptionMedicationService;
    private final PrescriptionNoteService prescriptionNoteService;
    private final PrescriptionPdfService prescriptionPdfService;
    private final PrescriptionEmailService prescriptionEmailService;
    private final NotificationService notificationService;
    private final StaffMemberRepository staffMemberRepository;

    public PrescriptionController(PrescriptionService prescriptionService,
                                  ConsultationService consultationService,
                                  DoctorService doctorService,
                                  MedicationService medicationService,
                                  PrescriptionMedicationService prescriptionMedicationService,
                                  PrescriptionNoteService prescriptionNoteService,
                                  PrescriptionPdfService prescriptionPdfService,
                                  PrescriptionEmailService prescriptionEmailService,
                                  NotificationService notificationService,
                                  StaffMemberRepository staffMemberRepository) {
        this.staffMemberRepository = staffMemberRepository;
        this.prescriptionService = prescriptionService;
        this.consultationService = consultationService;
        this.doctorService = doctorService;
        this.medicationService = medicationService;
        this.prescriptionMedicationService = prescriptionMedicationService;
        this.prescriptionNoteService = prescriptionNoteService;
        this.prescriptionPdfService = prescriptionPdfService;
        this.prescriptionEmailService = prescriptionEmailService;
        this.notificationService = notificationService;
    }

    /** Downloadable PDF receipt — the document a patient can present at the dispensary or keep for reference. */
    @GetMapping("/prescriptions/{id}/receipt")
    public org.springframework.http.ResponseEntity<byte[]> downloadReceipt(@PathVariable Long id, Authentication auth) {
        Prescription prescription = prescriptionService.findById(id);

        boolean isPatient = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_PATIENT"));
        if (isPatient) {
            boolean isOwner = prescription.getPatient() != null
                    && prescription.getPatient().getEmail() != null
                    && prescription.getPatient().getEmail().equalsIgnoreCase(auth.getName());
            if (!isOwner) {
                return org.springframework.http.ResponseEntity.status(403).build();
            }
        }

        byte[] pdf = prescriptionPdfService.generateReceipt(prescription);

        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_PDF);
        headers.setContentDisposition(org.springframework.http.ContentDisposition
                .attachment().filename("NMAS-Prescription-RX-" + id + ".pdf").build());

        return org.springframework.http.ResponseEntity.ok().headers(headers).body(pdf);
    }

    // == VIDEO CONSULTATION PRESCRIPTION (AJAX, no redirect) ==

    /**
     * Saves a full structured prescription (one or more medication lines)
     * from the video-call prescription panel. Unlike /prescriptions/save,
     * this returns JSON instead of redirecting — a full-page redirect would
     * tear down the doctor's WebRTC connection and end the call. Reuses the
     * exact same service methods as the standard clinic prescription form,
     * so these prescriptions are indistinguishable from ones written
     * in-person (dispensary records, audit log, patient dashboard — all the
     * same).
     */
    @PostMapping("/prescriptions/save-bulk")
    @ResponseBody
    @SuppressWarnings("unchecked")
    public java.util.Map<String, Object> savePrescriptionBulk(Authentication auth,
                                                               @RequestBody java.util.Map<String, Object> body) {
        try {
            Long consultationId = Long.valueOf(body.get("consultationId").toString());
            java.util.List<java.util.Map<String, Object>> meds =
                    (java.util.List<java.util.Map<String, Object>>) body.get("medications");

            if (meds == null || meds.isEmpty()) {
                return java.util.Map.of("ok", false, "error", "At least one medication is required.");
            }

            Doctor doctor = doctorService.findByEmail(auth.getName()).orElse(null);

            // Check every line first, so nothing is saved if any medication is expired or out of stock.
            for (java.util.Map<String, Object> m : meds) {
                medicationService.assertPrescribable(
                        medicationService.findById(Long.valueOf(m.get("medicationId").toString())),
                        doctor != null ? doctor.getFacility() : null);
            }

            java.util.Map<String, Object> first = meds.get(0);
            Prescription saved = prescriptionService.saveWithMedication(
                    consultationId,
                    Long.valueOf(first.get("medicationId").toString()),
                    str(first, "dosage"), str(first, "frequency"), str(first, "duration"),
                    str(first, "route"), str(first, "mealInstruction"),
                    bool(first, "morning"), bool(first, "afternoon"), bool(first, "evening"), bool(first, "night"),
                    str(first, "instructions"), doctor
            );

            for (int i = 1; i < meds.size(); i++) {
                java.util.Map<String, Object> m = meds.get(i);
                prescriptionMedicationService.addMedication(saved.getId(),
                        Long.valueOf(m.get("medicationId").toString()),
                        str(m, "dosage"), str(m, "frequency"), str(m, "duration"),
                        str(m, "route"), str(m, "mealInstruction"),
                        bool(m, "morning"), bool(m, "afternoon"), bool(m, "evening"), bool(m, "night"),
                        str(m, "instructions"));
            }

            // Fetch the fully-saved prescription (with all lines) so the
            // emailed PDF includes every medication, not just the first.
            if (body.get("validityDays") != null && !body.get("validityDays").toString().isBlank()) {
                prescriptionService.setValidityDays(saved.getId(), Integer.parseInt(body.get("validityDays").toString()));
            }
            Prescription fullySaved = prescriptionService.findById(saved.getId());
            prescriptionEmailService.sendReceiptIfPossible(fullySaved);
            notifyPatientPrescription(fullySaved);

            return java.util.Map.of("ok", true, "prescriptionId", saved.getId());
        } catch (Exception e) {
            System.err.println("[NMAS Prescription] save-bulk failed for user=" + auth.getName() + ": " + e);
            e.printStackTrace();
            return java.util.Map.of("ok", false, "error", e.getMessage() != null ? e.getMessage() : "Could not save prescription.");
        }
    }

    private String str(java.util.Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : v.toString();
    }

    private boolean bool(java.util.Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null && Boolean.parseBoolean(v.toString());
    }

    // == EXISTING ENDPOINTS (preserved) ==

    @GetMapping("/prescriptions")
    public String viewPrescriptions(Authentication auth, Model model) {
        boolean isDoctor = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_DOCTOR"));
        if (isDoctor) {
            model.addAttribute("prescriptions",
                    doctorService.findByEmail(auth.getName())
                            .map(doctor -> prescriptionService.findByDoctorId(doctor.getId()))
                            .orElse(Collections.emptyList()));
        } else if (auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SYSTEM_ADMIN"))) {
            model.addAttribute("prescriptions", prescriptionService.findAll());
        } else {
            // Pharmacists, nurses, receptionists and facility admins only see prescriptions issued at their own facility.
            Facility fac = staffMemberRepository.findByEmail(auth.getName()).map(sm -> sm.getFacility()).orElse(null);
            model.addAttribute("prescriptions", fac != null ? prescriptionService.findByFacilityId(fac.getId()) : Collections.emptyList());
        }
        return "prescriptions";
    }

    @GetMapping("/prescriptions/write/{consultationId}")
    public String showPrescriptionForm(@PathVariable Long consultationId, Authentication auth, Model model) {
        Consultation consultation = consultationService.findById(consultationId);
        model.addAttribute("consultation", consultation);
        model.addAttribute("patient", consultation.getPatient());
        model.addAttribute("prescription", new Prescription());
        Doctor me = doctorService.findByEmail(auth.getName()).orElse(null);
        model.addAttribute("allMedications", medicationService.findAllActiveInInventory(me != null ? me.getFacility() : null));
        if (consultation.getPatient() != null) {
            model.addAttribute("elsewhereRx", prescriptionService.issuedElsewhere(consultation.getPatient().getId(),
                    me != null && me.getFacility() != null ? me.getFacility().getId() : null, 90));
        }
        return "prescription-form";
    }

    @PostMapping("/prescriptions/save")
    public String savePrescription(Authentication auth,
                                   @RequestParam Long consultationId,
                                   @RequestParam Long medicationId,
                                   @RequestParam String dosage,
                                   @RequestParam String frequency,
                                   @RequestParam String duration,
                                   @RequestParam String route,
                                   @RequestParam String mealInstruction,
                                   @RequestParam(required = false, defaultValue = "false") boolean morning,
                                   @RequestParam(required = false, defaultValue = "false") boolean afternoon,
                                   @RequestParam(required = false, defaultValue = "false") boolean evening,
                                   @RequestParam(required = false, defaultValue = "false") boolean night,
                                   @RequestParam(required = false) String instructions,
                                   @RequestParam(required = false) Integer validityDays,
                                   RedirectAttributes ra) {
        Doctor doctor = doctorService.findByEmail(auth.getName()).orElse(null);
        Prescription saved;
        try {
            saved = prescriptionService.saveWithMedication(consultationId, medicationId,
                    dosage, frequency, duration, route, mealInstruction,
                    morning, afternoon, evening, night, instructions, doctor);
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
            return "redirect:/prescriptions/write/" + consultationId;
        }
        if (validityDays != null) saved = prescriptionService.setValidityDays(saved.getId(), validityDays);
        prescriptionEmailService.sendReceiptIfPossible(saved);
        notifyPatientPrescription(saved);
        ra.addFlashAttribute("successMessage", "Prescription saved successfully.");
        return "redirect:/prescriptions/" + saved.getId() + "/view";
    }

    private void notifyPatientPrescription(Prescription prescription) {
        if (prescription.getPatient() == null || prescription.getPatient().getEmail() == null) return;
        notificationService.notify(prescription.getPatient().getEmail(), "PRESCRIPTION_RECEIVED",
                "You have received a new prescription"
                        + (prescription.getDoctor() != null ? " from Dr. " + prescription.getDoctor().getFullName() : "") + ".",
                "/patient-dashboard");
    }

    @GetMapping("/prescriptions/{id}/edit")
    public String showEditPrescription(@PathVariable Long id, Model model) {
        Prescription prescription = prescriptionService.findById(id);
        model.addAttribute("prescription", prescription);
        model.addAttribute("medications", prescriptionMedicationService.findByPrescriptionId(id));
        return "prescription-edit";
    }

    @PostMapping("/prescriptions/{id}/update")
    public String updatePrescription(@PathVariable Long id,
                                     @RequestParam(required = false) String instructions,
                                     @RequestParam String status,
                                     RedirectAttributes ra) {
        prescriptionService.updateStatus(id, instructions, status);
        ra.addFlashAttribute("successMessage", "Prescription updated.");
        return "redirect:/prescriptions/" + id + "/view";
    }

    // == NEW ENDPOINTS ==

    @GetMapping("/prescriptions/{id}/view")
    public String viewPrescription(@PathVariable Long id, Authentication auth, Model model) {
        Prescription prescription = prescriptionService.findById(id);
        boolean staffScoped = auth.getAuthorities().stream().anyMatch(x ->
                x.getAuthority().equals("ROLE_PHARMACIST") || x.getAuthority().equals("ROLE_RECEPTIONIST")
                || x.getAuthority().equals("ROLE_FACILITY_ADMIN"));
        if (staffScoped) {
            Facility mine = staffMemberRepository.findByEmail(auth.getName()).map(sm -> sm.getFacility()).orElse(null);
            Facility issued = prescription.getIssuingFacility();
            if (mine == null || issued == null || !mine.getId().equals(issued.getId())) {
                return "redirect:/prescriptions";
            }
        }
        model.addAttribute("prescription", prescription);
        if (prescription.getPatient() != null) {
            model.addAttribute("elsewhereRx", prescriptionService.issuedElsewhere(prescription.getPatient().getId(),
                    prescription.getIssuingFacility() != null ? prescription.getIssuingFacility().getId() : null, 90));
        }
        model.addAttribute("medications", prescriptionMedicationService.findByPrescriptionId(id));
        model.addAttribute("notes", prescriptionNoteService.findByPrescriptionId(id));
        model.addAttribute("allMedications", medicationService.findAllActiveInInventory(
                doctorService.findByEmail(auth.getName()).map(Doctor::getFacility).orElse(null)));
        model.addAttribute("currentUserEmail", auth.getName());
        return "prescription-view";
    }

    @PostMapping("/prescriptions/{id}/medications/add")
    public String addMedication(@PathVariable Long id,
                                 @RequestParam Long medicationId,
                                 @RequestParam String dosage,
                                 @RequestParam String frequency,
                                 @RequestParam String duration,
                                 @RequestParam String route,
                                 @RequestParam String mealInstruction,
                                 @RequestParam(required = false, defaultValue = "false") boolean morning,
                                 @RequestParam(required = false, defaultValue = "false") boolean afternoon,
                                 @RequestParam(required = false, defaultValue = "false") boolean evening,
                                 @RequestParam(required = false, defaultValue = "false") boolean night,
                                 @RequestParam(required = false) String instructions,
                                 RedirectAttributes ra) {
        try {
            prescriptionMedicationService.addMedication(id, medicationId, dosage, frequency, duration,
                    route, mealInstruction, morning, afternoon, evening, night, instructions);
            ra.addFlashAttribute("successMessage", "Medication added.");
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/prescriptions/" + id + "/view";
    }

    @PostMapping("/prescriptions/{id}/medications/{lineId}/remove")
    public String removeMedication(@PathVariable Long id, @PathVariable Long lineId,
                                    RedirectAttributes ra) {
        prescriptionMedicationService.removeMedication(lineId);
        ra.addFlashAttribute("successMessage", "Medication removed.");
        return "redirect:/prescriptions/" + id + "/view";
    }

    @PostMapping("/prescriptions/{id}/notes/add")
    public String addNote(@PathVariable Long id,
                           @RequestParam String noteType,
                           @RequestParam String noteText,
                           Authentication auth,
                           RedirectAttributes ra) {
        Doctor doctor = doctorService.findByEmail(auth.getName())
                .orElseThrow(() -> new RuntimeException("Doctor not found"));
        prescriptionNoteService.addNote(id, noteType, noteText, doctor);
        ra.addFlashAttribute("successMessage", "Note added.");
        return "redirect:/prescriptions/" + id + "/view";
    }

    @PostMapping("/prescriptions/{id}/notes/{noteId}/delete")
    public String deleteNote(@PathVariable Long id, @PathVariable Long noteId,
                              Authentication auth, RedirectAttributes ra) {
        prescriptionNoteService.deleteNote(noteId, auth.getName());
        ra.addFlashAttribute("successMessage", "Note deleted.");
        return "redirect:/prescriptions/" + id + "/view";
    }
}
