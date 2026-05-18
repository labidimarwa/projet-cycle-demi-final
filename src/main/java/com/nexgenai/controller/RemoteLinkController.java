package com.nexgenai.controller;

import com.nexgenai.service.RemoteLinkService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Generates a shareable remote-work application link for a given job.
 * POST /jobs/{id}/remote-link → returns { link: "https://..." }
 */
@RestController
@RequestMapping("/jobs")
@CrossOrigin(origins = "http://localhost:4200")
public class RemoteLinkController {

    private final RemoteLinkService remoteLinkService;

    public RemoteLinkController(RemoteLinkService remoteLinkService) {
        this.remoteLinkService = remoteLinkService;
    }

    @PostMapping("/{id}/remote-link")
    public ResponseEntity<Map<String, String>> generateRemoteLink(@PathVariable String id) {
        String link = remoteLinkService.generateLink(id);
        return ResponseEntity.ok(Map.of("link", link));
    }
}