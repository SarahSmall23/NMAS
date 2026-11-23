package za.co.nmas.nmas.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@Entity
@Table(name = "doctors")
public class Doctor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false)
    private String fullName;

    @NotBlank
    @Column(nullable = false)
    private String gender;

    @Email
    @NotBlank
    @Column(nullable = false, unique = true)
    private String email;

    @NotBlank
    @Column(nullable = false)
    private String phoneNumber;

    private String alternativePhoneNumber;

    @NotBlank
    @Column(nullable = false)
    private String specialization;

    @NotBlank
    @Column(nullable = false)
    private String qualification;

    @NotBlank
    @Column(nullable = false, unique = true)
    private String hpcsaNumber;

    @ManyToOne
    @JoinColumn(name = "facility_id")
    private Facility facility;

    @Column(nullable = false)
    private String role = "DOCTOR";

    /** Doctor's digital signature as a PNG data URL (data:image/png;base64,...). Stamped on prescriptions and appointment proofs. */
    @Column(columnDefinition = "TEXT")
    private String signatureData;

    public Doctor() {}

    public Long getId() { return id; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

    public String getAlternativePhoneNumber() { return alternativePhoneNumber; }
    public void setAlternativePhoneNumber(String v) { this.alternativePhoneNumber = v; }

    public String getSpecialization() { return specialization; }
    public void setSpecialization(String specialization) { this.specialization = specialization; }

    public String getQualification() { return qualification; }
    public void setQualification(String qualification) { this.qualification = qualification; }

    public String getHpcsaNumber() { return hpcsaNumber; }
    public void setHpcsaNumber(String hpcsaNumber) { this.hpcsaNumber = hpcsaNumber; }

    public Facility getFacility() { return facility; }
    public void setFacility(Facility facility) { this.facility = facility; }

    public String getRole() { return role; }

    public String getSignatureData() { return signatureData; }
    public void setSignatureData(String signatureData) { this.signatureData = signatureData; }

    public boolean hasSignature() { return signatureData != null && !signatureData.isBlank(); }
}
