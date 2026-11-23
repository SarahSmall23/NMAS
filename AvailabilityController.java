package za.co.nmas.nmas.controller;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import za.co.nmas.nmas.entity.Doctor;
import za.co.nmas.nmas.service.DoctorAvailabilityService;
import za.co.nmas.nmas.service.DoctorService;

import java.time.LocalDate;
import java.time.LocalTime;

@Controller
@RequestMapping("/availability")
public class AvailabilityController {

    private final DoctorAvailabilityService availabilityService;
    private final DoctorService doctorService;

    public AvailabilityController(DoctorAvailabilityService availabilityService,
                                  DoctorService doctorService) {
        this.availabilityService = availabilityService;
        this.doctorService = doctorService;
    }

    @GetMapping
    public String listAvailability(Authentication auth, Model model) {
        doctorService.findByEmail(auth.getName()).ifPresent(doctor -> {
            model.addAttribute("doctor", doctor);
            model.addAttribute("availabilities", availabilityService.findByDoctor(doctor.getId()));
        });
        return "doctor-availability-list";
    }

    @GetMapping("/new")
    public String showAvailabilityForm(Model model) {
        return "doctor-availability-form";
    }

    @PostMapping("/save")
    public String saveAvailability(Authentication auth,
                                   @RequestParam LocalDate availableDate,
                                   @RequestParam LocalTime shiftStartTime,
                                   @RequestParam LocalTime shiftEndTime,
                                   Model model) {

        Doctor doctor = doctorService.findByEmail(auth.getName())
                .orElseThrow(() -> new RuntimeException("Doctor not found"));

        if (!shiftEndTime.isAfter(shiftStartTime.plusMinutes(30))) {
            model.addAttribute("errorMessage",
                    "Shift must be at least 30 minutes long.");
            return "doctor-availability-form";
        }

        availabilityService.createAvailability(doctor, availableDate, shiftStartTime, shiftEndTime);
        return "redirect:/availability";
    }

    @GetMapping("/{id}/slots")
    public String viewSlots(@PathVariable Long id, Model model) {
        var availability = availabilityService.findById(id);
        model.addAttribute("availability", availability);
        model.addAttribute("slots", availabilityService.getSlotsByAvailabilityId(id));
        return "doctor-slots";
    }

    /**
     * Used by the appointment-booking page: once a patient has picked a
     * doctor and (optionally) a date, this returns either that doctor's
     * confirmed schedule for the exact date, or — if none is set for that
     * date — their usual working pattern, so the patient can see roughly
     * when the doctor tends to be in before they submit the booking.
     */
    @GetMapping(value = "/api/schedule", produces = "application/json")
    @ResponseBody
    public java.util.Map<String, Object> schedule(@RequestParam Long doctorId,
                                                    @RequestParam(required = false) LocalDate date) {
        return availabilityService.getScheduleSummary(doctorId, date);
    }
}
