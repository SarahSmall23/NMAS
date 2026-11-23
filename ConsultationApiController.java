package za.co.nmas.nmas.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import za.co.nmas.nmas.dto.ConsultationDto;
import za.co.nmas.nmas.service.ConsultationService;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/consultations")
public class ConsultationApiController {

    private final ConsultationService consultationService;

    public ConsultationApiController(ConsultationService consultationService) {
        this.consultationService = consultationService;
    }

    @GetMapping
    public List<ConsultationDto> findAll() {
        return consultationService.findAll().stream().map(ConsultationDto::from).collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ConsultationDto> findById(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(ConsultationDto.from(consultationService.findById(id)));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/by-patient/{patientId}")
    public List<ConsultationDto> byPatient(@PathVariable Long patientId) {
        return consultationService.findByPatientId(patientId).stream()
                .map(ConsultationDto::from).collect(Collectors.toList());
    }

    @GetMapping("/by-doctor/{doctorId}")
    public List<ConsultationDto> byDoctor(@PathVariable Long doctorId) {
        return consultationService.findByDoctorId(doctorId).stream()
                .map(ConsultationDto::from).collect(Collectors.toList());
    }
}
