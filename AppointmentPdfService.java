package za.co.nmas.nmas.service;

import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;
import za.co.nmas.nmas.entity.Appointment;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;

/** The appointment slip a patient receives (dashboard download + email) when a doctor books a follow-up. */
@Service
public class AppointmentPdfService {

    private static final Color TEAL = new Color(15, 118, 110);

    public byte[] generate(Appointment a) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Document doc = new Document(PageSize.A4, 48, 48, 48, 48);
            PdfWriter.getInstance(doc, out);
            doc.open();

            String facility = a.getFacility() != null ? a.getFacility().getFacilityName()
                    : (a.getDoctor() != null && a.getDoctor().getFacility() != null ? a.getDoctor().getFacility().getFacilityName() : "NMAS Facility");

            Paragraph title = new Paragraph("NMAS - Appointment Confirmation", new Font(Font.HELVETICA, 20, Font.BOLD, TEAL));
            title.setSpacingAfter(2);
            doc.add(title);
            doc.add(new Paragraph(facility, new Font(Font.HELVETICA, 12, Font.BOLD, new Color(15, 23, 42))));
            Paragraph kind = new Paragraph(a.isFollowUp() ? "FOLLOW-UP / CHECK-UP APPOINTMENT" : "APPOINTMENT",
                    new Font(Font.HELVETICA, 10, Font.BOLD, new Color(180, 83, 9)));
            kind.setSpacingBefore(6);
            kind.setSpacingAfter(16);
            doc.add(kind);

            PdfPTable t = new PdfPTable(2);
            t.setWidthPercentage(100);
            t.setWidths(new float[]{1f, 2f});
            row(t, "Patient", a.getPatient() != null ? a.getPatient().getFullName() : "N/A");
            row(t, "Doctor", a.getDoctor() != null ? "Dr. " + a.getDoctor().getFullName()
                    + (a.getDoctor().getSpecialization() != null ? " (" + a.getDoctor().getSpecialization() + ")" : "") : "N/A");
            row(t, "Date", a.getAppointmentDate() != null
                    ? a.getAppointmentDate().format(DateTimeFormatter.ofPattern("EEEE, dd MMMM yyyy")) : "To be confirmed");
            row(t, "Time", a.getAppointmentTime() != null ? a.getAppointmentTime().toString() : "To be confirmed");
            row(t, "Facility", facility);
            row(t, "Reason", a.getReason() != null && !a.getReason().isBlank() ? a.getReason() : "Check-up");
            row(t, "Reference", "APT-" + a.getId());
            doc.add(t);

            Paragraph note = new Paragraph(
                    "\nPlease arrive on time and show this slip (printed or on your phone) at reception. Because this is a "
                  + "follow-up booked by your doctor, you will be taken ahead of the walk-in queue. If you cannot attend, "
                  + "please contact the facility so the slot can be given to someone else.",
                    new Font(Font.HELVETICA, 10, Font.NORMAL, new Color(71, 85, 105)));
            doc.add(note);

            Paragraph sigSpacer = new Paragraph(" ");
            sigSpacer.setSpacingBefore(18);
            doc.add(sigSpacer);
            com.lowagie.text.Image sigImg = za.co.nmas.nmas.util.SignatureImage.of(a.getDoctor(), 170, 60);
            if (sigImg != null) {
                sigImg.setAlignment(Element.ALIGN_LEFT);
                doc.add(sigImg);
            } else {
                doc.add(new Paragraph("\n"));
            }
            doc.add(new Paragraph("______________________________",
                    new Font(Font.HELVETICA, 10, Font.NORMAL, new Color(15, 23, 42))));
            doc.add(new Paragraph("Doctor's signature" + (a.getDoctor() != null ? " - Dr. " + a.getDoctor().getFullName() : ""),
                    new Font(Font.HELVETICA, 9, Font.BOLD, new Color(100, 116, 139))));
            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Could not generate appointment PDF", e);
        }
    }

    private void row(PdfPTable t, String label, String value) {
        PdfPCell l = new PdfPCell(new Phrase(label, new Font(Font.HELVETICA, 9, Font.BOLD, new Color(100, 116, 139))));
        PdfPCell v = new PdfPCell(new Phrase(value, new Font(Font.HELVETICA, 11, Font.NORMAL, new Color(15, 23, 42))));
        for (PdfPCell c : new PdfPCell[]{l, v}) { c.setBorderColor(new Color(226, 232, 240)); c.setPadding(8); }
        t.addCell(l); t.addCell(v);
    }
}
