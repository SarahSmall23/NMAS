package za.co.nmas.nmas.dto;

import za.co.nmas.nmas.entity.Prescription;

public class PrescriptionDto {
    public Long id;
    public Long patientId;
    public String patientName;
    public Long doctorId;
    public String doctorName;
    public String medicationName;
    public String dosage;
    public String frequency;
    public String duration;
    public String instructions;
    public String prescriptionDate;
    public String status;

    public static PrescriptionDto from(Prescription p) {
        PrescriptionDto dto = new PrescriptionDto();
        dto.id = p.getId();
        if (p.getPatient() != null) {
            dto.patientId = p.getPatient().getId();
            dto.patientName = p.getPatient().getFullName();
        }
        if (p.getDoctor() != null) {
            dto.doctorId = p.getDoctor().getId();
            dto.doctorName = p.getDoctor().getFullName();
        }
        dto.medicationName = p.getMedicationName();
        dto.dosage = p.getDosage();
        dto.frequency = p.getFrequency();
        dto.duration = p.getDuration();
        dto.instructions = p.getInstructions();
        dto.prescriptionDate = p.getPrescriptionDate() != null ? p.getPrescriptionDate().toString() : null;
        dto.status = p.getStatus();
        return dto;
    }
}
