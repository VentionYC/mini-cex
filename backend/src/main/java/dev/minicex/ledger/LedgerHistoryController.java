package dev.minicex.ledger;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.minicex.auth.AuthModels.AuthenticatedUser;
import dev.minicex.ledger.LedgerHistoryService.Page;

@RestController
public class LedgerHistoryController {

    private final LedgerHistoryService history;

    public LedgerHistoryController(LedgerHistoryService history) {
        this.history = history;
    }

    @GetMapping("/api/ledger/history")
    public ResponseEntity<Page> history(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(name = "limit", defaultValue = "20") int limit,
            @RequestParam(name = "beforeId", required = false) Long beforeId) {

        Page page = history.history(
                principal.user().id(),
                limit,
                beforeId);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(page);
    }
}