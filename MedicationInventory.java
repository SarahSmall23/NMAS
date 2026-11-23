package za.co.nmas.nmas.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "medication_inventory")
public class MedicationInventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = true)
    @JoinColumn(name = "medication_id")
    private Medication medication;

    @Column(nullable = false)
    private String medicationName;

    private String genericName;

    private String category;

    @Column(nullable = false)
    private String unit;

    @Column(nullable = false)
    private int quantityInStock;

    @Column(nullable = false)
    private int reorderLevel;

    private String supplier;

    /**
     * Pills per pack for items added directly on this page (not linked to a
     * formulary Medication). When this inventory row IS linked to a
     * Medication, that entity's pillsPerPack takes precedence — see
     * getEffectivePillsPerPack(). Null/absent means this item isn't tracked
     * in packs (e.g. Vials, Inhalers, mL — dispensed as raw units).
     */
    private Integer pillsPerPack;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime lastRestockedAt;

    /** Expiry date of the stock currently on the shelf, and an optional batch/lot reference. */
    private LocalDate expiryDate;
    private String batchNumber;
    /** Set once the "expires within 6 months" notification has gone out, so it is sent once per batch. */
    private LocalDate expiryAlertSentFor;

    /** Each facility keeps its own stock: the facility whose pharmacy holds this row. */
    @ManyToOne
    @JoinColumn(name = "facility_id")
    private Facility facility;

    /** True once the one-time "full opening stock" baseline has been applied to this row. */
    private Boolean baselineApplied = Boolean.FALSE;

    public Boolean getBaselineApplied() { return baselineApplied; }
    public void setBaselineApplied(Boolean v) { this.baselineApplied = v; }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public MedicationInventory() {}

    public Long getId() { return id; }

    public Medication getMedication() { return medication; }
    public void setMedication(Medication medication) { this.medication = medication; }

    public String getMedicationName() { return medicationName; }
    public void setMedicationName(String medicationName) { this.medicationName = medicationName; }

    public String getGenericName() { return genericName; }
    public void setGenericName(String genericName) { this.genericName = genericName; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }

    public int getQuantityInStock() { return quantityInStock; }
    public void setQuantityInStock(int quantityInStock) { this.quantityInStock = quantityInStock; }

    public int getReorderLevel() { return reorderLevel; }
    public void setReorderLevel(int reorderLevel) { this.reorderLevel = reorderLevel; }

    public String getSupplier() { return supplier; }
    public void setSupplier(String supplier) { this.supplier = supplier; }

    public Integer getPillsPerPack() { return pillsPerPack; }
    public void setPillsPerPack(Integer pillsPerPack) { this.pillsPerPack = pillsPerPack; }

    /** The pack size actually in effect: the linked formulary Medication's value takes priority. */
    public Integer getEffectivePillsPerPack() {
        if (medication != null && medication.getPillsPerPack() != null) {
            return medication.getPillsPerPack();
        }
        return pillsPerPack;
    }

    public boolean isPackTracked() {
        return getEffectivePillsPerPack() != null && getEffectivePillsPerPack() > 0;
    }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public LocalDateTime getLastRestockedAt() { return lastRestockedAt; }
    public void setLastRestockedAt(LocalDateTime lastRestockedAt) { this.lastRestockedAt = lastRestockedAt; }

    public boolean isLowStock() {
        return quantityInStock > 0 && quantityInStock <= reorderLevel;
    }

    public boolean isOutOfStock() {
        return quantityInStock == 0;
    }

    public LocalDate getExpiryDate() { return expiryDate; }
    public void setExpiryDate(LocalDate expiryDate) { this.expiryDate = expiryDate; }
    public String getBatchNumber() { return batchNumber; }
    public void setBatchNumber(String batchNumber) { this.batchNumber = batchNumber; }
    public LocalDate getExpiryAlertSentFor() { return expiryAlertSentFor; }
    public void setExpiryAlertSentFor(LocalDate d) { this.expiryAlertSentFor = d; }

    public boolean isExpired() { return expiryDate != null && expiryDate.isBefore(LocalDate.now()); }

    /** True when this stock can neither be prescribed nor dispensed: it has expired or run out. */
    public boolean isUnavailable() { return isExpired() || isOutOfStock(); }

    /** Plain-language reason this stock is unavailable, or null when it is fine to use. */
    public String getUnavailableReason() {
        if (isExpired()) return "expired on " + expiryDate;
        if (isOutOfStock()) return "out of stock";
        return null;
    }

    /** Within the 6-month warning window (and not yet expired). */
    public boolean isExpiringSoon() {
        return expiryDate != null && !isExpired() && !expiryDate.isAfter(LocalDate.now().plusMonths(6));
    }

    /** Within 3 months — the urgent end of the warning window. */
    public boolean isExpiringVerySoon() {
        return expiryDate != null && !isExpired() && !expiryDate.isAfter(LocalDate.now().plusMonths(3));
    }

    public Long getDaysToExpiry() {
        return expiryDate == null ? null : ChronoUnit.DAYS.between(LocalDate.now(), expiryDate);
    }

    /** One word for colour-coding: EXPIRED, OUT, EXPIRING, LOW, OK. Worst problem wins. */
    public String getHealthStatus() {
        if (isExpired()) return "EXPIRED";
        if (isOutOfStock()) return "OUT";
        if (isExpiringSoon()) return "EXPIRING";
        if (isLowStock()) return "LOW";
        return "OK";
    }

    public Facility getFacility() { return facility; }
    public void setFacility(Facility facility) { this.facility = facility; }
}
