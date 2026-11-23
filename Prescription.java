package za.co.nmas.nmas.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "prescriptions")
public class Prescription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDate prescriptionDate = LocalDate.now();

    // nullable — new prescriptions use PrescriptionMedication lines instead
    private String medicationName;
    private String dosage;
    private String frequency;
    private String duration;
    private String instructions;

    @Column(nullable = false)
    private String status = "ACTIVE";

    /** e.g. RX-0327-0004: RX - the doctor's 4-digit code - that doctor's running count. Assigned when issued. */
    @Column(unique = true)
    private String prescriptionNumber;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;
    private String updatedBy;

    @PrePersist
    protected void onCreate() { createdAt = LocalDateTime.now(); updatedAt = LocalDateTime.now(); }
    @PreUpdate
    protected void onUpdate() { updatedAt = LocalDateTime.now(); }

    @ManyToOne
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @ManyToOne
    @JoinColumn(name = "doctor_id")
    private Doctor doctor;

    @ManyToOne
    @JoinColumn(name = "visit_id")
    private Visit visit;

    @ManyToOne
    @JoinColumn(name = "consultation_id")
    private Consultation consultation;

    @OneToMany(mappedBy = "prescription", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PrescriptionMedication> medicationLines = new ArrayList<>();

    @OneToMany(mappedBy = "prescription", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PrescriptionNote> notes = new ArrayList<>();

    public Prescription() {}

    public Long getId() { return id; }

    public LocalDate getPrescriptionDate() { return prescriptionDate; }
    public void setPrescriptionDate(LocalDate prescriptionDate) { this.prescriptionDate = prescriptionDate; }

    public String getMedicationName() { return medicationName; }
    public void setMedicationName(String medicationName) { this.medicationName = medicationName; }

    public String getDosage() { return dosage; }
    public void setDosage(String dosage) { this.dosage = dosage; }

    public String getFrequency() { return frequency; }
    public void setFrequency(String frequency) { this.frequency = frequency; }

    public String getDuration() { return duration; }
    public void setDuration(String duration) { this.duration = duration; }

    public String getInstructions() { return instructions; }
    public void setInstructions(String instructions) { this.instructions = instructions; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Patient getPatient() { return patient; }
    public void setPatient(Patient patient) { this.patient = patient; }

    public Doctor getDoctor() { return doctor; }
    public void setDoctor(Doctor doctor) { this.doctor = doctor; }

    public Visit getVisit() { return visit; }
    public void setVisit(Visit visit) { this.visit = visit; }

    public Consultation getConsultation() { return consultation; }
    public void setConsultation(Consultation consultation) { this.consultation = consultation; }

    public List<PrescriptionMedication> getMedicationLines() { return medicationLines; }
    public List<PrescriptionNote> getNotes() { return notes; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime v) { this.updatedAt = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String v) { this.updatedBy = v; }

    public String getPrescriptionNumber() { return prescriptionNumber; }
    public void setPrescriptionNumber(String prescriptionNumber) { this.prescriptionNumber = prescriptionNumber; }

    /** Last day this prescription may be collected/dispensed. */
    private java.time.LocalDate validUntil;

    public java.time.LocalDate getValidUntil() { return validUntil; }
    public void setValidUntil(java.time.LocalDate validUntil) { this.validUntil = validUntil; }

    /** Old prescriptions without a validity date are treated as valid for 30 days from issue. */
    public java.time.LocalDate getEffectiveValidUntil() {
        if (validUntil != null) return validUntil;
        java.time.LocalDate base = prescriptionDate != null ? prescriptionDate : java.time.LocalDate.now();
        return base.plusDays(30);
    }

    public boolean isExpired() { return java.time.LocalDate.now().isAfter(getEffectiveValidUntil()); }

    public long getDaysLeft() {
        return java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), getEffectiveValidUntil());
    }

    public long getValidityDays() {
        java.time.LocalDate base = prescriptionDate != null ? prescriptionDate : java.time.LocalDate.now();
        return java.time.temporal.ChronoUnit.DAYS.between(base, getEffectiveValidUntil());
    }

    /** The number to show anywhere: the real one, or the old RX-<id> for prescriptions issued before numbering existed. */
    public String getDisplayNumber() {
        return prescriptionNumber != null ? prescriptionNumber : "RX-" + id;
    }

    /** The facility that issued this prescription (the prescribing doctor's facility). */
    public Facility getIssuingFacility() {
        return doctor != null ? doctor.getFacility() : null;
    }
}
