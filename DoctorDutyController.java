package za.co.nmas.nmas.controller;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import za.co.nmas.nmas.entity.Doctor;
import za.co.nmas.nmas.entity.DoctorCheckIn;
import za.co.nmas.nmas.repository.DoctorCheckInRepository;
import za.co.nmas.nmas.repository.DoctorRepository;

import java.time.LocalDateTime;

/** Doctor check-in / check-out (on duty at the hospital) and digital signature management. */
@Controller
public class DoctorDutyController {

    private static final int MAX_SIGNATURE_CHARS = 700_000; // ~500 KB of base64

    private final DoctorRepository doctorRepository;
    private final DoctorCheckInRepository checkInRepository;

    public DoctorDutyController(DoctorRepository doctorRepository, DoctorCheckInRepository checkInRepository) {
        this.doctorRepository = doctorRepository;
        this.checkInRepository = checkInRepository;
    }

    @PostMapping("/doctor/check-in")
    public String checkIn(Authentication auth, RedirectAttributes ra) {
        Doctor doctor = doctorRepository.findByEmail(auth.getName()).orElse(null);
        if (doctor == null) return "redirect:/doctor-dashboard";
        if (checkInRepository.findFirstByDoctorIdAndCheckOutTimeIsNullOrderByCheckInTimeDesc(doctor.getId()).isEmpty()) {
            DoctorCheckIn c = new DoctorCheckIn();
            c.setDoctor(doctor);
            c.setFacility(doctor.getFacility());
            c.setCheckInTime(LocalDateTime.now());
            checkInRepository.save(c);
        }
        ra.addFlashAttribute("dutyMessage", "You are checked in and shown as available at the hospital.");
        return "redirect:/doctor-dashboard";
    }

    @PostMapping("/doctor/check-out")
    public String checkOut(Authentication auth, RedirectAttributes ra) {
        doctorRepository.findByEmail(auth.getName()).flatMap(d ->
                checkInRepository.findFirstByDoctorIdAndCheckOutTimeIsNullOrderByCheckInTimeDesc(d.getId()))
                .ifPresent(c -> { c.setCheckOutTime(LocalDateTime.now()); checkInRepository.save(c); });
        ra.addFlashAttribute("dutyMessage", "You have checked out.");
        return "redirect:/doctor-dashboard";
    }

    @GetMapping("/doctor/signature")
    public String signaturePage(Authentication auth, Model model) {
        Doctor d = doctorRepository.findByEmail(auth.getName()).orElse(null);
        model.addAttribute("doctorName", d != null ? d.getFullName() : "");
        model.addAttribute("signatureData", d != null ? d.getSignatureData() : null);
        return "doctor-signature";
    }

    @PostMapping("/doctor/signature")
    public String saveSignature(@RequestParam("signatureData") String data, Authentication auth, RedirectAttributes ra) {
        if (data == null || !data.startsWith("data:image/png;base64,") || data.length() > MAX_SIGNATURE_CHARS) {
            ra.addFlashAttribute("signatureError", "Please draw or upload a valid signature (PNG, under 500 KB).");
            return "redirect:/doctor/signature";
        }
        doctorRepository.findByEmail(auth.getName()).ifPresent(d -> {
            d.setSignatureData(data);
            doctorRepository.save(d);
        });
        ra.addFlashAttribute("signatureSaved", "Your signature was saved. It will now appear on prescriptions and appointment proofs.");
        return "redirect:/doctor/signature";
    }

    @PostMapping("/doctor/signature/remove")
    public String removeSignature(Authentication auth, RedirectAttributes ra) {
        doctorRepository.findByEmail(auth.getName()).ifPresent(d -> {
            d.setSignatureData(null);
            doctorRepository.save(d);
        });
        ra.addFlashAttribute("signatureSaved", "Signature removed.");
        return "redirect:/doctor/signature";
    }
}
