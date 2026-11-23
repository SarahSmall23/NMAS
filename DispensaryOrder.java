package za.co.nmas.nmas.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "dispensary_orders")
public class DispensaryOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "prescription_id", nullable = false)
    private Prescription prescription;

    @ManyToOne
    @JoinColumn(name = "prescription_medication_id")
    private PrescriptionMedication prescriptionMedication;

    @ManyToOne
    @JoinColumn(name = "medication_id")
    private Medication medication;

    @ManyToOne
    @JoinColumn(name = "inventory_item_id")
    private MedicationInventory inventoryItem;

    @Column(nullable = false)
    private String medicationName;

    private String patientName;
    private String dosage;
    private String frequency;
    private String duration;

    private Integer suggestedQuantity;
    private Integer dispensedQuantity;

    /** How many whole packs/boxes to dispense — calculated from suggestedQuantity (pills needed) and the medication's pillsPerPack, rounded up so the patient always gets at least enough. */
    private Integer suggestedPacks;
    private Integer dispensedPacks;

    @Column(nullable = false)
    private String status; // PENDING, DISPENSED, REJECTED, OUT_OF_STOCK

    @Column(length = 500)
    private String pharmacistNotes;

    private String dispensedBy;
    private LocalDateTime dispensedAt;

    /** The facility whose pharmacist actually dispensed this — set at dispense time from the pharmacist's own facility.
     *  When this differs from the patient's registered facility, their home facility is notified, so chronic
     *  medication (e.g. ARVs) collected elsewhere doesn't get unknowingly dispensed twice. */
    @ManyToOne
    @JoinColumn(name = "dispensing_facility_id")
    private Facility dispensingFacility;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(); }

    public DispensaryOrder() {}

    public Long getId() { return id; }
    public Prescription getPrescription() { return prescription; }
    public void setPrescription(Prescription prescription) { this.prescription = prescription; }
    public PrescriptionMedication getPrescriptionMedication() { return prescriptionMedication; }
    public void setPrescriptionMedication(PrescriptionMedication pm) { this.prescriptionMedication = pm; }
    public Medication getMedication() { return medication; }
    public void setMedication(Medication medication) { this.medication = medication; }
    public MedicationInventory getInventoryItem() { return inventoryItem; }
    public void setInventoryItem(MedicationInventory inventoryItem) { this.inventoryItem = inventoryItem; }
    public String getMedicationName() { return medicationName; }
    public void setMedicationName(String medicationName) { this.medicationName = medicationName; }
    public String getPatientName() { return patientName; }
    public void setPatientName(String patientName) { this.patientName = patientName; }
    public String getDosage() { return dosage; }
    public void setDosage(String dosage) { this.dosage = dosage; }
    public String getFrequency() { return frequency; }
    public void setFrequency(String frequency) { this.frequency = frequency; }
    public String getDuration() { return duration; }
    public void setDuration(String duration) { this.duration = duration; }
    public Integer getSuggestedQuantity() { return suggestedQuantity; }
    public void setSuggestedQuantity(Integer suggestedQuantity) { this.suggestedQuantity = suggestedQuantity; }
    public Integer getDispensedQuantity() { return dispensedQuantity; }
    public void setDispensedQuantity(Integer dispensedQuantity) { this.dispensedQuantity = dispensedQuantity; }
    public Integer getSuggestedPacks() { return suggestedPacks; }
    public void setSuggestedPacks(Integer suggestedPacks) { this.suggestedPacks = suggestedPacks; }
    public Integer getDispensedPacks() { return dispensedPacks; }
    public void setDispensedPacks(Integer dispensedPacks) { this.dispensedPacks = dispensedPacks; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPharmacistNotes() { return pharmacistNotes; }
    public void setPharmacistNotes(String pharmacistNotes) { this.pharmacistNotes = pharmacistNotes; }
    public String getDispensedBy() { return dispensedBy; }
    public void setDispensedBy(String dispensedBy) { this.dispensedBy = dispensedBy; }
    public LocalDateTime getDispensedAt() { return dispensedAt; }
    public void setDispensedAt(LocalDateTime dispensedAt) { this.dispensedAt = dispensedAt; }

    public Facility getDispensingFacility() { return dispensingFacility; }
    public void setDispensingFacility(Facility dispensingFacility) { this.dispensingFacility = dispensingFacility; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
