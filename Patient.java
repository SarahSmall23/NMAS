package za.co.nmas.nmas.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "patients")
public class Patient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false)
    private String fullName;

    @Column(unique = true)
    private String idNumber;

    @Column(unique = true)
    private String passportNumber;

    private LocalDate dateOfBirth;

    @Column(nullable = false)
    private String gender;

    private String ethnicity;
    private String nationality;

    @Email
    @Column(unique = true)
    private String email;

    private String phoneNumber;
    private String alternativePhoneNumber;

    private String address;
    private String city;
    private String province;
    private String postalCode;

    private String nextOfKinName;
    private String nextOfKinRelationship;
    private String nextOfKinPhone;

    private String emergencyContactName;
    private String emergencyContactPhone;

    private String bloodType;

    @Column(length = 1000)
    private String allergies;

    @Column(length = 1000)
    private String chronicConditions;

    @Column(length = 1000)
    private String currentMedication;

    @Column(length = 1000)
    private String pastSurgeries;

    @Column(length = 2000)
    private String medicalNotes;

    @Column(length = 1000)
    private String disabilities;

    private String medicalAidStatus;
    private String medicalAidProvider;
    private String medicalAidNumber;

    private String preferredFacility;

    @ManyToOne
    @JoinColumn(name = "registered_facility_id")
    private Facility registeredFacility;

    @Column(nullable = false)
    private String role = "PATIENT";

    @Column(nullable = false)
    private String accountStatus = "ACTIVE";

    @Column(nullable = false)
    private Boolean consentToStoreRecords = false;

    @Column(nullable = false)
    private Boolean consentToShareRecords = false;

    private LocalDateTime dateRegistered = LocalDateTime.now();
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;
    private String updatedBy;

    @PrePersist
    protected void onCreate() { createdAt = LocalDateTime.now(); updatedAt = LocalDateTime.now(); }
    @PreUpdate
    protected void onUpdate() { updatedAt = LocalDateTime.now(); }

    public Patient() {
    }

    public Long getId() {
        return id;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getIdNumber() {
        return idNumber;
    }

    public void setIdNumber(String idNumber) {
        this.idNumber = idNumber;
    }

    public String getPassportNumber() {
        return passportNumber;
    }

    public void setPassportNumber(String passportNumber) {
        this.passportNumber = passportNumber;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public void setDateOfBirth(LocalDate dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    public String getGender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }

    public String getEthnicity() {
        return ethnicity;
    }

    public void setEthnicity(String ethnicity) {
        this.ethnicity = ethnicity;
    }

    public String getNationality() {
        return nationality;
    }

    public void setNationality(String nationality) {
        this.nationality = nationality;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }
    
    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public String getAlternativePhoneNumber() {
        return alternativePhoneNumber;
    }

    public void setAlternativePhoneNumber(String alternativePhoneNumber) {
        this.alternativePhoneNumber = alternativePhoneNumber;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getProvince() {
        return province;
    }

    public void setProvince(String province) {
        this.province = province;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public void setPostalCode(String postalCode) {
        this.postalCode = postalCode;
    }

    public String getNextOfKinName() {
        return nextOfKinName;
    }

    public void setNextOfKinName(String nextOfKinName) {
        this.nextOfKinName = nextOfKinName;
    }

    public String getNextOfKinRelationship() {
        return nextOfKinRelationship;
    }

    public void setNextOfKinRelationship(String nextOfKinRelationship) {
        this.nextOfKinRelationship = nextOfKinRelationship;
    }

    public String getNextOfKinPhone() {
        return nextOfKinPhone;
    }

    public void setNextOfKinPhone(String nextOfKinPhone) {
        this.nextOfKinPhone = nextOfKinPhone;
    }

    public String getEmergencyContactName() {
        return emergencyContactName;
    }

    public void setEmergencyContactName(String emergencyContactName) {
        this.emergencyContactName = emergencyContactName;
    }

    public String getEmergencyContactPhone() {
        return emergencyContactPhone;
    }

    public void setEmergencyContactPhone(String emergencyContactPhone) {
        this.emergencyContactPhone = emergencyContactPhone;
    }

    public String getBloodType() {
        return bloodType;
    }

    public void setBloodType(String bloodType) {
        this.bloodType = bloodType;
    }

    public String getAllergies() {
        return allergies;
    }

    public void setAllergies(String allergies) {
        this.allergies = allergies;
    }

    public String getChronicConditions() {
        return chronicConditions;
    }

    public void setChronicConditions(String chronicConditions) {
        this.chronicConditions = chronicConditions;
    }

    public String getCurrentMedication() {
        return currentMedication;
    }

    public void setCurrentMedication(String currentMedication) {
        this.currentMedication = currentMedication;
    }

    public String getPastSurgeries() {
        return pastSurgeries;
    }

    public void setPastSurgeries(String pastSurgeries) {
        this.pastSurgeries = pastSurgeries;
    }

    public String getMedicalNotes() {
        return medicalNotes;
    }

    public void setMedicalNotes(String medicalNotes) {
        this.medicalNotes = medicalNotes;
    }

    public String getDisabilities() {
        return disabilities;
    }

    public void setDisabilities(String disabilities) {
        this.disabilities = disabilities;
    }

    public String getMedicalAidStatus() {
        return medicalAidStatus;
    }

    public void setMedicalAidStatus(String medicalAidStatus) {
        this.medicalAidStatus = medicalAidStatus;
    }

    public String getMedicalAidProvider() {
        return medicalAidProvider;
    }

    public void setMedicalAidProvider(String medicalAidProvider) {
        this.medicalAidProvider = medicalAidProvider;
    }

    public String getMedicalAidNumber() {
        return medicalAidNumber;
    }

    public void setMedicalAidNumber(String medicalAidNumber) {
        this.medicalAidNumber = medicalAidNumber;
    }

    public String getPreferredFacility() {
        return preferredFacility;
    }

    public void setPreferredFacility(String preferredFacility) {
        this.preferredFacility = preferredFacility;
    }

    /** The facility this patient registered at — used to decide whether a doctor
     * needs the patient's (or that facility's) permission to view their full
     * medical history. Null means no restriction (e.g. legacy/admin-registered records). */
    public Facility getRegisteredFacility() { return registeredFacility; }
    public void setRegisteredFacility(Facility registeredFacility) { this.registeredFacility = registeredFacility; }

    public String getRole() {
        return role;
    }

    public String getAccountStatus() {
        return accountStatus;
    }

    public Boolean getConsentToStoreRecords() {
        return consentToStoreRecords;
    }

    public void setConsentToStoreRecords(Boolean consentToStoreRecords) {
        this.consentToStoreRecords = consentToStoreRecords;
    }

    public Boolean getConsentToShareRecords() {
        return consentToShareRecords;
    }

    public void setConsentToShareRecords(Boolean consentToShareRecords) {
        this.consentToShareRecords = consentToShareRecords;
    }

    public LocalDateTime getDateRegistered() { return dateRegistered; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime v) { this.updatedAt = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String v) { this.updatedBy = v; }
}