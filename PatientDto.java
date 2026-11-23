package za.co.nmas.nmas.dto;

import za.co.nmas.nmas.entity.Patient;

public class PatientDto {
    public Long id;
    public String fullName;
    public String idNumber;
    public String passportNumber;
    public String email;
    public String phoneNumber;
    public String gender;
    public String dateOfBirth;
    public String bloodType;
    public String allergies;
    public String chronicConditions;
    public String currentMedication;

    public static PatientDto from(Patient p) {
        PatientDto dto = new PatientDto();
        dto.id = p.getId();
        dto.fullName = p.getFullName();
        dto.idNumber = p.getIdNumber();
        dto.passportNumber = p.getPassportNumber();
        dto.email = p.getEmail();
        dto.phoneNumber = p.getPhoneNumber();
        dto.gender = p.getGender();
        dto.dateOfBirth = p.getDateOfBirth() != null ? p.getDateOfBirth().toString() : null;
        dto.bloodType = p.getBloodType();
        dto.allergies = p.getAllergies();
        dto.chronicConditions = p.getChronicConditions();
        dto.currentMedication = p.getCurrentMedication();
        return dto;
    }
}
