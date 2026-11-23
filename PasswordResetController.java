package za.co.nmas.nmas.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import za.co.nmas.nmas.service.PasswordResetService;

@Controller
@RequestMapping("/forgot-password")
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    public PasswordResetController(PasswordResetService passwordResetService) {
        this.passwordResetService = passwordResetService;
    }

    @GetMapping
    public String forgotForm() {
        return "forgot-password";
    }

    @PostMapping
    public String sendOtp(@RequestParam String email, Model model) {
        try {
            boolean sent = passwordResetService.generateOtp(email);
            if (!sent) {
                model.addAttribute("error", "No account found with that email address.");
                return "forgot-password";
            }
        } catch (RuntimeException e) {
            model.addAttribute("error", "Could not send OTP email. Please check mail configuration.");
            return "forgot-password";
        }
        model.addAttribute("email", email);
        return "verify-otp";
    }

    @GetMapping("/verify")
    public String verifyForm(@RequestParam(required = false) String email, Model model) {
        model.addAttribute("email", email);
        return "verify-otp";
    }

    @PostMapping("/verify")
    public String verifyOtp(@RequestParam String email,
                            @RequestParam String otp,
                            Model model) {
        if (!passwordResetService.verifyOtp(email, otp)) {
            model.addAttribute("email", email);
            model.addAttribute("error", "Invalid or expired OTP. Please try again.");
            return "verify-otp";
        }
        model.addAttribute("email", email);
        model.addAttribute("otp", otp);
        return "reset-password";
    }

    @GetMapping("/reset")
    public String resetForm(@RequestParam(required = false) String email,
                            @RequestParam(required = false) String otp,
                            Model model) {
        model.addAttribute("email", email);
        model.addAttribute("otp", otp);
        return "reset-password";
    }

    @PostMapping("/reset")
    public String resetPassword(@RequestParam String email,
                                @RequestParam String otp,
                                @RequestParam String newPassword,
                                @RequestParam String confirmPassword,
                                Model model) {
        if (!newPassword.equals(confirmPassword)) {
            model.addAttribute("email", email);
            model.addAttribute("otp", otp);
            model.addAttribute("error", "Passwords do not match.");
            return "reset-password";
        }
        boolean reset = passwordResetService.resetPassword(email, otp, newPassword);
        if (!reset) {
            model.addAttribute("email", email);
            model.addAttribute("otp", otp);
            model.addAttribute("error", "OTP expired or already used. Please start over.");
            return "reset-password";
        }
        return "redirect:/login?passwordReset=true";
    }
}
