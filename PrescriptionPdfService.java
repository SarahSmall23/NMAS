package za.co.nmas.nmas.service;

import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPCellEvent;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;
import za.co.nmas.nmas.entity.Prescription;
import za.co.nmas.nmas.entity.PrescriptionMedication;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;

/**
 * Generates a printable/downloadable PDF receipt for a prescription — the
 * document a patient can present at the dispensary (or refer back to at
 * home) showing what was prescribed, by whom, and how to take it.
 */
@Service
public class PrescriptionPdfService {

    private static final Font TITLE_FONT = new Font(Font.HELVETICA, 20, Font.BOLD, new Color(15, 118, 110));
    private static final Font SUBTITLE_FONT = new Font(Font.HELVETICA, 10, Font.NORMAL, new Color(100, 116, 139));
    private static final Font SECTION_FONT = new Font(Font.HELVETICA, 12, Font.BOLD, new Color(15, 23, 42));
    private static final Font LABEL_FONT = new Font(Font.HELVETICA, 9, Font.BOLD, new Color(100, 116, 139));
    private static final Font VALUE_FONT = new Font(Font.HELVETICA, 11, Font.NORMAL, new Color(15, 23, 42));
    private static final Font SMALL_FONT = new Font(Font.HELVETICA, 8, Font.NORMAL, new Color(148, 163, 184));

    public byte[] generateReceipt(Prescription prescription) {
        try {
            Document document = new Document(PageSize.A4, 40, 40, 40, 40);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfWriter.getInstance(document, out);
            document.open();

            // Header
            Paragraph title = new Paragraph("NMAS Prescription Receipt", TITLE_FONT);
            title.setAlignment(Element.ALIGN_LEFT);
            document.add(title);

            Paragraph subtitle = new Paragraph(
                    "Present this receipt at any NMAS-affiliated dispensary to collect your medication.",
                    SUBTITLE_FONT);
            subtitle.setSpacingAfter(16);
            document.add(subtitle);

            document.add(lineSeparator());
            document.add(Chunk.NEWLINE);

            // Overview table: date, doctor, patient, patient ID
            PdfPTable overview = new PdfPTable(2);
            overview.setWidthPercentage(100);
            overview.setSpacingAfter(18);

            addInfoCell(overview, "Date Issued",
                    prescription.getPrescriptionDate() != null
                            ? prescription.getPrescriptionDate().format(DateTimeFormatter.ofPattern("dd MMM yyyy"))
                            : "N/A");
            addInfoCell(overview, "Prescription No.", prescription.getDisplayNumber());
            addInfoCell(overview, "Issued At",
                    prescription.getIssuingFacility() != null ? prescription.getIssuingFacility().getFacilityName() : "N/A");
            addInfoCell(overview, "Prescribing Doctor",
                    prescription.getDoctor() != null ? "Dr. " + prescription.getDoctor().getFullName() : "N/A");
            addInfoCell(overview, "Specialization",
                    prescription.getDoctor() != null && prescription.getDoctor().getSpecialization() != null
                            ? prescription.getDoctor().getSpecialization() : "N/A");
            addInfoCell(overview, "Patient Name",
                    prescription.getPatient() != null ? prescription.getPatient().getFullName() : "N/A");
            addInfoCell(overview, "Patient ID Number",
                    prescription.getPatient() != null && prescription.getPatient().getIdNumber() != null
                            ? prescription.getPatient().getIdNumber() : "N/A");
            addInfoCell(overview, "Valid Until",
                    prescription.getEffectiveValidUntil().format(DateTimeFormatter.ofPattern("dd MMM yyyy"))
                            + (prescription.isExpired() ? " (EXPIRED)" : ""));
            addInfoCell(overview, "Validity Period", prescription.getValidityDays() + " days from issue");
            document.add(overview);

            // Medications
            Paragraph medHeader = new Paragraph("Prescribed Medication", SECTION_FONT);
            medHeader.setSpacingAfter(10);
            document.add(medHeader);

            PdfPTable medTable = new PdfPTable(new float[]{2.4f, 1.1f, 1.5f, 1f, 2f});
            medTable.setWidthPercentage(100);
            medTable.setSpacingAfter(16);
            addHeaderCell(medTable, "Medicine");
            addHeaderCell(medTable, "Dosage");
            addHeaderCell(medTable, "Frequency");
            addHeaderCell(medTable, "Duration");
            addHeaderCell(medTable, "Instructions");

            if (prescription.getMedicationLines() != null && !prescription.getMedicationLines().isEmpty()) {
                for (PrescriptionMedication line : prescription.getMedicationLines()) {
                    String medName = line.getMedication() != null ? line.getMedication().getDisplayName() : "—";
                    addBodyCell(medTable, medName);
                    addBodyCell(medTable, safe(line.getDosage()));
                    addBodyCell(medTable, safe(line.getFrequency()));
                    addBodyCell(medTable, safe(line.getDuration()));
                    addBodyCell(medTable, timingSummary(line) + (line.getInstructions() != null && !line.getInstructions().isBlank() ? " — " + line.getInstructions() : ""));
                }
            } else {
                // Legacy free-text prescription (no structured medication lines)
                addBodyCell(medTable, safe(prescription.getMedicationName()));
                addBodyCell(medTable, safe(prescription.getDosage()));
                addBodyCell(medTable, safe(prescription.getFrequency()));
                addBodyCell(medTable, safe(prescription.getDuration()));
                addBodyCell(medTable, safe(prescription.getInstructions()));
            }
            document.add(medTable);

            // Footer note
            Paragraph note = new Paragraph(
                    "Take medication exactly as instructed above. If you experience any adverse reaction, " +
                    "contact your clinic or nearest healthcare facility immediately. Keep this receipt for your records.",
                    SUBTITLE_FONT);
            note.setSpacingBefore(10);
            document.add(note);

            if (PillCollectionService.isRepeatPrescription(prescription)) {
                Paragraph repeat = new Paragraph(
                        "REPEAT PRESCRIPTION — collect your medication within the FIRST FOUR DAYS of each month. "
                        + "If it is not collected in that window, that month's note is no longer valid. "
                        + "Please report to the reception desk first.",
                        new Font(Font.HELVETICA, 9, Font.BOLD, new Color(154, 52, 18)));
                repeat.setSpacingBefore(8);
                document.add(repeat);
            }

            document.add(stampBlock(prescription));
            document.add(Chunk.NEWLINE);
            document.add(lineSeparator());
            Paragraph footer = new Paragraph("Generated by NMAS — National Medical Access System", SMALL_FONT);
            footer.setSpacingBefore(6);
            document.add(footer);

            document.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Could not generate prescription receipt: " + e.getMessage(), e);
        }
    }

    /** Signature line on the left, the facility's round-cornered issue stamp on the right. */
    private PdfPTable stampBlock(Prescription prescription) {
        PdfPTable wrap = new PdfPTable(new float[]{1.2f, 1f});
        wrap.setWidthPercentage(100);
        wrap.setSpacingBefore(22);

        PdfPCell sig = new PdfPCell();
        sig.setBorder(Rectangle.NO_BORDER);
        sig.setVerticalAlignment(Element.ALIGN_BOTTOM);
        com.lowagie.text.Image sigImg = za.co.nmas.nmas.util.SignatureImage.of(prescription.getDoctor(), 170, 60);
        if (sigImg != null) {
            sigImg.setAlignment(Element.ALIGN_LEFT);
            sig.addElement(sigImg);
        }
        Paragraph sp = new Paragraph(sigImg != null ? "______________________________\n" : "\n\n______________________________\n", VALUE_FONT);
        sp.add(new Chunk("Doctor's signature"
                + (prescription.getDoctor() != null ? " — Dr. " + prescription.getDoctor().getFullName() : ""), LABEL_FONT));
        sig.addElement(sp);
        wrap.addCell(sig);

        Color teal = new Color(15, 118, 110);
        PdfPCell stamp = new PdfPCell();
        stamp.setBorder(Rectangle.NO_BORDER);
        stamp.setPadding(12);
        stamp.setCellEvent(new PdfPCellEvent() {
            @Override
            public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
                PdfContentByte cb = canvases[PdfPTable.LINECANVAS];
                cb.setColorStroke(teal);
                cb.setLineWidth(2.2f);
                cb.roundRectangle(position.getLeft() + 2, position.getBottom() + 2,
                        position.getWidth() - 4, position.getHeight() - 4, 12);
                cb.stroke();
                cb.setLineWidth(0.8f);
                cb.roundRectangle(position.getLeft() + 6, position.getBottom() + 6,
                        position.getWidth() - 12, position.getHeight() - 12, 9);
                cb.stroke();
            }
        });
        Font small = new Font(Font.HELVETICA, 7, Font.BOLD, teal);
        Font big = new Font(Font.HELVETICA, 12, Font.BOLD, teal);
        Font mid = new Font(Font.HELVETICA, 10, Font.NORMAL, teal);
        String facility = prescription.getIssuingFacility() != null
                ? prescription.getIssuingFacility().getFacilityName() : "NMAS Facility";
        String date = prescription.getPrescriptionDate() != null
                ? prescription.getPrescriptionDate().format(DateTimeFormatter.ofPattern("dd MMM yyyy")) : "N/A";
        Paragraph p1 = new Paragraph("OFFICIALLY ISSUED BY", small); p1.setAlignment(Element.ALIGN_CENTER);
        Paragraph p2 = new Paragraph(facility.toUpperCase(), big); p2.setAlignment(Element.ALIGN_CENTER); p2.setSpacingBefore(3);
        Paragraph p3 = new Paragraph("Date issued: " + date, mid); p3.setAlignment(Element.ALIGN_CENTER); p3.setSpacingBefore(3);
        Paragraph p4 = new Paragraph(prescription.getDisplayNumber(), small); p4.setAlignment(Element.ALIGN_CENTER); p4.setSpacingBefore(3);
        stamp.addElement(p1); stamp.addElement(p2); stamp.addElement(p3); stamp.addElement(p4);
        wrap.addCell(stamp);
        return wrap;
    }

    private String timingSummary(PrescriptionMedication line) {
        StringBuilder sb = new StringBuilder();
        if (line.isMorning()) sb.append("Morning ");
        if (line.isAfternoon()) sb.append("Afternoon ");
        if (line.isEvening()) sb.append("Evening ");
        if (line.isNight()) sb.append("Night ");
        return sb.toString().trim();
    }

    private String safe(String s) { return s != null && !s.isBlank() ? s : "—"; }

    private Element lineSeparator() {
        PdfPTable line = new PdfPTable(1);
        line.setWidthPercentage(100);
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColor(new Color(226, 232, 240));
        cell.setBorderWidth(1);
        cell.setFixedHeight(1);
        line.addCell(cell);
        return line;
    }

    private void addInfoCell(PdfPTable table, String label, String value) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(6);
        Paragraph p = new Paragraph();
        p.add(new Chunk(label + "\n", LABEL_FONT));
        p.add(new Chunk(value, VALUE_FONT));
        cell.addElement(p);
        table.addCell(cell);
    }

    private void addHeaderCell(PdfPTable table, String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, LABEL_FONT));
        cell.setBackgroundColor(new Color(240, 253, 250));
        cell.setPadding(8);
        cell.setBorderColor(new Color(226, 232, 240));
        table.addCell(cell);
    }

    private void addBodyCell(PdfPTable table, String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, VALUE_FONT));
        cell.setPadding(8);
        cell.setBorderColor(new Color(226, 232, 240));
        table.addCell(cell);
    }
}
