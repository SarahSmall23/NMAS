package za.co.nmas.nmas.report;

import java.util.ArrayList;
import java.util.List;

public class ReportDocument {
    private final String title;
    private final String subtitle;
    private final List<ReportSection> sections = new ArrayList<>();

    public ReportDocument(String title, String subtitle) {
        this.title = title;
        this.subtitle = subtitle;
    }

    public ReportDocument add(ReportSection s) { sections.add(s); return this; }

    /** A copy of this report containing only the section at the given index (title gets the section name). */
    public ReportDocument onlySection(Integer index) {
        if (index == null || index < 0 || index >= sections.size()) return this;
        ReportSection s = sections.get(index);
        ReportDocument d = new ReportDocument(title + " - " + s.getTitle(), subtitle);
        d.sections.add(s);
        return d;
    }

    public String getTitle() { return title; }
    public String getSubtitle() { return subtitle; }
    public List<ReportSection> getSections() { return sections; }
}
