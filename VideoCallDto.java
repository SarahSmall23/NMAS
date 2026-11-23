package za.co.nmas.nmas.dto;

import za.co.nmas.nmas.entity.VideoConsultationRequest;

public class VideoCallDto {
    public Long id;
    public Long patientId;
    public String patientName;
    public Long doctorId;
    public String doctorName;
    public String status;
    public String roomId;
    public String requestedAt;
    public String acceptedAt;
    public String endedAt;
    public String notes;

    public static VideoCallDto from(VideoConsultationRequest r) {
        VideoCallDto dto = new VideoCallDto();
        dto.id = r.getId();
        if (r.getPatient() != null) {
            dto.patientId = r.getPatient().getId();
            dto.patientName = r.getPatient().getFullName();
        }
        if (r.getDoctor() != null) {
            dto.doctorId = r.getDoctor().getId();
            dto.doctorName = r.getDoctor().getFullName();
        }
        dto.status = r.getStatus();
        dto.roomId = r.getRoomId();
        dto.requestedAt = r.getRequestedAt() != null ? r.getRequestedAt().toString() : null;
        dto.acceptedAt = r.getAcceptedAt() != null ? r.getAcceptedAt().toString() : null;
        dto.endedAt = r.getEndedAt() != null ? r.getEndedAt().toString() : null;
        dto.notes = r.getNotes();
        return dto;
    }
}
