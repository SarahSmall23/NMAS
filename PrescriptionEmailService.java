package za.co.nmas.nmas.service;

import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import za.co.nmas.nmas.entity.Prescription;

/**
 * Emails the prescription PDF receipt to the patient's registered email
 * address, in addition to it being available on the website. Sending is
 * best-effort: if the patient has no email on file, or the mail server is
 * unreachable, this quietly does nothing rather than failing the whole
 * prescription-save operation — the receipt is still available for
 * download on the site either way.
 */
@Service
public class PrescriptionEmailService {

    private final JavaMailSender mailSender;
    private final PrescriptionPdfService prescriptionPdfService;

    @Value("${spring.mail.username}")
    private String fromAddress;

    public PrescriptionEmailService(JavaMailSender mailSender, PrescriptionPdfService prescriptionPdfService) {
        this.mailSender = mailSender;
        this.prescriptionPdfService = prescriptionPdfService;
    }

    public void sendReceiptIfPossible(Prescription prescription) {
        try {
            if (prescription.getPatient() == null
                    || prescription.getPatient().getEmail() == null
                    || prescription.getPatient().getEmail().isBlank()) {
                return; // no email on file — receipt is still available on the site
            }

            byte[] pdf = prescriptionPdfService.generateReceipt(prescription);

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);
            helper.setFrom(fromAddress);
            helper.setTo(prescription.getPatient().getEmail());
            helper.setSubject("Your NMAS Prescription Receipt — " + prescription.getDisplayNumber());
            helper.setText(
                    "Hi " + safe(prescription.getPatient().getFullName()) + ",\n\n" +
                    "A new prescription has been issued for you" +
                    (prescription.getDoctor() != null ? " by Dr. " + prescription.getDoctor().getFullName() : "") +
                    (prescription.getIssuingFacility() != null ? " at " + prescription.getIssuingFacility().getFacilityName() : "") +
                    (prescription.getPrescriptionDate() != null ? " on " + prescription.getPrescriptionDate() : "") +
                    ".\n\nYour prescription receipt is attached as a PDF — present it at any NMAS-affiliated " +
                    "dispensary to collect your medication, or keep it for your own reference.\n\n" +
                    "If you have any questions, please contact your clinic.\n\n" +
                    "— NMAS (National Medical Access System)"
            );
            helper.addAttachment("NMAS-Prescription-" + prescription.getDisplayNumber() + ".pdf",
                    new org.springframework.core.io.ByteArrayResource(pdf));

            mailSender.send(message);
        } catch (Exception e) {
            // Never let an email failure block the prescription save itself.
            System.err.println("[NMAS] Could not email prescription receipt: " + e);
        }
    }

    private String safe(String s) {
        return s != null ? s : "there";
    }
}
