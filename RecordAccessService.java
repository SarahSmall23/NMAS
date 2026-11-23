package za.co.nmas.nmas.service;

import org.springframework.stereotype.Service;
import za.co.nmas.nmas.entity.Doctor;
import za.co.nmas.nmas.entity.Patient;
import za.co.nmas.nmas.entity.RecordAccessRequest;
import za.co.nmas.nmas.entity.StaffMember;
import za.co.nmas.nmas.entity.Facility;
import za.co.nmas.nmas.repository.DoctorRepository;
import za.co.nmas.nmas.repository.FacilityRepository;
import za.co.nmas.nmas.repository.PatientRepository;
import za.co.nmas.nmas.repository.RecordAccessRequestRepository;
import za.co.nmas.nmas.repository.StaffMemberRepository;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Cross-facility medical record access, as an audit trail rather than a
 * gate: a doctor can always open a patient's full history -- including in
 * an emergency, no waiting on anyone -- but if the doctor's own facility
 * differs from where the patient is registered, that view is logged and
 * the patient's home facility admin is notified immediately (who, when,
 * from which facility), so a data leak can always be traced back to
 * exactly who looked.
 */
@Service
public class RecordAccessService {

    private static final String FACILITY_ADMIN_ROLE = "FACILITY_ADMIN";
    private static final DateTimeFormatter WHEN_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy 'at' HH:mm");

    private final RecordAccessRequestRepository repository;
    private final StaffMemberRepository staffMemberRepository;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;
    private final DoctorRepository doctorRepository;
    private final FacilityRepository facilityRepository;
    private final PatientRepository patientRepository;

    public RecordAccessService(RecordAccessRequestRepository repository,
                                StaffMemberRepository staffMemberRepository,
                                NotificationService notificationService,
                                AuditLogService auditLogService,
                                DoctorRepository doctorRepository,
                                FacilityRepository facilityRepository,
                                PatientRepository patientRepository) {
        this.doctorRepository = doctorRepository;
        this.facilityRepository = facilityRepository;
        this.patientRepository = patientRepository;
        this.repository = repository;
        this.staffMemberRepository = staffMemberRepository;
        this.notificationService = notificationService;
        this.auditLogService = auditLogService;
    }

    public boolean isCrossFacility(Patient patient, Doctor doctor) {
        if (patient == null || doctor == null) return false;
        if (patient.getRegisteredFacility() == null) return false;
        if (doctor.getFacility() == null) return true;
        return !doctor.getFacility().getId().equals(patient.getRegisteredFacility().getId());
    }

    /**
     * Call this every time a doctor opens a patient's full history. Does
     * nothing if the doctor is at the patient's own registered facility.
     * Otherwise, records the access and notifies every facility-admin at
     * the patient's home facility -- non-blocking, the doctor's view is
     * never delayed by this.
     */
    public void logAccessIfCrossFacility(Patient patient, Doctor doctor) {
        if (!isCrossFacility(patient, doctor)) return;

        RecordAccessRequest log = new RecordAccessRequest();
        log.setPatient(patient);
        log.setDoctor(doctor);
        log.setRequestingFacility(doctor.getFacility());
        log.setHomeFacility(patient.getRegisteredFacility());
        log.setStatus("VIEWED");
        RecordAccessRequest saved = repository.save(log);

        String doctorFacilityName = doctor.getFacility() != null ? doctor.getFacility().getFacilityName() : "an unlisted facility";

        auditLogService.log("CROSS_FACILITY_RECORD_VIEWED", "RecordAccessRequest", saved.getId(),
                "Dr. " + doctor.getFullName() + " (" + doctorFacilityName + ") viewed "
                        + patient.getFullName() + "'s full medical history -- registered at "
                        + patient.getRegisteredFacility().getFacilityName());

        List<StaffMember> admins = staffMemberRepository.findByFacilityIdAndRole(
                patient.getRegisteredFacility().getId(), FACILITY_ADMIN_ROLE);
        for (StaffMember admin : admins) {
            if (admin.getEmail() == null) continue;
            notificationService.notify(admin.getEmail(), "CROSS_FACILITY_ACCESS",
                    "Dr. " + doctor.getFullName() + " from " + doctorFacilityName
                            + " accessed " + patient.getFullName() + "'s medical history on "
                            + WHEN_FORMAT.format(saved.getRequestedAt()) + ".",
                    "/facility-admin-dashboard");
        }
    }

    /**
     * The patient's home facility: the registered facility, or -- for older
     * rows that only have the free-text preferred facility -- the facility
     * whose name matches it (and the link is saved so it is only done once).
     */
    public Facility resolveHomeFacility(Patient patient) {
        if (patient == null) return null;
        if (patient.getRegisteredFacility() != null) return patient.getRegisteredFacility();
        String pref = patient.getPreferredFacility();
        if (pref == null || pref.isBlank()) return null;
        String want = pref.trim().toLowerCase();
        for (Facility f : facilityRepository.findAll()) {
            String name = f.getFacilityName() == null ? "" : f.getFacilityName().trim().toLowerCase();
            if (name.equals(want) || name.contains(want) || want.contains(name) && !name.isEmpty()) {
                patient.setRegisteredFacility(f);
                try { patientRepository.save(patient); } catch (Exception ignored) { }
                return f;
            }
        }
        return null;
    }

    /**
     * Entry point used by every page that opens a patient's record. Works
     * out who is looking (doctor or nurse) from their login email, and logs
     * + notifies if their facility differs from the patient's home facility.
     * Never blocks and never throws -- a logging problem must not stop care.
     */
    public boolean logStaffView(Patient patient, String email, String role) {
        try {
            if (patient == null || email == null) return false;
            Facility home = resolveHomeFacility(patient);
            if (home == null) return false;

            Doctor doctor = null;
            Facility viewerFacility = null;
            String viewerName = null;
            String roleLabel;
            if ("DOCTOR".equals(role)) {
                doctor = doctorRepository.findByEmail(email).orElse(null);
                if (doctor == null) return false;
                viewerFacility = doctor.getFacility();
                viewerName = doctor.getFullName();
                roleLabel = "Doctor";
            } else if ("NURSE".equals(role)) {
                StaffMember sm = staffMemberRepository.findByEmail(email).orElse(null);
                if (sm == null) return false;
                viewerFacility = sm.getFacility();
                viewerName = sm.getFullName();
                roleLabel = "Nurse";
            } else {
                return false;
            }
            if (viewerFacility != null && viewerFacility.getId().equals(home.getId())) return false;

            // The same person refreshing the page must not spam the admin: one entry per viewer + patient per 2 minutes.
            java.time.LocalDateTime since = java.time.LocalDateTime.now().minusMinutes(2);
            for (RecordAccessRequest r : repository.findByPatientIdOrderByRequestedAtDesc(patient.getId())) {
                if (r.getRequestedAt() == null || r.getRequestedAt().isBefore(since)) break;
                boolean same = doctor != null
                        ? r.getDoctor() != null && r.getDoctor().getId().equals(doctor.getId())
                        : email.equalsIgnoreCase(r.getViewerEmail());
                if (same) return true;
            }

            RecordAccessRequest log = new RecordAccessRequest();
            log.setPatient(patient);
            log.setDoctor(doctor);
            log.setViewerName(viewerName);
            log.setViewerRole(roleLabel);
            log.setViewerEmail(email);
            log.setRequestingFacility(viewerFacility);
            log.setHomeFacility(home);
            log.setStatus("VIEWED");
            RecordAccessRequest saved = repository.save(log);

            String viewerFacilityName = viewerFacility != null ? viewerFacility.getFacilityName() : "an unlisted facility";
            String who = ("Doctor".equals(roleLabel) ? "Dr. " : "Nurse ") + viewerName;

            auditLogService.log("CROSS_FACILITY_RECORD_VIEWED", "RecordAccessRequest", saved.getId(),
                    who + " (" + viewerFacilityName + ") viewed " + patient.getFullName()
                            + "'s medical history -- registered at " + home.getFacilityName());

            for (StaffMember admin : staffMemberRepository.findByFacilityIdAndRole(home.getId(), FACILITY_ADMIN_ROLE)) {
                if (admin.getEmail() == null) continue;
                notificationService.notify(admin.getEmail(), "CROSS_FACILITY_ACCESS",
                        who + " from " + viewerFacilityName + " accessed " + patient.getFullName()
                                + "'s medical history on " + WHEN_FORMAT.format(saved.getRequestedAt()) + ".",
                        "/facility/access-log");
            }
            // The patient is told too, so they can see who looked at their file.
            if (patient.getEmail() != null) {
                notificationService.notify(patient.getEmail(), "CROSS_FACILITY_ACCESS",
                        who + " from " + viewerFacilityName + " viewed your medical history on "
                                + WHEN_FORMAT.format(saved.getRequestedAt()) + ".",
                        "/patient-dashboard");
            }
            return true;
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(RecordAccessService.class)
                    .warn("Cross-facility access logging failed: {}", e.toString());
            return false;
        }
    }

    public boolean isCrossFacilityStaff(Patient patient, String email, String role) {
        Facility home = resolveHomeFacility(patient);
        if (home == null || email == null) return false;
        Facility vf = null;
        if ("DOCTOR".equals(role)) vf = doctorRepository.findByEmail(email).map(Doctor::getFacility).orElse(null);
        else if ("NURSE".equals(role)) vf = staffMemberRepository.findByEmail(email).map(StaffMember::getFacility).orElse(null);
        else return false;
        return vf == null || !vf.getId().equals(home.getId());
    }

    /** Read-only audit trail for a facility admin: who from elsewhere has looked at their patients' records. */
    public List<RecordAccessRequest> findAccessLogForFacility(Long facilityId) {
        return repository.findByHomeFacilityIdOrderByRequestedAtDesc(facilityId);
    }

    /** National audit trail (system admin): every cross-facility view, newest first. */
    public List<RecordAccessRequest> findAccessLogAll() {
        return repository.findAll(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "requestedAt"));
    }

    /** Read-only audit trail for the patient themselves: who has viewed their records from outside their home facility. */
    public List<RecordAccessRequest> findAccessLogForPatient(Long patientId) {
        return repository.findByPatientIdOrderByRequestedAtDesc(patientId);
    }
}
