package com.propertyos.document.web;

import com.propertyos.common.rls.Db;
import com.propertyos.common.security.RequiresPermission;
import com.propertyos.common.security.UserContext;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.common.web.ApiException;
import com.propertyos.common.web.Req;
import com.propertyos.document.service.DocumentService;
import com.propertyos.document.service.MediaService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * API for property and profile photos that go through review before they are shown.
 */
@RestController
public class MediaController {
    private final MediaService media;
    private final TransactionTemplate tx;

    public MediaController(MediaService media, PlatformTransactionManager tm) {
        this.media = media;
        this.tx = new TransactionTemplate(tm);
    }

    /**
     * Returns the photo rules, checker status and what the signed-in user may do.
     */
    @GetMapping("/api/documents/media/settings") @RequiresPermission("MEDIA:UPLOAD")
    public Object settings() { return media.settings(); }

    /**
     * Lists photos the user may see, filtered by status or subject.
     */
    @GetMapping("/api/documents/media") @RequiresPermission("MEDIA:UPLOAD")
    public Object list(@RequestParam Map<String, String> f) { return media.list(f); }

    /**
     * Submits a property or profile photo for checking and review.
     */
    @PostMapping(value = "/api/documents/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE) @RequiresPermission("MEDIA:UPLOAD")
    public Object upload(@RequestParam("file") MultipartFile file, @RequestParam(defaultValue = "PROPERTY") String kind, @RequestParam(required = false) String subjectId) throws java.io.IOException {
        UUID subject;
        try {
            subject = subjectId == null || subjectId.isBlank() ? null : UUID.fromString(subjectId);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Not a valid property id.");
        }
        return media.submit(kind, subject, file.getOriginalFilename(), file.getBytes());
    }

    /**
     * Returns one photo's details and review history.
     */
    @GetMapping("/api/documents/media/{id}") @RequiresPermission("MEDIA:UPLOAD")
    public Object get(@PathVariable UUID id) { return media.get(id); }

    /**
     * Returns the photo's image bytes.
     */
    @GetMapping("/api/documents/media/{id}/content") @RequiresPermission("MEDIA:UPLOAD")
    public ResponseEntity<byte[]> content(@PathVariable UUID id) {
        DocumentService.FileData f = media.content(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.mimeType())).header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore()).body(f.bytes());
    }

    /**
     * Approves or rejects a photo; a rejection needs a reason.
     */
    @PostMapping("/api/documents/media/{id}/decision") @RequiresPermission("MEDIA:REVIEW")
    public Object decide(@PathVariable UUID id, @RequestBody Map<String, Object> b) { return media.decide(id, Req.str(b, "decision"), Req.str(b, "note")); }

    /**
     * Removes a photo.
     */
    @DeleteMapping("/api/documents/media/{id}") @RequiresPermission("MEDIA:UPLOAD")
    public Object remove(@PathVariable UUID id) {
        media.remove(id);
        return Map.of("removed", true);
    }

    /** Anyone may fetch an approved photo: it is already published on a listing or profile. Pending and rejected photos return 404. */
    @GetMapping("/api/documents/public/media/{id}")
    public ResponseEntity<byte[]> publicPhoto(@PathVariable UUID id) {
        return UserContextHolder.runAsSystem(null, () -> tx.execute(s -> media.publicContent(id)
                .map(f -> ResponseEntity.ok().contentType(MediaType.parseMediaType(f.mimeType())).header("X-Content-Type-Options", "nosniff")
                        .header("Cross-Origin-Resource-Policy", "cross-origin").cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic()).body(f.bytes()))
                .orElseGet(() -> ResponseEntity.notFound().build())));
    }
}
