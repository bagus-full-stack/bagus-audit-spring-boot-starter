package io.github.assami.audit.sample;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** A host application's own controller, with a bug: it throws. The audit starter must not handle that error. */
@RestController
class HostBugController {

    @GetMapping("/host/boom")
    String boom() {
        throw new IllegalArgumentException("host internal detail");
    }
}
