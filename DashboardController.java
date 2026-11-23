package za.co.nmas.nmas.controller;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import za.co.nmas.nmas.repository.MedicationInventoryRepository;
import za.co.nmas.nmas.repository.StaffMemberRepository;
import za.co.nmas.nmas.service.AppointmentService;
import za.co.nmas.nmas.service.ConsultationService;
import za.co.nmas.nmas.service.DispensaryService;
import za.co.nmas.nmas.service.DoctorService;
import za.co.nmas.nmas.service.FacilityService;
import za.co.nmas.nmas.service.PatientService;
import za.co.nmas.nmas.service.PrescriptionService;
import za.co.nmas.nmas.service.RecordAccessService;
import za.co.nmas.nmas.service.VideoConsultationService;
import za.co.nmas.nmas.service.WalkInVisitService;
import za.co.nmas.nmas.service.VisitService;

@Controller
public class DashboardController {

    private final AppointmentService appointmentService;
    private final ConsultationService consultationService;
    private final PrescriptionService prescriptionService;
    private final DoctorService doctorService;
    private final FacilityService facilityService;
    private final PatientService patientService;
    private final VisitService visitService;
    private final StaffMemberRepository staffMemberRepository;
    private final MedicationInventoryRepository inventoryRepository;
    private final DispensaryService dispensaryService;
    private final VideoConsultationService videoService;
    private final RecordAccessService recordAccessService;
    private final WalkInVisitService walkInVisitService;

    @org.springframework.beans.factory.annotation.Autowired
    private za.co.nmas.nmas.repository.DoctorCheckInRepository checkInRepository;

    public DashboardController(AppointmentService appointmentService,
                               ConsultationService consultationService,
                               PrescriptionService prescriptionService,
                               DoctorService doctorService,
                               FacilityService facilityService,
                               PatientService patientService,
                               VisitService visitService,
                               StaffMemberRepository staffMemberRepository,
                               MedicationInventoryRepository inventoryRepository,
                               DispensaryService dispensaryService,
                               VideoConsultationService videoService,
                               RecordAccessService recordAccessService,
                               WalkInVisitService walkInVisitService) {
        this.appointmentService = appointmentService;
        this.consultationService = consultationService;
        this.prescriptionService = prescriptionService;
        this.doctorService = doctorService;
        this.facilityService = facilityService;
        this.patientService = patientService;
        this.visitService = visitService;
        this.staffMemberRepository = staffMemberRepository;
        this.inventoryRepository = inventoryRepository;
        this.dispensaryService = dispensaryService;
        this.videoService = videoService;
        this.recordAccessService = recordAccessService;
        this.walkInVisitService = walkInVisitService;
    }

    /** Appointments belonging to one facility (its own, or its doctors' when none is set). */
    private java.util.List<za.co.nmas.nmas.entity.Appointment> facilityAppointments(Long fid) {
        return appointmentService.findAll().stream()
                .filter(a -> (a.getFacility() != null && a.getFacility().getId().equals(fid))
                        || (a.getFacility() == null && a.getDoctor() != null && a.getDoctor().getFacility() != null
                            && a.getDoctor().getFacility().getId().equals(fid)))
                .toList();
    }

    private void addStaffMember(Authentication auth, Model model) {
        staffMemberRepository.findByEmail(auth.getName())
                .ifPresent(s -> model.addAttribute("staffMember", s));
        model.addAttribute("userEmail", auth.getName());
    }

    @GetMapping("/doctor-dashboard")
    public String doctorDashboard(Authentication auth, Model model) {
        model.addAttribute("currentUser", auth.getName());
        doctorService.findByEmail(auth.getName()).ifPresent(doctor -> {
            Long doctorId = doctor.getId();
            model.addAttribute("doctorName", doctor.getFullName());
            model.addAttribute("doctorSpecialization", doctor.getSpecialization());
            model.addAttribute("doctorFacility",
                    doctor.getFacility() != null ? doctor.getFacility().getFacilityName() : "NMAS Portal");
            model.addAttribute("todayAppointments", appointmentService.countTodayByDoctorId(doctorId));
            model.addAttribute("totalAppointments", appointmentService.countByDoctorId(doctorId));
            model.addAttribute("pendingConsultations", appointmentService.countByDoctorIdAndStatus(doctorId, "APPROVED"));
            model.addAttribute("totalPrescriptions", prescriptionService.countByDoctorId(doctorId));
            model.addAttribute("totalConsultations", consultationService.countByDoctorId(doctorId));
            // Video consultation requests waiting for this doctor
            model.addAttribute("pendingVideoRequests", videoService.findIncoming(doctorId));
            model.addAttribute("pendingVideoCount", videoService.findIncoming(doctorId).size());
            model.addAttribute("waitingPatients", walkInVisitService.doctorWaitingCount(doctorId));
            model.addAttribute("checkIn", checkInRepository
                    .findFirstByDoctorIdAndCheckOutTimeIsNullOrderByCheckInTimeDesc(doctorId).orElse(null));
            model.addAttribute("hasSignature", doctor.hasSignature());
        });
        return "doctor-dashboard";
    }

    @GetMapping("/nurse-dashboard")
    public String nurseDashboard(Authentication auth, Model model) {
        addStaffMember(auth, model);
        za.co.nmas.nmas.entity.Facility fac = staffMemberRepository.findByEmail(auth.getName())
                .map(sm -> sm.getFacility()).orElse(null);
        if (fac != null) {
            Long fid = fac.getId();
            model.addAttribute("waitingForVitals", walkInVisitService.awaitingVitals(fid).size());
            model.addAttribute("totalPatients", patientService.findAll().stream()
                    .filter(p -> p.getRegisteredFacility() != null && p.getRegisteredFacility().getId().equals(fid)).count());
            java.util.List<za.co.nmas.nmas.entity.Appointment> apps = facilityAppointments(fid);
            model.addAttribute("totalAppointments", apps.size());
            model.addAttribute("pendingAppointments", apps.stream().filter(x -> "PENDING".equals(x.getStatus())).count());
        } else {
            model.addAttribute("waitingForVitals", 0);
            model.addAttribute("totalPatients", 0);
            model.addAttribute("totalAppointments", 0);
            model.addAttribute("pendingAppointments", 0);
        }
        model.addAttribute("totalVisits", visitService.count());
        return "nurse-dashboard";
    }

    @GetMapping("/pharmacist-dashboard")
    public String pharmacistDashboard(Authentication auth,
                                      @RequestParam(required = false) String medSearch,
                                      Model model) {
        addStaffMember(auth, model);
        // This pharmacy's own stock and prescriptions only
        za.co.nmas.nmas.entity.Facility fac = staffMemberRepository.findByEmail(auth.getName())
                .map(sm -> sm.getFacility()).orElse(null);
        model.addAttribute("totalPrescriptions", fac != null ? prescriptionService.countByFacilityId(fac.getId()) : 0);
        model.addAttribute("activePrescriptions", fac != null ? prescriptionService.countByFacilityIdAndStatus(fac.getId(), "ACTIVE") : 0);
        java.util.List<za.co.nmas.nmas.entity.MedicationInventory> mine = fac != null
                ? inventoryRepository.findByFacilityIdOrderByMedicationNameAsc(fac.getId()) : java.util.List.of();
        model.addAttribute("myFacility", fac);
        model.addAttribute("totalMedications", mine.size());
        model.addAttribute("lowStockCount", mine.stream().filter(m -> m.getQuantityInStock() <= m.getReorderLevel()).count());
        model.addAttribute("pendingDispensaryOrders", dispensaryService.countPending(fac != null ? fac.getId() : null));
        // Full inventory list for pack+pills overview table
        model.addAttribute("inventoryList", mine);
        if (medSearch != null && !medSearch.isBlank()) {
            model.addAttribute("medResults", fac != null
                    ? inventoryRepository.findByFacilityIdAndMedicationNameContainingIgnoreCase(fac.getId(), medSearch)
                    : java.util.List.of());
            model.addAttribute("medSearch", medSearch);
        }
        return "pharmacist-dashboard";
    }

    @GetMapping("/facility-admin-dashboard")
    public String facilityAdminDashboard(Authentication auth, Model model) {
        addStaffMember(auth, model);
        // Every number on this page is for the admin's OWN facility only.
        za.co.nmas.nmas.entity.Facility facility = staffMemberRepository.findByEmail(auth.getName())
                .map(sm -> sm.getFacility()).orElse(null);
        model.addAttribute("myFacility", facility);
        if (facility != null) {
            Long fid = facility.getId();
            model.addAttribute("totalDoctors", doctorService.findAll().stream()
                    .filter(d -> d.getFacility() != null && d.getFacility().getId().equals(fid)).count());
            model.addAttribute("totalNurses", staffMemberRepository.findByFacilityIdAndRole(fid, "NURSE").size());
            model.addAttribute("totalPharmacists", staffMemberRepository.findByFacilityIdAndRole(fid, "PHARMACIST").size());
            model.addAttribute("totalReceptionists", staffMemberRepository.findByFacilityIdAndRole(fid, "RECEPTIONIST").size());
            model.addAttribute("totalPatients", patientService.findAll().stream()
                    .filter(p -> p.getRegisteredFacility() != null && p.getRegisteredFacility().getId().equals(fid)).count());
            java.util.List<za.co.nmas.nmas.entity.Appointment> apps = appointmentService.findAll().stream()
                    .filter(a -> (a.getFacility() != null && a.getFacility().getId().equals(fid))
                            || (a.getFacility() == null && a.getDoctor() != null && a.getDoctor().getFacility() != null
                                && a.getDoctor().getFacility().getId().equals(fid)))
                    .toList();
            model.addAttribute("totalAppointments", apps.size());
            model.addAttribute("completedAppointments", apps.stream().filter(a -> "COMPLETED".equals(a.getStatus())).count());
        } else {
            model.addAttribute("totalDoctors", 0);
            model.addAttribute("totalNurses", 0);
            model.addAttribute("totalPharmacists", 0);
            model.addAttribute("totalReceptionists", 0);
            model.addAttribute("totalPatients", 0);
            model.addAttribute("totalAppointments", 0);
            model.addAttribute("completedAppointments", 0);
        }
        // Doctors from other facilities who viewed this facility's patients
        if (facility != null) {
            model.addAttribute("crossFacilityAccessLog", recordAccessService.findAccessLogForFacility(facility.getId()));
            model.addAttribute("crossFacilityDispensingLog", dispensaryService.findCrossFacilityDispensingForHomeFacility(facility.getId()));
        }
        return "facility-admin-dashboard";
    }

    @GetMapping("/system-admin-dashboard")
    public String systemAdminDashboard(@org.springframework.web.bind.annotation.RequestParam(required = false) Long facilityId,
                                       Authentication auth, Model model) {
        addStaffMember(auth, model);
        za.co.nmas.nmas.entity.Facility chosen = facilityService.findById(facilityId);
        model.addAttribute("facilities", facilityService.findAll());
        model.addAttribute("chosenFacility", chosen);
        model.addAttribute("facilityId", chosen != null ? chosen.getId() : null);
        if (chosen == null) {
            model.addAttribute("totalFacilities", facilityService.count());
            model.addAttribute("totalDoctors", doctorService.count());
            model.addAttribute("totalPatients", patientService.count());
            model.addAttribute("totalAppointments", appointmentService.count());
            model.addAttribute("totalConsultations", consultationService.count());
            model.addAttribute("totalPrescriptions", prescriptionService.count());
            model.addAttribute("totalNurses", staffMemberRepository.countByRole("NURSE"));
            model.addAttribute("totalPharmacists", staffMemberRepository.countByRole("PHARMACIST"));
            model.addAttribute("totalReceptionists", staffMemberRepository.countByRole("RECEPTIONIST"));
            model.addAttribute("completedAppointments", appointmentService.countByStatus("COMPLETED"));
            model.addAttribute("crossFacilityAccessLog", recordAccessService.findAccessLogAll());
        } else {
            Long fid = chosen.getId();
            model.addAttribute("totalFacilities", 1);
            model.addAttribute("totalDoctors", doctorService.findAll().stream()
                    .filter(d -> d.getFacility() != null && d.getFacility().getId().equals(fid)).count());
            model.addAttribute("totalPatients", patientService.findAll().stream()
                    .filter(p -> p.getRegisteredFacility() != null && p.getRegisteredFacility().getId().equals(fid)).count());
            java.util.List<za.co.nmas.nmas.entity.Appointment> apps = facilityAppointments(fid);
            model.addAttribute("totalAppointments", apps.size());
            model.addAttribute("completedAppointments", apps.stream().filter(x -> "COMPLETED".equals(x.getStatus())).count());
            model.addAttribute("totalConsultations", consultationService.findAll().stream()
                    .filter(c -> c.getAppointment() != null && c.getAppointment().getDoctor() != null
                            && c.getAppointment().getDoctor().getFacility() != null
                            && c.getAppointment().getDoctor().getFacility().getId().equals(fid)).count());
            model.addAttribute("totalPrescriptions", prescriptionService.findAll().stream()
                    .filter(p -> p.getDoctor() != null && p.getDoctor().getFacility() != null
                            && p.getDoctor().getFacility().getId().equals(fid)).count());
            model.addAttribute("totalNurses", staffMemberRepository.findByFacilityIdAndRole(fid, "NURSE").size());
            model.addAttribute("totalPharmacists", staffMemberRepository.findByFacilityIdAndRole(fid, "PHARMACIST").size());
            model.addAttribute("totalReceptionists", staffMemberRepository.findByFacilityIdAndRole(fid, "RECEPTIONIST").size());
            model.addAttribute("crossFacilityAccessLog", recordAccessService.findAccessLogForFacility(fid));
        }
        return "system-admin-dashboard";
    }

    @GetMapping("/receptionist-dashboard")
    public String receptionistDashboard(Authentication auth, Model model) {
        addStaffMember(auth, model);
        za.co.nmas.nmas.entity.Facility fac = staffMemberRepository.findByEmail(auth.getName())
                .map(sm -> sm.getFacility()).orElse(null);
        java.util.List<za.co.nmas.nmas.entity.Appointment> apps = fac != null ? facilityAppointments(fac.getId()) : java.util.List.of();
        model.addAttribute("queueSize", fac != null ? walkInVisitService.activeAtFacility(fac.getId()).size() : 0);
        model.addAttribute("totalAppointments", apps.size());
        model.addAttribute("pendingAppointments", apps.stream().filter(x -> "PENDING".equals(x.getStatus())).count());
        model.addAttribute("approvedAppointments", apps.stream().filter(x -> "APPROVED".equals(x.getStatus())).count());
        model.addAttribute("completedAppointments", apps.stream().filter(x -> "COMPLETED".equals(x.getStatus())).count());
        return "receptionist-dashboard";
    }

    @GetMapping("/patient-dashboard")
    public String patientDashboard(Authentication auth, Model model) {
        String loginEmail = auth.getName();
        model.addAttribute("currentUser", loginEmail);
        // Try direct email match first, then fall back to searching by nmas.ac.za generated email
        java.util.Optional<za.co.nmas.nmas.entity.Patient> patientOpt =
                patientService.findByEmail(loginEmail);

        // If not found directly (system-generated email), search all patients for one
        // whose generated email matches (stored in patients.email after registration)
        if (patientOpt.isEmpty()) {
            patientOpt = patientService.findAll().stream()
                    .filter(p -> loginEmail.equals(p.getEmail()))
                    .findFirst();
        }

        patientOpt.ifPresent(patient -> {
            Long patientId = patient.getId();
            model.addAttribute("patient", patient);
            model.addAttribute("appointments", appointmentService.findByPatientId(patientId));
            model.addAttribute("consultations", consultationService.findByPatientId(patientId));
            model.addAttribute("prescriptions", prescriptionService.findByPatientId(patientId));
            // Who has viewed this patient's records from outside their home facility
            model.addAttribute("crossFacilityAccessLog", recordAccessService.findAccessLogForPatient(patientId));
        });
        return "patient-dashboard";
    }
}
