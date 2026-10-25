package io.github.assami.audit.sample;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A host application's own Spring Data repository must still be found next to the starter's.
 * The starter once used {@code @EnableJpaRepositories}, which switched Boot's repository scan off.
 */
@SpringBootTest(properties = {
        "audit.security.jwk-set-uri=http://localhost:1/jwks",
        "audit.security.issuer=http://test-issuer"})
class HostRepositoryTest {

    @Autowired(required = false) HostRepository hostRepository;

    @Test
    void hostRepositoryIsRegistered() {
        assertThat(hostRepository).isNotNull();
    }
}
