package za.co.nmas.nmas.dto;

import za.co.nmas.nmas.entity.Appointment;

public class AppointmentDto {
    public Long id;
    public String patientName;
    public Long patientId;
    public String doctorName;
    public Long doctorId;
    public String appointmentDate;
    public String appointmentTime;
    public String reason;
    public String status;

    public static AppointmentDto from(Appointment a) {
        AppointmentDto dto = new AppointmentDto();
        dto.id = a.getId();
        if (a.getPatient() != null) {
            dto.patientName = a.getPatient().getFullName();
            dto.patientId = a.getPatient().getId();
        }
        if (a.getDoctor() != null) {
            dto.doctorName = a.getDoctor().getFullName();
            dto.doctorId = a.getDoctor().getId();
        }
        dto.appointmentDate = a.getAppointmentDate() != null ? a.getAppointmentDate().toString() : null;
        dto.appointmentTime = a.getAppointmentTime() != null ? a.getAppointmentTime().toString() : null;
        dto.reason = a.getReason();
        dto.status = a.getStatus();
        return dto;
    }
}
