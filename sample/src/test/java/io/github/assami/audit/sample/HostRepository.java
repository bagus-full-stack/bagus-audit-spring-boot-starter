package io.github.assami.audit.sample;

import org.springframework.data.jpa.repository.JpaRepository;

interface HostRepository extends JpaRepository<HostEntity, String> {}
