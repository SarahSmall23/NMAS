package za.co.nmas.nmas.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * One patient's trip through a facility: recorded by reception, vitals taken
 * by a nurse, then sent to a doctor. Each facility only ever sees its own.
 *
 * Status flow: WAITING_VITALS -> WAITING_DOCTOR -> IN_CONSULTATION -> COMPLETED
 * (or CANCELLED at any point before the consultation starts).
 */
@Entity
@Table(name = "walk_in_visits")
public class WalkInVisit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @ManyToOne(optional = false)
    @JoinColumn(name = "facility_id", nullable = false)
    private Facility facility;

    /** WALK_IN, or APPOINTMENT when the patient is checking in for a booked (follow-up) appointment. */
    @Column(nullable = false)
    private String visitType = "WALK_IN";

    /** What the patient came for: General Practitioner, Neurology, Cardiology, ... */
    private String requestedService;

    @Column(length = 500)
    private String reason;

    @ManyToOne
    @JoinColumn(name = "appointment_id")
    private Appointment appointment;

    @Column(nullable = false)
    private String status = "WAITING_VITALS";

    @Column(nullable = false, updatable = false)
    private LocalDateTime arrivalTime;

    private String registeredBy;

    // ---- vitals (recorded by the nurse) ----
    private Integer bpSystolic;
    private Integer bpDiastolic;
    private Integer heartRate;
    private Double temperature;
    private Double weightKg;
    private Double heightCm;
    private Integer oxygenSaturation;
    private Double bloodGlucose;
    private String hivStatus;
    @Column(length = 1000)
    private String nurseNotes;
    private String vitalsRecordedBy;
    private LocalDateTime vitalsRecordedAt;

    // ---- hand-over to doctor ----
    @ManyToOne
    @JoinColumn(name = "doctor_id")
    private Doctor assignedDoctor;
    private LocalDateTime sentToDoctorAt;
    private LocalDateTime consultationStartedAt;

    @PrePersist
    protected void onCreate() {
        if (arrivalTime == null) arrivalTime = LocalDateTime.now();
    }

    public Double getBmi() {
        if (weightKg == null || heightCm == null || heightCm <= 0) return null;
        double m = heightCm / 100.0;
        return Math.round((weightKg / (m * m)) * 10.0) / 10.0;
    }

    public String getBloodPressure() {
        return (bpSystolic != null && bpDiastolic != null) ? bpSystolic + "/" + bpDiastolic : null;
    }

    public boolean isVitalsRecorded() { return vitalsRecordedAt != null; }

    /** Elderly patients (by ID number) are seen first. */
    public boolean isPriorityElderly() { return za.co.nmas.nmas.util.PatientPriority.isElderly(patient); }

    public Integer getPatientAge() { return za.co.nmas.nmas.util.PatientPriority.ageOf(patient); }

    public Long getId() { return id; }
    public Patient getPatient() { return patient; }
    public void setPatient(Patient patient) { this.patient = patient; }
    public Facility getFacility() { return facility; }
    public void setFacility(Facility facility) { this.facility = facility; }
    public String getVisitType() { return visitType; }
    public void setVisitType(String visitType) { this.visitType = visitType; }
    public String getRequestedService() { return requestedService; }
    public void setRequestedService(String requestedService) { this.requestedService = requestedService; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Appointment getAppointment() { return appointment; }
    public void setAppointment(Appointment appointment) { this.appointment = appointment; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getArrivalTime() { return arrivalTime; }
    public String getRegisteredBy() { return registeredBy; }
    public void setRegisteredBy(String registeredBy) { this.registeredBy = registeredBy; }
    public Integer getBpSystolic() { return bpSystolic; }
    public void setBpSystolic(Integer v) { this.bpSystolic = v; }
    public Integer getBpDiastolic() { return bpDiastolic; }
    public void setBpDiastolic(Integer v) { this.bpDiastolic = v; }
    public Integer getHeartRate() { return heartRate; }
    public void setHeartRate(Integer v) { this.heartRate = v; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double v) { this.temperature = v; }
    public Double getWeightKg() { return weightKg; }
    public void setWeightKg(Double v) { this.weightKg = v; }
    public Double getHeightCm() { return heightCm; }
    public void setHeightCm(Double v) { this.heightCm = v; }
    public Integer getOxygenSaturation() { return oxygenSaturation; }
    public void setOxygenSaturation(Integer v) { this.oxygenSaturation = v; }
    public Double getBloodGlucose() { return bloodGlucose; }
    public void setBloodGlucose(Double v) { this.bloodGlucose = v; }
    public String getHivStatus() { return hivStatus; }
    public void setHivStatus(String v) { this.hivStatus = v; }
    public String getNurseNotes() { return nurseNotes; }
    public void setNurseNotes(String v) { this.nurseNotes = v; }
    public String getVitalsRecordedBy() { return vitalsRecordedBy; }
    public void setVitalsRecordedBy(String v) { this.vitalsRecordedBy = v; }
    public LocalDateTime getVitalsRecordedAt() { return vitalsRecordedAt; }
    public void setVitalsRecordedAt(LocalDateTime v) { this.vitalsRecordedAt = v; }
    public Doctor getAssignedDoctor() { return assignedDoctor; }
    public void setAssignedDoctor(Doctor d) { this.assignedDoctor = d; }
    public LocalDateTime getSentToDoctorAt() { return sentToDoctorAt; }
    public void setSentToDoctorAt(LocalDateTime v) { this.sentToDoctorAt = v; }
    public LocalDateTime getConsultationStartedAt() { return consultationStartedAt; }
    public void setConsultationStartedAt(LocalDateTime v) { this.consultationStartedAt = v; }
}
