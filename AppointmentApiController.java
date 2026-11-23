package za.co.nmas.nmas.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import za.co.nmas.nmas.dto.AppointmentDto;
import za.co.nmas.nmas.service.AppointmentService;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/appointments")
public class AppointmentApiController {

    private final AppointmentService appointmentService;

    public AppointmentApiController(AppointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }

    @GetMapping
    public List<AppointmentDto> findAll() {
        return appointmentService.findAll().stream().map(AppointmentDto::from).collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<AppointmentDto> findById(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(AppointmentDto.from(appointmentService.findById(id)));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/by-patient/{patientId}")
    public List<AppointmentDto> byPatient(@PathVariable Long patientId) {
        return appointmentService.findByPatientId(patientId).stream()
                .map(AppointmentDto::from).collect(Collectors.toList());
    }

    @GetMapping("/by-doctor/{doctorId}")
    public List<AppointmentDto> byDoctor(@PathVariable Long doctorId) {
        return appointmentService.findByDoctorId(doctorId).stream()
                .map(AppointmentDto::from).collect(Collectors.toList());
    }
}
