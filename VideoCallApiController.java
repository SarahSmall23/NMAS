package za.co.nmas.nmas.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import za.co.nmas.nmas.dto.VideoCallDto;
import za.co.nmas.nmas.service.VideoConsultationService;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/video-calls")
public class VideoCallApiController {

    private final VideoConsultationService videoService;

    public VideoCallApiController(VideoConsultationService videoService) {
        this.videoService = videoService;
    }

    @GetMapping("/by-patient/{patientId}")
    public List<VideoCallDto> byPatient(@PathVariable Long patientId) {
        return videoService.findByPatient(patientId).stream()
                .map(VideoCallDto::from).collect(Collectors.toList());
    }

    @GetMapping("/by-doctor/{doctorId}")
    public List<VideoCallDto> byDoctor(@PathVariable Long doctorId) {
        return videoService.findIncoming(doctorId).stream()
                .map(VideoCallDto::from).collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<VideoCallDto> findById(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(VideoCallDto.from(videoService.findById(id)));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
