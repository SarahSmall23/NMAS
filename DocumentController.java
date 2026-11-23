package za.co.nmas.nmas.controller;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import za.co.nmas.nmas.entity.UploadedMedicalDocument;
import za.co.nmas.nmas.service.MedicalDocumentService;
import za.co.nmas.nmas.service.PatientService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Controller
@RequestMapping("/documents")
public class DocumentController {

    private final MedicalDocumentService documentService;
    private final PatientService patientService;

    public DocumentController(MedicalDocumentService documentService, PatientService patientService) {
        this.documentService = documentService;
        this.patientService = patientService;
    }

    @GetMapping("/upload")
    public String uploadForm(@RequestParam(required = false) Long patientId, Model model) {
        if (patientId != null) {
            model.addAttribute("patient", patientService.findById(patientId));
        }
        model.addAttribute("patients", patientService.findAll());
        return "upload-medical-document";
    }

    @PostMapping("/upload")
    public String upload(@RequestParam Long patientId,
                         @RequestParam MultipartFile file,
                         @RequestParam String documentType,
                         @RequestParam(required = false) String notes,
                         Authentication auth,
                         Model model) {
        try {
            documentService.upload(patientId, file, documentType, notes, auth.getName());
            return "redirect:/documents/patient/" + patientId;
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("patients", patientService.findAll());
            return "upload-medical-document";
        } catch (IOException e) {
            model.addAttribute("error", "File upload failed. Please try again.");
            model.addAttribute("patients", patientService.findAll());
            return "upload-medical-document";
        }
    }

    @GetMapping("/patient/{patientId}")
    public String patientDocuments(@PathVariable Long patientId, Model model) {
        model.addAttribute("documents", documentService.findByPatient(patientId));
        model.addAttribute("patient", patientService.findById(patientId));
        return "patient-documents";
    }

    @GetMapping("/download/{id}")
    public ResponseEntity<Resource> download(@PathVariable Long id) throws IOException {
        UploadedMedicalDocument doc = documentService.findById(id);
        Path filePath = Paths.get(doc.getFilePath());
        Resource resource = new FileSystemResource(filePath);
        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        String contentType = doc.getContentType() != null ? doc.getContentType() : "application/octet-stream";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + doc.getFileName() + "\"")
                .body(resource);
    }
}
