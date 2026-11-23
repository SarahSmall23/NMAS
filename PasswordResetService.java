package za.co.nmas.nmas.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import za.co.nmas.nmas.entity.AppUser;
import za.co.nmas.nmas.entity.PasswordResetOtp;
import za.co.nmas.nmas.repository.AppUserRepository;
import za.co.nmas.nmas.repository.PasswordResetOtpRepository;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Random;

@Service
public class PasswordResetService {

    private final PasswordResetOtpRepository otpRepository;
    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromAddress;

    public PasswordResetService(PasswordResetOtpRepository otpRepository,
                                AppUserRepository appUserRepository,
                                PasswordEncoder passwordEncoder,
                                JavaMailSender mailSender) {
        this.otpRepository = otpRepository;
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.mailSender = mailSender;
    }

    public boolean generateOtp(String email) {
        Optional<AppUser> user = appUserRepository.findByEmail(email);
        if (user.isEmpty()) return false;

        String code = String.format("%06d", new Random().nextInt(1_000_000));

        PasswordResetOtp otp = new PasswordResetOtp();
        otp.setEmail(email);
        otp.setOtp(code);
        otp.setExpiresAt(LocalDateTime.now().plusMinutes(15));
        otpRepository.save(otp);

        sendOtpEmail(email, code);
        return true;
    }

    private void sendOtpEmail(String toEmail, String code) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromAddress);
            helper.setTo(toEmail);
            helper.setSubject("NMAS Password Reset OTP");
            helper.setText(buildEmailBody(code), true);

            mailSender.send(message);
        } catch (MessagingException e) {
            throw new RuntimeException("Failed to send OTP email. Please try again.", e);
        }
    }

    private String buildEmailBody(String code) {
        return """
                <!DOCTYPE html>
                <html>
                <body style="margin:0;padding:0;background:#F0FAF5;font-family:Arial,sans-serif;">
                  <div style="max-width:480px;margin:40px auto;background:white;border-radius:16px;
                              box-shadow:0 8px 30px rgba(0,0,0,0.08);overflow:hidden;">
                    <div style="background:#1C2B2B;padding:28px 32px;">
                      <h1 style="color:white;margin:0;font-size:24px;">NMAS</h1>
                      <p style="color:#94a3b8;margin:4px 0 0;font-size:13px;">National Medical Access System</p>
                    </div>
                    <div style="padding:32px;">
                      <h2 style="color:#1C2B2B;font-size:20px;margin:0 0 12px;">Password Reset Request</h2>
                      <p style="color:#64748b;font-size:14px;line-height:1.7;margin:0 0 24px;">
                        You requested to reset your NMAS password. Use the One-Time PIN below to proceed.
                        This PIN expires in <strong>15 minutes</strong>.
                      </p>
                      <div style="background:#F0FAF5;border:2px dashed #059669;border-radius:12px;
                                  padding:24px;text-align:center;margin-bottom:24px;">
                        <p style="color:#64748b;font-size:13px;margin:0 0 10px;">Your One-Time PIN</p>
                        <span style="font-size:40px;font-weight:bold;color:#059669;letter-spacing:12px;">%s</span>
                      </div>
                      <p style="color:#94a3b8;font-size:12px;line-height:1.7;margin:0;">
                        If you did not request a password reset, please ignore this email.
                        Your password will not change unless you complete the reset process.
                      </p>
                    </div>
                    <div style="background:#f8fafc;padding:16px 32px;border-top:1px solid #e2e8f0;">
                      <p style="color:#94a3b8;font-size:11px;margin:0;">
                        &copy; NMAS &mdash; National Medical Access System &middot; Authorised use only
                      </p>
                    </div>
                  </div>
                </body>
                </html>
                """.formatted(code);
    }

    public boolean verifyOtp(String email, String code) {
        Optional<PasswordResetOtp> otp = otpRepository
                .findTopByEmailAndUsedFalseOrderByIdDesc(email);
        if (otp.isEmpty()) return false;
        PasswordResetOtp record = otp.get();
        return !record.isUsed()
                && record.getOtp().equals(code)
                && record.getExpiresAt().isAfter(LocalDateTime.now());
    }

    public boolean resetPassword(String email, String otp, String newPassword) {
        if (!verifyOtp(email, otp)) return false;

        AppUser user = appUserRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
        user.setPassword(passwordEncoder.encode(newPassword));
        appUserRepository.save(user);

        otpRepository.findTopByEmailAndUsedFalseOrderByIdDesc(email).ifPresent(o -> {
            o.setUsed(true);
            otpRepository.save(o);
        });
        return true;
    }
}
