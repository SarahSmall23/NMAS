package za.co.nmas.nmas.report;

import java.util.ArrayList;
import java.util.List;

/** One titled table inside a report. Plain getters so Thymeleaf can read it. */
public class ReportSection {
    private final String title;
    private final List<String> headers;
    private final List<List<String>> rows = new ArrayList<>();

    public ReportSection(String title, List<String> headers) {
        this.title = title;
        this.headers = headers;
    }

    public ReportSection add(String... cells) {
        rows.add(List.of(cells));
        return this;
    }

    public String getTitle() { return title; }
    public List<String> getHeaders() { return headers; }
    public List<List<String>> getRows() { return rows; }
}
