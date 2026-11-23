package za.co.nmas.nmas.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "medications")
public class Medication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String medicationCode;

    @Column(nullable = false)
    private String medicationName;

    private String genericName;
    private String dosageForm;
    private String strength;
    private String manufacturer;
    private boolean requiresPrescription;
    private String stockStatus = "IN_STOCK";
    private boolean active = true;

    /** Number of individual pills/units in one pack/box of this medicine, as sold/stocked (e.g. Amoxicillin 500mg comes in packs of 21). Used so the pharmacist dispenses whole packs instead of counting out loose pills. */
    private Integer pillsPerPack;

    public Medication() {}

    public Long getId() { return id; }
    public String getMedicationCode() { return medicationCode; }
    public void setMedicationCode(String medicationCode) { this.medicationCode = medicationCode; }
    public String getMedicationName() { return medicationName; }
    public void setMedicationName(String medicationName) { this.medicationName = medicationName; }
    public String getGenericName() { return genericName; }
    public void setGenericName(String genericName) { this.genericName = genericName; }
    public String getDosageForm() { return dosageForm; }
    public void setDosageForm(String dosageForm) { this.dosageForm = dosageForm; }
    public String getStrength() { return strength; }
    public void setStrength(String strength) { this.strength = strength; }
    public String getManufacturer() { return manufacturer; }
    public void setManufacturer(String manufacturer) { this.manufacturer = manufacturer; }
    public boolean isRequiresPrescription() { return requiresPrescription; }
    public void setRequiresPrescription(boolean requiresPrescription) { this.requiresPrescription = requiresPrescription; }
    public String getStockStatus() { return stockStatus; }
    public void setStockStatus(String stockStatus) { this.stockStatus = stockStatus; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Integer getPillsPerPack() { return pillsPerPack; }
    public void setPillsPerPack(Integer pillsPerPack) { this.pillsPerPack = pillsPerPack; }

    public String getDisplayName() {
        return medicationName + (strength != null ? " " + strength : "") + (dosageForm != null ? " (" + dosageForm + ")" : "");
    }
}
