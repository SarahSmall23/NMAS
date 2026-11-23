package za.co.nmas.nmas.controller;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import za.co.nmas.nmas.entity.Appointment;
import za.co.nmas.nmas.entity.Consultation;
import za.co.nmas.nmas.service.AppointmentService;
import za.co.nmas.nmas.service.ConsultationService;
import za.co.nmas.nmas.service.DoctorService;
import za.co.nmas.nmas.service.WalkInVisitService;

import java.util.Collections;

@Controller
@RequestMapping("/consultations")
public class ConsultationController {

    private final ConsultationService consultationService;
    private final AppointmentService appointmentService;
    private final DoctorService doctorService;
    private final WalkInVisitService walkInVisitService;

    public ConsultationController(ConsultationService consultationService,
                                  AppointmentService appointmentService,
                                  DoctorService doctorService,
                                  WalkInVisitService walkInVisitService) {
        this.consultationService = consultationService;
        this.appointmentService = appointmentService;
        this.doctorService = doctorService;
        this.walkInVisitService = walkInVisitService;
    }

    @GetMapping
    public String listConsultations(Authentication auth, Model model) {
        boolean isDoctor = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_DOCTOR"));

        if (isDoctor) {
            doctorService.findByEmail(auth.getName()).ifPresentOrElse(doctor -> {
                Long doctorId = doctor.getId();
                model.addAttribute("consultations", consultationService.findByDoctorId(doctorId));
                model.addAttribute("todayCount", consultationService.countTodayByDoctorId(doctorId));
                model.addAttribute("completedCount", consultationService.countCompletedByDoctorId(doctorId));
            }, () -> {
                model.addAttribute("consultations", Collections.emptyList());
                model.addAttribute("todayCount", 0L);
                model.addAttribute("completedCount", 0L);
            });
        } else {
            model.addAttribute("consultations", consultationService.findAll());
            model.addAttribute("todayCount", consultationService.countToday());
            model.addAttribute("completedCount", consultationService.countCompleted());
        }
        return "consultation-records";
    }

    @GetMapping("/start/{id}")
    public String startConsultation(@PathVariable Long id, Model model) {
        Appointment appointment = appointmentService.findById(id);
        model.addAttribute("appointment", appointment);
        model.addAttribute("patient", appointment.getPatient());
        model.addAttribute("vitals", walkInVisitService.vitalsForAppointment(id));
        return "start-consultation";
    }

    @PostMapping("/save")
    public String saveConsultation(@RequestParam Long appointmentId,
                                   @RequestParam String symptoms,
                                   @RequestParam String observation,
                                   @RequestParam String assessment) {

        consultationService.save(appointmentId, symptoms, observation, assessment);
        walkInVisitService.completeForAppointment(appointmentId);
        return "redirect:/appointments";
    }

    @GetMapping("/{id}/edit")
    public String showEditConsultation(@PathVariable Long id, Authentication auth, Model model) {
        Consultation consultation = consultationService.findById(id);

        boolean isDoctor = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_DOCTOR"));
        if (isDoctor) {
            boolean isOwner = doctorService.findByEmail(auth.getName())
                    .map(d -> consultation.getAppointment() != null
                            && consultation.getAppointment().getDoctor() != null
                            && consultation.getAppointment().getDoctor().getId().equals(d.getId()))
                    .orElse(false);
            if (!isOwner) {
                return "redirect:/consultations";
            }
        }

        model.addAttribute("consultation", consultation);
        if (consultation.getAppointment() != null) {
            model.addAttribute("vitals", walkInVisitService.vitalsForAppointment(consultation.getAppointment().getId()));
        }
        return "consultation-edit";
    }

    @PostMapping("/{id}/update")
    public String updateConsultation(@PathVariable Long id,
                                     @RequestParam String symptoms,
                                     @RequestParam String observation,
                                     @RequestParam String assessment) {

        consultationService.update(id, symptoms, observation, assessment);
        return "redirect:/consultations";
    }
}
