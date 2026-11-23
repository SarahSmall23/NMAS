package za.co.nmas.nmas.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * A record of a doctor viewing a patient's full medical history from a
 * facility other than the one the patient is registered at.
 *
 * NMAS never blocks a doctor from seeing a patient's history for
 * permission reasons — care (especially emergency care) shouldn't wait on
 * anyone's approval. Instead, every such cross-facility view is logged
 * here and the patient's home facility is notified immediately, so
 * there's always a clear, traceable record of who looked, from where,
 * and when.
 */
@Entity
@Table(name = "record_access_requests")
public class RecordAccessRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @ManyToOne
    @JoinColumn(name = "doctor_id")
    private Doctor doctor;

    /** Who viewed the record when they are not a doctor (e.g. a nurse): name, role, email. */
    private String viewerName;
    private String viewerRole;
    private String viewerEmail;

    /** The doctor's facility at the time of the request (kept even if the doctor later moves facility). */
    @ManyToOne
    @JoinColumn(name = "requesting_facility_id")
    private Facility requestingFacility;

    /** The patient's registered (home) facility at the time of the request — who the request is directed to. */
    @ManyToOne
    @JoinColumn(name = "home_facility_id")
    private Facility homeFacility;

    @Column(length = 500)
    private String reason;

    @Column(nullable = false)
    private String status = "PENDING"; // PENDING, APPROVED, DENIED

    /** Email of whoever decided it — the patient themselves, or a facility admin acting on their behalf. */
    private String decidedBy;

    /** How the decision was made, for the audit trail — "PATIENT" or "FACILITY_ADMIN". */
    private String decidedByRole;

    @Column(length = 500)
    private String decisionNote;

    @Column(nullable = false, updatable = false)
    private LocalDateTime requestedAt;

    private LocalDateTime decidedAt;

    @PrePersist
    protected void onCreate() {
        this.requestedAt = LocalDateTime.now();
    }

    public RecordAccessRequest() {}

    public Long getId() { return id; }

    public Patient getPatient() { return patient; }
    public void setPatient(Patient patient) { this.patient = patient; }

    public Doctor getDoctor() { return doctor; }
    public void setDoctor(Doctor doctor) { this.doctor = doctor; }

    public Facility getRequestingFacility() { return requestingFacility; }
    public void setRequestingFacility(Facility requestingFacility) { this.requestingFacility = requestingFacility; }

    public Facility getHomeFacility() { return homeFacility; }
    public void setHomeFacility(Facility homeFacility) { this.homeFacility = homeFacility; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getDecidedBy() { return decidedBy; }
    public void setDecidedBy(String decidedBy) { this.decidedBy = decidedBy; }

    public String getDecidedByRole() { return decidedByRole; }
    public void setDecidedByRole(String decidedByRole) { this.decidedByRole = decidedByRole; }

    public String getDecisionNote() { return decisionNote; }
    public void setDecisionNote(String decisionNote) { this.decisionNote = decisionNote; }

    public LocalDateTime getRequestedAt() { return requestedAt; }

    public LocalDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(LocalDateTime decidedAt) { this.decidedAt = decidedAt; }

    public String getViewerName() { return viewerName; }
    public void setViewerName(String v) { this.viewerName = v; }
    public String getViewerRole() { return viewerRole; }
    public void setViewerRole(String v) { this.viewerRole = v; }
    public String getViewerEmail() { return viewerEmail; }
    public void setViewerEmail(String v) { this.viewerEmail = v; }

    /** Display label used by every access-log page: "Dr. X" or "Nurse X". */
    public String getViewerLabel() {
        if (doctor != null) return "Dr. " + doctor.getFullName();
        String role = viewerRole != null ? viewerRole : "Staff";
        return role + " " + (viewerName != null ? viewerName : "");
    }

    public String getViewerDetail() {
        if (doctor != null) return doctor.getSpecialization() != null ? doctor.getSpecialization() : "Doctor";
        return viewerRole != null ? viewerRole : "Staff";
    }

    public boolean isViewed() { return "VIEWED".equals(status); }
}
