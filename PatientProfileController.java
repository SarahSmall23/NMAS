package za.co.nmas.nmas.controller;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import za.co.nmas.nmas.entity.Facility;
import za.co.nmas.nmas.entity.Patient;
import za.co.nmas.nmas.repository.PatientRepository;
import za.co.nmas.nmas.service.AuditLogService;
import za.co.nmas.nmas.service.FacilityService;

import java.time.LocalDateTime;

/**
 * "My Profile": a patient sees their own personal information and edits
 * the parts that are theirs to change. Identity fields (name, ID/passport,
 * date of birth, gender, login email, home facility, account status) are
 * read-only -- they come from the verified registration and are changed at
 * the facility. Only ever touches the logged-in patient's own row.
 */
@Controller
public class PatientProfileController {

    private final PatientRepository patientRepository;
    private final AuditLogService auditLogService;
    private final FacilityService facilityService;

    public PatientProfileController(PatientRepository patientRepository,
                                    AuditLogService auditLogService,
                                    FacilityService facilityService) {
        this.patientRepository = patientRepository;
        this.auditLogService = auditLogService;
        this.facilityService = facilityService;
    }

    @GetMapping("/patient/profile")
    public String profile(Authentication auth, Model model) {
        Patient p = patientRepository.findByEmail(auth.getName()).orElse(null);
        model.addAttribute("patient", p);
        model.addAttribute("facilities", facilityService.findAll());
        return "patient-profile";
    }

    @PostMapping("/patient/profile")
    public String save(Authentication auth, @ModelAttribute("form") ProfileForm f, RedirectAttributes ra) {
        Patient p = patientRepository.findByEmail(auth.getName()).orElse(null);
        if (p == null) {
            ra.addFlashAttribute("errorMessage", "We could not find your patient record.");
            return "redirect:/patient/profile";
        }
        p.setPhoneNumber(clean(f.phoneNumber));
        p.setAlternativePhoneNumber(clean(f.alternativePhoneNumber));
        p.setAddress(clean(f.address));
        p.setCity(clean(f.city));
        p.setProvince(clean(f.province));
        p.setPostalCode(clean(f.postalCode));
        p.setNationality(clean(f.nationality));
        p.setNextOfKinName(clean(f.nextOfKinName));
        p.setNextOfKinRelationship(clean(f.nextOfKinRelationship));
        p.setNextOfKinPhone(clean(f.nextOfKinPhone));
        p.setEmergencyContactName(clean(f.emergencyContactName));
        p.setEmergencyContactPhone(clean(f.emergencyContactPhone));
        p.setBloodType(clean(f.bloodType));
        p.setAllergies(clean(f.allergies));
        p.setChronicConditions(clean(f.chronicConditions));
        p.setCurrentMedication(clean(f.currentMedication));
        p.setPastSurgeries(clean(f.pastSurgeries));
        p.setDisabilities(clean(f.disabilities));
        p.setMedicalAidStatus(clean(f.medicalAidStatus));
        p.setMedicalAidProvider(clean(f.medicalAidProvider));
        p.setMedicalAidNumber(clean(f.medicalAidNumber));
        p.setConsentToStoreRecords(f.consentToStoreRecords != null);
        p.setConsentToShareRecords(f.consentToShareRecords != null);
        p.setUpdatedAt(LocalDateTime.now());
        p.setUpdatedBy(auth.getName());
        // Changing the preferred facility never changes the home facility used for record-access auditing.
        if (f.preferredFacility != null && !f.preferredFacility.isBlank()) p.setPreferredFacility(f.preferredFacility.trim());
        patientRepository.save(p);
        try {
            auditLogService.log("PATIENT_PROFILE_UPDATED", "Patient", p.getId(),
                    p.getFullName() + " updated their own profile");
        } catch (Exception ignored) { }
        ra.addFlashAttribute("successMessage", "Your information has been updated.");
        return "redirect:/patient/profile";
    }

    private static String clean(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Binds only the fields a patient is allowed to change. */
    public static class ProfileForm {
        public String phoneNumber, alternativePhoneNumber, address, city, province, postalCode, nationality;
        public String nextOfKinName, nextOfKinRelationship, nextOfKinPhone, emergencyContactName, emergencyContactPhone;
        public String bloodType, allergies, chronicConditions, currentMedication, pastSurgeries, disabilities;
        public String medicalAidStatus, medicalAidProvider, medicalAidNumber, preferredFacility;
        public String consentToStoreRecords, consentToShareRecords;

        public String getPhoneNumber() { return phoneNumber; } public void setPhoneNumber(String v) { phoneNumber = v; }
        public String getAlternativePhoneNumber() { return alternativePhoneNumber; } public void setAlternativePhoneNumber(String v) { alternativePhoneNumber = v; }
        public String getAddress() { return address; } public void setAddress(String v) { address = v; }
        public String getCity() { return city; } public void setCity(String v) { city = v; }
        public String getProvince() { return province; } public void setProvince(String v) { province = v; }
        public String getPostalCode() { return postalCode; } public void setPostalCode(String v) { postalCode = v; }
        public String getNationality() { return nationality; } public void setNationality(String v) { nationality = v; }
        public String getNextOfKinName() { return nextOfKinName; } public void setNextOfKinName(String v) { nextOfKinName = v; }
        public String getNextOfKinRelationship() { return nextOfKinRelationship; } public void setNextOfKinRelationship(String v) { nextOfKinRelationship = v; }
        public String getNextOfKinPhone() { return nextOfKinPhone; } public void setNextOfKinPhone(String v) { nextOfKinPhone = v; }
        public String getEmergencyContactName() { return emergencyContactName; } public void setEmergencyContactName(String v) { emergencyContactName = v; }
        public String getEmergencyContactPhone() { return emergencyContactPhone; } public void setEmergencyContactPhone(String v) { emergencyContactPhone = v; }
        public String getBloodType() { return bloodType; } public void setBloodType(String v) { bloodType = v; }
        public String getAllergies() { return allergies; } public void setAllergies(String v) { allergies = v; }
        public String getChronicConditions() { return chronicConditions; } public void setChronicConditions(String v) { chronicConditions = v; }
        public String getCurrentMedication() { return currentMedication; } public void setCurrentMedication(String v) { currentMedication = v; }
        public String getPastSurgeries() { return pastSurgeries; } public void setPastSurgeries(String v) { pastSurgeries = v; }
        public String getDisabilities() { return disabilities; } public void setDisabilities(String v) { disabilities = v; }
        public String getMedicalAidStatus() { return medicalAidStatus; } public void setMedicalAidStatus(String v) { medicalAidStatus = v; }
        public String getMedicalAidProvider() { return medicalAidProvider; } public void setMedicalAidProvider(String v) { medicalAidProvider = v; }
        public String getMedicalAidNumber() { return medicalAidNumber; } public void setMedicalAidNumber(String v) { medicalAidNumber = v; }
        public String getPreferredFacility() { return preferredFacility; } public void setPreferredFacility(String v) { preferredFacility = v; }
        public String getConsentToStoreRecords() { return consentToStoreRecords; } public void setConsentToStoreRecords(String v) { consentToStoreRecords = v; }
        public String getConsentToShareRecords() { return consentToShareRecords; } public void setConsentToShareRecords(String v) { consentToShareRecords = v; }
    }
}
