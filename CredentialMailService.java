package za.co.nmas.nmas.service;

import jakarta.annotation.PostConstruct;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class CredentialMailService {

    private static final Logger log = LoggerFactory.getLogger(CredentialMailService.class);

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String gmailFrom;

    private boolean gmailEnabled  = false;

    public CredentialMailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @PostConstruct
    public void init() {
        // ── Gmail ──────────────────────────────────────────────────────────
        if (!gmailFrom.isBlank()) {
            gmailEnabled = true;
            log.info("✅ Gmail enabled — sending from {}", gmailFrom);
        } else {
            log.warn("⚠️  Gmail not configured.");
        }
    }

    /**
     * Emails the login credentials to the patient's personal email (if they gave one).
     * Called automatically on patient registration. Email only; no SMS.
     */
    public boolean sendCredentials(String personalEmail, String patientName, String loginEmail, String password) {
        if (personalEmail == null || personalEmail.isBlank() || personalEmail.endsWith("@nmas.ac.za")) return false;
        boolean emailSent = sendEmail(personalEmail, patientName, loginEmail, password);
        log.info("Credentials delivery by email: {}", emailSent);
        return emailSent;
    }

    // ── Email via Gmail ───────────────────────────────────────────────────────

    private boolean sendEmail(String toEmail, String name, String loginEmail, String password) {
        if (!gmailEnabled) {
            log.warn("Email skipped — Gmail not configured.");
            return false;
        }
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(msg, true, "UTF-8");
            h.setFrom(gmailFrom);
            h.setTo(toEmail);
            h.setSubject("Your NMAS Patient Portal Login Credentials");
            h.setText(buildHtml(name, loginEmail, password), true);
            mailSender.send(msg);
            log.info("✅ Credential email sent to {}", toEmail);
            return true;
        } catch (MessagingException e) {
            log.error("❌ Email failed to {}: {}", toEmail, e.getMessage());
            return false;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildHtml(String name, String loginEmail, String password) {
        return """
            <!DOCTYPE html><html><body style="margin:0;padding:0;background:#f0faf5;font-family:Arial,sans-serif;">
            <div style="max-width:560px;margin:40px auto;background:white;border-radius:16px;overflow:hidden;box-shadow:0 4px 20px rgba(0,0,0,.08);">
              <div style="background:#1C2B2B;padding:30px;text-align:center;">
                <h1 style="color:white;margin:0;font-size:26px;">NMAS</h1>
                <p style="color:#a7f3d0;margin:6px 0 0;">National Medical Access System</p>
              </div>
              <div style="padding:36px;">
                <p style="font-size:16px;color:#1C2B2B;">Dear <strong>%s</strong>,</p>
                <p style="color:#475569;line-height:1.7;">
                  Your patient portal account has been created. Use the details below to log in:
                </p>
                <div style="background:#f0faf5;border-radius:12px;padding:24px;margin:24px 0;border-left:4px solid #059669;">
                  <p style="margin:0 0 4px;font-size:12px;color:#64748b;text-transform:uppercase;letter-spacing:1px;">Login URL</p>
                  <p style="margin:0 0 18px;font-size:15px;font-weight:bold;color:#059669;">http://localhost:8080/login</p>
                  <p style="margin:0 0 4px;font-size:12px;color:#64748b;text-transform:uppercase;letter-spacing:1px;">Your Email (Username)</p>
                  <p style="margin:0 0 18px;font-size:15px;font-weight:bold;color:#1C2B2B;">%s</p>
                  <p style="margin:0 0 4px;font-size:12px;color:#64748b;text-transform:uppercase;letter-spacing:1px;">Your Password</p>
                  <p style="margin:0;font-size:15px;font-weight:bold;color:#1C2B2B;">
                    %s <span style="font-size:12px;color:#64748b;font-weight:400;">(your SA ID number)</span>
                  </p>
                </div>
                <p style="color:#ef4444;font-size:13px;">
                  🔒 Keep these credentials confidential. Do not share them with anyone.
                </p>
                <p style="color:#64748b;font-size:13px;line-height:1.7;">
                  If you have any questions, please contact your NMAS clinic directly.
                </p>
              </div>
              <div style="background:#f8fafc;padding:18px;text-align:center;border-top:1px solid #e2e8f0;">
                <p style="margin:0;font-size:12px;color:#94a3b8;">© National Medical Access System · Confidential</p>
              </div>
            </div></body></html>
            """.formatted(name, loginEmail, password);
    }
}
