package za.co.nmas.nmas.service;

import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import za.co.nmas.nmas.report.ReportDocument;
import za.co.nmas.nmas.report.ReportSection;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Turns a ReportDocument into a downloadable PDF or a CSV "soft copy" (opens in Excel). */
@Service
public class ReportExportService {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm");

    public ResponseEntity<byte[]> csv(ReportDocument doc, String fileBase) {
        StringBuilder sb = new StringBuilder("\uFEFF"); // BOM so Excel reads UTF-8 correctly
        sb.append(cell(doc.getTitle())).append('\n');
        sb.append(cell(doc.getSubtitle())).append('\n');
        sb.append(cell("Generated " + LocalDateTime.now().format(STAMP))).append("\n\n");
        for (ReportSection s : doc.getSections()) {
            sb.append(cell(s.getTitle())).append('\n');
            sb.append(row(s.getHeaders())).append('\n');
            for (List<String> r : s.getRows()) sb.append(row(r)).append('\n');
            sb.append('\n');
        }
        return download(sb.toString().getBytes(StandardCharsets.UTF_8), fileBase + ".csv", "text/csv; charset=UTF-8");
    }

    public ResponseEntity<byte[]> pdf(ReportDocument doc, String fileBase) {
        try {
            Document d = new Document(PageSize.A4.rotate(), 30, 30, 30, 30);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfWriter.getInstance(d, out);
            d.open();

            Font title = new Font(Font.HELVETICA, 18, Font.BOLD, new Color(15, 118, 110));
            Font sub = new Font(Font.HELVETICA, 10, Font.NORMAL, new Color(100, 116, 139));
            Font sec = new Font(Font.HELVETICA, 12, Font.BOLD, new Color(15, 23, 42));
            Font head = new Font(Font.HELVETICA, 8, Font.BOLD, Color.WHITE);
            Font body = new Font(Font.HELVETICA, 8, Font.NORMAL, new Color(15, 23, 42));

            d.add(new Paragraph(doc.getTitle(), title));
            Paragraph sp = new Paragraph(doc.getSubtitle() + "   |   Generated " + LocalDateTime.now().format(STAMP)
                    + "   |   NMAS — National Medical Access System", sub);
            sp.setSpacingAfter(14);
            d.add(sp);

            for (ReportSection s : doc.getSections()) {
                Paragraph st = new Paragraph(s.getTitle(), sec);
                st.setSpacingBefore(8);
                st.setSpacingAfter(6);
                d.add(st);
                if (s.getRows().isEmpty()) {
                    d.add(new Paragraph("No records.", sub));
                    continue;
                }
                PdfPTable t = new PdfPTable(s.getHeaders().size());
                t.setWidthPercentage(100);
                t.setHeaderRows(1);
                for (String h : s.getHeaders()) {
                    PdfPCell c = new PdfPCell(new Phrase(h, head));
                    c.setBackgroundColor(new Color(28, 43, 43));
                    c.setPadding(5);
                    t.addCell(c);
                }
                boolean alt = false;
                for (List<String> r : s.getRows()) {
                    for (String v : r) {
                        PdfPCell c = new PdfPCell(new Phrase(v == null ? "" : v, body));
                        c.setPadding(4);
                        c.setBorderColor(new Color(226, 232, 240));
                        if (alt) c.setBackgroundColor(new Color(248, 250, 252));
                        t.addCell(c);
                    }
                    alt = !alt;
                }
                d.add(t);
            }
            d.close();
            return download(out.toByteArray(), fileBase + ".pdf", "application/pdf");
        } catch (Exception e) {
            throw new RuntimeException("Could not build report PDF: " + e.getMessage(), e);
        }
    }

    private ResponseEntity<byte[]> download(byte[] bytes, String filename, String contentType) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .body(bytes);
    }

    private String row(List<String> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(cell(cells.get(i)));
        }
        return sb.toString();
    }

    private String cell(String v) {
        if (v == null) return "";
        String x = v.replace("\"", "\"\"");
        return (x.contains(",") || x.contains("\"") || x.contains("\n")) ? "\"" + x + "\"" : x;
    }
}
