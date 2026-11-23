package za.co.nmas.nmas.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import za.co.nmas.nmas.service.SearchService;

@Controller
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/search-records")
    public String searchRecords(@RequestParam(required = false) String searchTerm, Model model) {
        if (searchTerm == null || searchTerm.isBlank()) {
            return "search-records";
        }
        model.addAttribute("results", searchService.search(searchTerm));
        model.addAttribute("searchTerm", searchTerm);
        return "search-records";
    }
}
