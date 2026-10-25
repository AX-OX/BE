package com.axnavigator.recovery;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/agent-commands")
@ConditionalOnProperty(name="ax.recovery.enabled", havingValue="true")
public class CommandController {
    private final CommandQueue queue;
    public CommandController(CommandQueue queue) { this.queue = queue; }
    public record Lease(@NotBlank String leaseToken) {}
    public record Failure(@NotBlank String leaseToken, boolean retryable) {}
    @PostMapping("/claim")
    public ResponseEntity<CommandQueue.Claimed> claim() {
        var command = queue.claim();
        return command == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(command);
    }
    @PostMapping("/{id}/heartbeat") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void heartbeat(@PathVariable String id, @Valid @RequestBody Lease lease) { queue.heartbeat(id, lease.leaseToken()); }
    @GetMapping(value="/{id}/input", produces=MediaType.APPLICATION_PDF_VALUE)
    public byte[] input(@PathVariable String id, @RequestHeader("X-Command-Lease") String token) { return queue.input(id, token); }
    @PostMapping("/{id}/complete") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void complete(@PathVariable String id, @Valid @RequestBody Lease lease) { queue.complete(id, lease.leaseToken()); }
    @PostMapping("/{id}/fail") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void fail(@PathVariable String id, @Valid @RequestBody Failure failure) { queue.fail(id, failure.leaseToken(), failure.retryable()); }
    @Scheduled(fixedDelay=60000)
    public void maintenance() { queue.maintenance(); }
}
