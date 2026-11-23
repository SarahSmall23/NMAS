package za.co.nmas.nmas.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import za.co.nmas.nmas.dto.PrescriptionDto;
import za.co.nmas.nmas.service.PrescriptionService;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/prescriptions")
public class PrescriptionApiController {

    private final PrescriptionService prescriptionService;

    public PrescriptionApiController(PrescriptionService prescriptionService) {
        this.prescriptionService = prescriptionService;
    }

    @GetMapping
    public List<PrescriptionDto> findAll() {
        return prescriptionService.findAll().stream().map(PrescriptionDto::from).collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PrescriptionDto> findById(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(PrescriptionDto.from(prescriptionService.findById(id)));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/by-patient/{patientId}")
    public List<PrescriptionDto> byPatient(@PathVariable Long patientId) {
        return prescriptionService.findByPatientId(patientId).stream()
                .map(PrescriptionDto::from).collect(Collectors.toList());
    }

    @GetMapping("/by-doctor/{doctorId}")
    public List<PrescriptionDto> byDoctor(@PathVariable Long doctorId) {
        return prescriptionService.findByDoctorId(doctorId).stream()
                .map(PrescriptionDto::from).collect(Collectors.toList());
    }
}
