package za.co.nmas.nmas.dto;

import za.co.nmas.nmas.entity.Consultation;

public class ConsultationDto {
    public Long id;
    public Long patientId;
    public String patientName;
    public Long appointmentId;
    public String symptoms;
    public String diagnosis;
    public String treatmentPlan;
    public String prescriptionNotes;
    public String consultationDate;

    public static ConsultationDto from(Consultation c) {
        ConsultationDto dto = new ConsultationDto();
        dto.id = c.getId();
        if (c.getPatient() != null) {
            dto.patientId = c.getPatient().getId();
            dto.patientName = c.getPatient().getFullName();
        }
        if (c.getAppointment() != null) {
            dto.appointmentId = c.getAppointment().getId();
        }
        dto.symptoms = c.getSymptoms();
        dto.diagnosis = c.getDiagnosis();
        dto.treatmentPlan = c.getTreatmentPlan();
        dto.prescriptionNotes = c.getPrescriptionNotes();
        dto.consultationDate = c.getConsultationDate() != null ? c.getConsultationDate().toString() : null;
        return dto;
    }
}
