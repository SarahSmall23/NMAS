package za.co.nmas.nmas.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import za.co.nmas.nmas.dto.PatientDto;
import za.co.nmas.nmas.service.PatientService;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/patients")
public class PatientApiController {

    private final PatientService patientService;

    public PatientApiController(PatientService patientService) {
        this.patientService = patientService;
    }

    @GetMapping
    public List<PatientDto> findAll() {
        return patientService.findAll().stream().map(PatientDto::from).collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PatientDto> findById(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(PatientDto.from(patientService.findById(id)));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
