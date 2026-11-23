package za.co.nmas.nmas.controller;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import za.co.nmas.nmas.entity.AppUser;
import za.co.nmas.nmas.entity.Patient;
import za.co.nmas.nmas.repository.AppUserRepository;
import za.co.nmas.nmas.repository.StaffMemberRepository;
import za.co.nmas.nmas.service.AppointmentService;
import za.co.nmas.nmas.service.ConsultationService;
import za.co.nmas.nmas.service.DoctorService;
import za.co.nmas.nmas.service.FacilityService;
import za.co.nmas.nmas.service.PatientService;
import za.co.nmas.nmas.service.PrescriptionService;
import za.co.nmas.nmas.service.RecordAccessService;
import za.co.nmas.nmas.service.CredentialMailService;
import za.co.nmas.nmas.service.VisitService;
import za.co.nmas.nmas.service.WalkInVisitService;

import java.util.List;
import java.util.Map;

@Controller
public class PatientController {

    private final PatientService patientService;
    private final ConsultationService consultationService;
    private final PrescriptionService prescriptionService;
    private final AppointmentService appointmentService;
    private final VisitService visitService;
    private final DoctorService doctorService;
    private final FacilityService facilityService;
    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final CredentialMailService credentialMailService;
    private final StaffMemberRepository staffMemberRepository;
    private final RecordAccessService recordAccessService;
    private final WalkInVisitService walkInVisitService;

    public PatientController(PatientService patientService,
                             ConsultationService consultationService,
                             PrescriptionService prescriptionService,
                             AppointmentService appointmentService,
                             VisitService visitService,
                             DoctorService doctorService,
                             FacilityService facilityService,
                             AppUserRepository appUserRepository,
                             PasswordEncoder passwordEncoder,
                             CredentialMailService credentialMailService,
                             StaffMemberRepository staffMemberRepository,
                             RecordAccessService recordAccessService,
                             WalkInVisitService walkInVisitService) {
        this.patientService = patientService;
        this.consultationService = consultationService;
        this.prescriptionService = prescriptionService;
        this.appointmentService = appointmentService;
        this.visitService = visitService;
        this.doctorService = doctorService;
        this.facilityService = facilityService;
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.credentialMailService = credentialMailService;
        this.staffMemberRepository = staffMemberRepository;
        this.recordAccessService = recordAccessService;
        this.walkInVisitService = walkInVisitService;
    }

    @GetMapping("/patients/register")
    public String showRegistrationForm(Model model) {
        model.addAttribute("patient", new Patient());
        model.addAttribute("facilities", facilityService.findAll());
        return "patient-register";
    }

    @PostMapping("/patients/register")
    public String registerPatient(@Valid @ModelAttribute("patient") Patient patient,
                                  BindingResult result,
                                  Authentication auth,
                                  Model model) {

        if (patient.getIdNumber() != null && patient.getIdNumber().isBlank()) patient.setIdNumber(null);
        if (patient.getPassportNumber() != null && patient.getPassportNumber().isBlank()) patient.setPassportNumber(null);
        if (patient.getEmail() != null && patient.getEmail().isBlank()) patient.setEmail(null);

        patient.setPhoneNumber(za.co.nmas.nmas.util.PhoneUtil.normalize(patient.getPhoneNumber()));
        patient.setAlternativePhoneNumber(za.co.nmas.nmas.util.PhoneUtil.normalize(patient.getAlternativePhoneNumber()));
        patient.setNextOfKinPhone(za.co.nmas.nmas.util.PhoneUtil.normalize(patient.getNextOfKinPhone()));
        patient.setEmergencyContactPhone(za.co.nmas.nmas.util.PhoneUtil.normalize(patient.getEmergencyContactPhone()));
        if (!za.co.nmas.nmas.util.PhoneUtil.isValid(patient.getPhoneNumber()))
            result.rejectValue("phoneNumber", "error.patient", "Enter a valid 10-digit South African phone number (e.g. 0812345678)");
        for (String alt : new String[]{patient.getAlternativePhoneNumber(), patient.getNextOfKinPhone(), patient.getEmergencyContactPhone()}) {
            if (alt != null && !za.co.nmas.nmas.util.PhoneUtil.isValid(alt)) {
                result.rejectValue("phoneNumber", "error.patient", "All phone numbers must be 10-digit South African numbers (e.g. 0812345678)");
                break;
            }
        }

        if (patient.getIdNumber() != null && patientService.findByIdNumber(patient.getIdNumber()).isPresent())
            result.rejectValue("idNumber", "error.patient", "ID number already registered");
        if (patient.getEmail() != null && patientService.findByEmail(patient.getEmail()).isPresent())
            result.rejectValue("email", "error.patient", "Email already registered");

        if (result.hasErrors()) {
            model.addAttribute("facilities", facilityService.findAll());
            return "patient-register";
        }

        // The patient's "home" facility for record-access purposes is the
        // facility of whoever is registering them — not the free-text
        // preferredFacility field, which the person filling out the form
        // could type anything into. A doctor at this facility views the
        // patient's history like normal; a doctor anywhere else can too
        // (never blocked — including emergencies), but that view is logged
        // and this facility's admin is notified (see RecordAccessService).
        staffMemberRepository.findByEmail(auth.getName())
                .map(sm -> sm.getFacility())
                .ifPresent(patient::setRegisteredFacility);

        Patient saved = patientService.register(patient);

        // Auto-generate a system email if patient has none
        String loginEmail = patient.getEmail();
        if (loginEmail == null || loginEmail.isBlank()) {
            // Build email from full name: "John Doe" → "john.doe.12345678@nmas.ac.za"
            String namePart = (patient.getFullName() != null ? patient.getFullName() : "patient")
                    .toLowerCase().trim().replaceAll("\\s+", ".").replaceAll("[^a-z0-9.]", "");
            String suffix = patient.getIdNumber() != null
                    ? patient.getIdNumber().replaceAll("[^0-9]", "").substring(0, Math.min(6, patient.getIdNumber().replaceAll("[^0-9]", "").length()))
                    : String.valueOf(saved.getId());
            loginEmail = namePart + "." + suffix + "@nmas.ac.za";
            // Ensure uniqueness
            String base = loginEmail;
            int attempt = 1;
            while (appUserRepository.findByEmail(loginEmail).isPresent()) {
                loginEmail = base.replace("@", attempt + "@");
                attempt++;
            }
            // Save generated email back to patient record
            saved.setEmail(loginEmail);
            patientService.register(saved);
        }

        // Create login account
        final String finalEmail = loginEmail;
        if (appUserRepository.findByEmail(finalEmail).isEmpty()) {
            String rawPassword = patient.getIdNumber() != null ? patient.getIdNumber()
                               : patient.getPassportNumber() != null ? patient.getPassportNumber()
                               : "NMAS@" + saved.getId();
            AppUser user = new AppUser();
            user.setEmail(finalEmail);
            user.setPassword(passwordEncoder.encode(rawPassword));
            user.setRole("PATIENT");
            appUserRepository.save(user);

            // Send credentials by email to the patient's personal email, if provided
            String personalEmail = patient.getEmail() != null
                    && !patient.getEmail().isBlank()
                    && !patient.getEmail().endsWith("@nmas.ac.za")
                    ? patient.getEmail() : null;

            boolean sent = credentialMailService.sendCredentials(
                personalEmail,
                patient.getFullName(),
                finalEmail,
                rawPassword
            );

            String sentMsg = sent
                ? " ✅ Credentials sent by email."
                : " ⚠️ Could not send automatically — share credentials with patient manually.";

            model.addAttribute("successMessage",
                "✅ Patient registered! Login account created. " +
                "📧 Login Email: " + finalEmail +
                " | 🔑 Password: " + rawPassword + sentMsg);
        } else {
            model.addAttribute("successMessage", "Patient registered successfully.");
        }
        model.addAttribute("patient", new Patient());
        model.addAttribute("facilities", facilityService.findAll());
        return "patient-register";
    }

    @GetMapping("/patients")
    public String viewPatients(@RequestParam(required = false) String facility,
                               @RequestParam(required = false) String q,
                               Model model) {
        List<Patient> patients;
        if ((facility != null && !facility.isBlank()) || (q != null && !q.isBlank())) {
            patients = patientService.searchByFacilityAndQuery(
                facility != null ? facility.trim() : "",
                q != null ? q.trim() : "");
        } else {
            patients = patientService.findAll();
        }
        model.addAttribute("patients", patients);
        model.addAttribute("facilities", patientService.findDistinctFacilities());
        model.addAttribute("selectedFacility", facility);
        model.addAttribute("searchQuery", q);
        return "patients";
    }

    /** Live search autocomplete API — returns JSON list of matching patient names */
    @GetMapping("/api/patients/autocomplete")
    @ResponseBody
    public ResponseEntity<List<Map<String, String>>> autocomplete(
            @RequestParam String q,
            @RequestParam(required = false) String facility) {
        List<Patient> matches = patientService.searchByFacilityAndQuery(
            facility != null ? facility.trim() : "", q.trim());
        List<Map<String, String>> result = matches.stream()
            .limit(8)
            .map(p -> Map.of(
                "id", String.valueOf(p.getId()),
                "name", p.getFullName() != null ? p.getFullName() : "",
                "idNumber", p.getIdNumber() != null ? p.getIdNumber() : "",
                "facility", p.getPreferredFacility() != null ? p.getPreferredFacility() : ""
            ))
            .toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/patients/history/{id}")
    public String viewPatientHistory(@PathVariable Long id, Authentication auth, Model model) {
        Patient patient = patientService.findById(id);
        model.addAttribute("patient", patient);

        // Never gate the view itself -- a doctor or nurse can always see a
        // patient's history, including in an emergency. If they are from a
        // different facility than the patient's home facility, the view is
        // logged and the home facility's admin (and the patient) notified.
        String viewerRole = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_DOCTOR")) ? "DOCTOR"
                : auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_NURSE")) ? "NURSE" : null;
        if (viewerRole != null) {
            recordAccessService.logStaffView(patient, auth.getName(), viewerRole);
            model.addAttribute("crossFacilityView", recordAccessService.isCrossFacilityStaff(patient, auth.getName(), viewerRole));
        }

        model.addAttribute("consultations", consultationService.findByPatientId(id));
        model.addAttribute("prescriptions", prescriptionService.findByPatientId(id));
        model.addAttribute("appointments", appointmentService.findByPatientId(id));
        model.addAttribute("visits", visitService.findByPatientId(id));
        model.addAttribute("walkIns", walkInVisitService.historyForPatient(id));
        return "patient-medical-history";
    }

    @GetMapping("/patients/{id}/edit-medical")
    public String showEditMedical(@PathVariable Long id, Authentication auth, Model model) {
        Patient patient = patientService.findById(id);
        boolean isDoctor = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_DOCTOR"));
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_FACILITY_ADMIN")
                            || a.getAuthority().equals("ROLE_SYSTEM_ADMIN"));
        if (isDoctor) {
            boolean linked = doctorService.findByEmail(auth.getName())
                    .map(d -> appointmentService.doctorHasPatient(d.getId(), id))
                    .orElse(false);
            if (!linked) {
                model.addAttribute("errorMessage", "Access denied. You can only edit medical info for your own patients.");
                model.addAttribute("patient", patient);
                model.addAttribute("consultations", consultationService.findByPatientId(id));
                model.addAttribute("prescriptions", prescriptionService.findByPatientId(id));
                model.addAttribute("appointments", appointmentService.findByPatientId(id));
                model.addAttribute("visits", visitService.findByPatientId(id));
                return "patient-medical-history";
            }
        }
        if (!isDoctor && !isAdmin) return "redirect:/patients/history/" + id;
        if (isDoctor) recordAccessService.logStaffView(patient, auth.getName(), "DOCTOR");
        model.addAttribute("patient", patient);
        return "patient-edit-medical";
    }

    @PostMapping("/patients/{id}/update-medical")
    public String updateMedical(@PathVariable Long id,
                                @RequestParam(required = false) String bloodType,
                                @RequestParam(required = false) String allergies,
                                @RequestParam(required = false) String chronicConditions,
                                @RequestParam(required = false) String currentMedication,
                                @RequestParam(required = false) String pastSurgeries,
                                @RequestParam(required = false) String disabilities,
                                @RequestParam(required = false) String medicalNotes,
                                @RequestParam(required = false) String medicalAidStatus,
                                @RequestParam(required = false) String medicalAidProvider,
                                @RequestParam(required = false) String medicalAidNumber,
                                @RequestParam(required = false) Boolean consentToStoreRecords,
                                @RequestParam(required = false) Boolean consentToShareRecords) {
        patientService.updateMedical(id, bloodType, allergies, chronicConditions,
                currentMedication, pastSurgeries, disabilities, medicalNotes,
                medicalAidStatus, medicalAidProvider, medicalAidNumber,
                consentToStoreRecords, consentToShareRecords);
        return "redirect:/patients/history/" + id;
    }
}
