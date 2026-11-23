package za.co.nmas.nmas.controller;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import za.co.nmas.nmas.entity.Appointment;
import za.co.nmas.nmas.entity.AppointmentSlot;
import za.co.nmas.nmas.entity.Patient;
import za.co.nmas.nmas.service.AppointmentService;
import za.co.nmas.nmas.service.DoctorAvailabilityService;
import za.co.nmas.nmas.service.DoctorService;
import za.co.nmas.nmas.service.FacilityService;
import za.co.nmas.nmas.service.PatientService;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

@Controller
public class AppointmentController {

    private final AppointmentService appointmentService;
    private final PatientService patientService;
    private final DoctorService doctorService;
    private final FacilityService facilityService;
    private final DoctorAvailabilityService availabilityService;
    private final za.co.nmas.nmas.repository.StaffMemberRepository staffMemberRepository;

    public AppointmentController(AppointmentService appointmentService,
                                 PatientService patientService,
                                 DoctorService doctorService,
                                 FacilityService facilityService,
                                 DoctorAvailabilityService availabilityService,
                                 za.co.nmas.nmas.repository.StaffMemberRepository staffMemberRepository) {
        this.staffMemberRepository = staffMemberRepository;
        this.appointmentService = appointmentService;
        this.patientService = patientService;
        this.doctorService = doctorService;
        this.facilityService = facilityService;
        this.availabilityService = availabilityService;
    }

    private boolean isPatient(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_PATIENT"));
    }

    @GetMapping("/appointments")
    public String viewAppointments(Authentication auth, Model model) {
        java.util.List<Appointment> list;
        if (auth.getAuthorities().stream().anyMatch(x -> x.getAuthority().equals("ROLE_SYSTEM_ADMIN"))) {
            list = appointmentService.findAll();
        } else if (auth.getAuthorities().stream().anyMatch(x -> x.getAuthority().equals("ROLE_DOCTOR"))) {
            list = doctorService.findByEmail(auth.getName())
                    .map(d -> appointmentService.findByDoctorId(d.getId())).orElse(java.util.List.of());
        } else {
            // Receptionists, nurses and facility admins: only their own facility's appointments.
            Long fid = staffMemberRepository.findByEmail(auth.getName())
                    .map(sm -> sm.getFacility() != null ? sm.getFacility().getId() : null).orElse(null);
            list = fid == null ? java.util.List.of() : appointmentService.findAll().stream()
                    .filter(a -> (a.getFacility() != null && a.getFacility().getId().equals(fid))
                            || (a.getFacility() == null && a.getDoctor() != null && a.getDoctor().getFacility() != null
                                && a.getDoctor().getFacility().getId().equals(fid)))
                    .toList();
        }
        model.addAttribute("appointments", list);
        model.addAttribute("totalAppointments", list.size());
        model.addAttribute("todayAppointments", list.stream().filter(a -> LocalDate.now().equals(a.getAppointmentDate())).count());
        model.addAttribute("pendingAppointments", list.stream().filter(a -> "PENDING".equals(a.getStatus())).count());
        model.addAttribute("completedAppointments", list.stream().filter(a -> "COMPLETED".equals(a.getStatus())).count());
        model.addAttribute("isDoctorView", auth.getAuthorities().stream().anyMatch(x -> x.getAuthority().equals("ROLE_DOCTOR")));
        return "appointments";
    }

    /** Physical appointments are now booked by the doctor as follow-ups. */
    @GetMapping("/appointments/book")
    public String showBookingForm() {
        return "redirect:/follow-up/book";
    }

    @PostMapping("/appointments/book")
    public String bookAppointment() {
        return "redirect:/follow-up/book";
    }

    @GetMapping("/appointments/approve/{id}")
    public String approveAppointment(@PathVariable Long id) {
        appointmentService.approve(id);
        return "redirect:/appointments";
    }

    @GetMapping("/appointments/reject/{id}")
    public String rejectAppointment(@PathVariable Long id) {
        appointmentService.reject(id);
        return "redirect:/appointments";
    }
}
