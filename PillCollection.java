package za.co.nmas.nmas.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A repeat ("chronic") prescription's monthly pill pick-up. Patients get a
 * short window at the start of each month — the first four days — to collect;
 * miss it and that month's note is no longer valid.
 *
 * Status: DUE -> CHECKED_IN (reception confirmed the patient is due today)
 *         -> COLLECTED (pharmacist handed the pills over)   |   EXPIRED (window passed).
 */
@Entity
@Table(name = "pill_collections",
       uniqueConstraints = @UniqueConstraint(columnNames = {"prescription_id", "windowStart"}))
public class PillCollection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "prescription_id", nullable = false)
    private Prescription prescription;

    @ManyToOne(optional = false)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    /** The facility the patient collects from — the one that issued the prescription. */
    @ManyToOne
    @JoinColumn(name = "facility_id")
    private Facility facility;

    @Column(nullable = false)
    private LocalDate windowStart;

    @Column(nullable = false)
    private LocalDate windowEnd;

    @Column(nullable = false)
    private String status = "DUE";

    private LocalDateTime checkedInAt;
    private String checkedInBy;
    private LocalDateTime collectedAt;
    private String collectedBy;

    public boolean isOpenToday() {
        LocalDate t = LocalDate.now();
        return !t.isBefore(windowStart) && !t.isAfter(windowEnd);
    }

    public Long getId() { return id; }
    public Prescription getPrescription() { return prescription; }
    public void setPrescription(Prescription p) { this.prescription = p; }
    public Patient getPatient() { return patient; }
    public void setPatient(Patient p) { this.patient = p; }
    public Facility getFacility() { return facility; }
    public void setFacility(Facility f) { this.facility = f; }
    public LocalDate getWindowStart() { return windowStart; }
    public void setWindowStart(LocalDate d) { this.windowStart = d; }
    public LocalDate getWindowEnd() { return windowEnd; }
    public void setWindowEnd(LocalDate d) { this.windowEnd = d; }
    public String getStatus() { return status; }
    public void setStatus(String s) { this.status = s; }
    public LocalDateTime getCheckedInAt() { return checkedInAt; }
    public void setCheckedInAt(LocalDateTime d) { this.checkedInAt = d; }
    public String getCheckedInBy() { return checkedInBy; }
    public void setCheckedInBy(String s) { this.checkedInBy = s; }
    public LocalDateTime getCollectedAt() { return collectedAt; }
    public void setCollectedAt(LocalDateTime d) { this.collectedAt = d; }
    public String getCollectedBy() { return collectedBy; }
    public void setCollectedBy(String s) { this.collectedBy = s; }
}
