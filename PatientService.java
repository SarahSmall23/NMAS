package za.co.nmas.nmas.service;

import org.springframework.stereotype.Service;
import za.co.nmas.nmas.entity.Patient;
import za.co.nmas.nmas.repository.PatientRepository;
import za.co.nmas.nmas.util.PhoneUtil;

import java.util.List;
import java.util.Optional;

@Service
public class PatientService {

    private final PatientRepository patientRepository;
    private final AuditLogService auditLogService;

    public PatientService(PatientRepository patientRepository, AuditLogService auditLogService) {
        this.patientRepository = patientRepository;
        this.auditLogService = auditLogService;
    }

    public Patient register(Patient patient) {
        if (patient.getIdNumber() != null && patient.getIdNumber().isBlank()) {
            patient.setIdNumber(null);
        }
        if (patient.getPassportNumber() != null && patient.getPassportNumber().isBlank()) {
            patient.setPassportNumber(null);
        }
        if (patient.getEmail() != null && patient.getEmail().isBlank()) {
            patient.setEmail(null);
        }
        patient.setPhoneNumber(PhoneUtil.normalize(patient.getPhoneNumber()));
        patient.setAlternativePhoneNumber(PhoneUtil.normalize(patient.getAlternativePhoneNumber()));
        patient.setNextOfKinPhone(PhoneUtil.normalize(patient.getNextOfKinPhone()));
        patient.setEmergencyContactPhone(PhoneUtil.normalize(patient.getEmergencyContactPhone()));
        return patientRepository.save(patient);
    }

    public List<Patient> findAll() {
        return patientRepository.findAll();
    }

    public Patient findById(Long id) {
        return patientRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Patient not found"));
    }

    public Optional<Patient> findByIdNumber(String idNumber) {
        return patientRepository.findByIdNumber(idNumber);
    }

    public Optional<Patient> findByEmail(String email) {
        return patientRepository.findByEmail(email);
    }

    public Patient updateMedical(Long id, String bloodType, String allergies,
                                 String chronicConditions, String currentMedication,
                                 String pastSurgeries, String disabilities, String medicalNotes,
                                 String medicalAidStatus, String medicalAidProvider,
                                 String medicalAidNumber, Boolean consentToStoreRecords,
                                 Boolean consentToShareRecords) {
        Patient patient = findById(id);
        patient.setBloodType(bloodType);
        patient.setAllergies(allergies);
        patient.setChronicConditions(chronicConditions);
        patient.setCurrentMedication(currentMedication);
        patient.setPastSurgeries(pastSurgeries);
        patient.setDisabilities(disabilities);
        patient.setMedicalNotes(medicalNotes);
        patient.setMedicalAidStatus(medicalAidStatus);
        patient.setMedicalAidProvider(medicalAidProvider);
        patient.setMedicalAidNumber(medicalAidNumber);
        patient.setConsentToStoreRecords(Boolean.TRUE.equals(consentToStoreRecords));
        patient.setConsentToShareRecords(Boolean.TRUE.equals(consentToShareRecords));
        Patient saved = patientRepository.save(patient);
        auditLogService.log("MEDICAL_INFO_UPDATED", "Patient", id, null);
        return saved;
    }

    public long count() {
        return patientRepository.count();
    }

    /** Every patient in the national table (any facility), matched on name / ID / passport. */
    public List<Patient> searchAll(String q) {
        return patientRepository.searchAllByNameOrId(q);
    }

    public List<Patient> searchByFacilityAndQuery(String facility, String query) {
        return patientRepository.searchByFacilityAndQuery(facility, query);
    }

    public java.util.List<String> findDistinctFacilities() {
        return patientRepository.findDistinctFacilities();
    }
}
// NOTE: append before closing brace - done via str_replace below
