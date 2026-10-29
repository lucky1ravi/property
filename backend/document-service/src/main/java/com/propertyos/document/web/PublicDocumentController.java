package com.propertyos.document.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.common.client.ServiceClient;
import com.propertyos.common.rls.Db;
import com.propertyos.common.security.SignedLinks;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.common.web.ApiException;
import com.propertyos.document.service.DocumentRequirements;
import com.propertyos.document.service.DocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

/**
 * Applicants have no account, so they upload with the signed link they receive when they apply.
 * The link only allows adding files to that one application and listing what they have sent.
 */
@RestController
@RequestMapping("/api/documents/public/applications/{applicationId}")
public class PublicDocumentController {
    private static final Logger log = LoggerFactory.getLogger(PublicDocumentController.class);
    private final DocumentService docs;
    private final DocumentRequirements requirements;
    private final SignedLinks links;
    private final ServiceClient client;
    private final Db db;
    private final TransactionTemplate tx;

    public PublicDocumentController(DocumentService docs, DocumentRequirements requirements, SignedLinks links, ServiceClient client, Db db, PlatformTransactionManager tm) {
        this.docs = docs;
        this.requirements = requirements;
        this.links = links;
        this.client = client;
        this.db = db;
        this.tx = new TransactionTemplate(tm);
    }

    private JsonNode application(UUID id, String token) {
        if (!links.verify("application-upload", id, token)) throw ApiException.forbidden("This upload link isn't valid. Use the link from your application confirmation.");
        JsonNode a = UserContextHolder.runAsSystem(null, () -> client.get(ServiceClient.Service.LEASING, "/internal/leasing/applications/" + id + "/brief"));
        if (a == null || a.isEmpty()) throw ApiException.notFound("Application");
        if (List.of("REJECTED", "WITHDRAWN", "LEASED").contains(a.path("status").asText()))
            throw ApiException.conflict("This application is closed, so files can't be added.");
        return a;
    }

    /**
     * Public (signed link): lists what an applicant has uploaded, the checklist and the accepted document types.
     */
    @GetMapping("/files")
    public Object files(@PathVariable UUID applicationId, @RequestParam String token) {
        JsonNode a = application(applicationId, token);
        UUID org = UUID.fromString(a.path("org_id").asText());
        return UserContextHolder.runAsSystem(org, () -> tx.execute(s -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("applicant", a.path("applicant_name").asText());
            out.put("home", a.path("label").asText());
            out.put("files", db.list("select doc_type as type, file_name, review_status, review_note, created_at from document.document where entity_type = 'APPLICATION' and entity_id = ? and status = 'ACTIVE' order by created_at", applicationId));
            out.put("checklist", requirements.requirements("APPLICATION", applicationId).get("items"));
            List<String> types = new ArrayList<>();
            for (String c : List.of("IDENTITY", "APPLICATION")) docs.categories().path(c).path("types").forEach(t -> types.add(t.asText()));
            types.remove("Screening report");
            out.put("types", types);
            return out;
        }));
    }

    /**
     * Public (signed link): adds a file to one application.
     */
    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Object upload(@PathVariable UUID applicationId, @RequestParam String token, @RequestParam("file") MultipartFile file,
                         @RequestParam(required = false) String docType) throws java.io.IOException {
        JsonNode a = application(applicationId, token);
        UUID org = UUID.fromString(a.path("org_id").asText());
        byte[] data = file.getBytes();
        try {
            return UserContextHolder.runAsSystem(org, () -> {
                String category = null;
                if (docType != null) {
                    if (docs.categories().path("IDENTITY").path("types").toString().contains("\"" + docType + "\"")) category = "IDENTITY";
                    else if (docs.categories().path("APPLICATION").path("types").toString().contains("\"" + docType + "\"")) category = "APPLICATION";
                }
                String cat = category;
                return tx.execute(s -> docs.uploadForApplicant(applicationId, a.path("applicant_name").asText(), file.getOriginalFilename(), data, cat, docType));
            });
        } catch (RuntimeException e) {
            log.warn("Applicant upload failed: {}", e.toString());
            throw e;
        }
    }
}
