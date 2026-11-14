package com.ebank.config;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.util.Credentials;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;
import org.springframework.vault.core.lease.SecretLeaseContainer;
import org.springframework.vault.core.lease.domain.Lease;
import org.springframework.vault.core.lease.domain.RequestedSecret;
import org.springframework.vault.core.lease.event.AfterSecretLeaseRenewedEvent;
import org.springframework.vault.core.lease.event.LeaseErrorListener;
import org.springframework.vault.core.lease.event.LeaseListener;
import org.springframework.vault.core.lease.event.SecretLeaseCreatedEvent;
import org.springframework.vault.core.lease.event.SecretLeaseEvent;
import org.springframework.vault.core.lease.event.SecretLeaseExpiredEvent;

/**
 * Keeps the connection pool on valid credentials when they come from Vault's database engine
 * ({@code VAULT_DATABASE_ENABLED=true}, see infra/vault/README.md).
 *
 * <p>Spring Cloud Vault gets a database user at startup and renews its lease, but only up to the
 * role's {@code max_ttl}; then it reports the lease as expired and stops. From that point this
 * class asks for a <em>rotating</em> secret, which Spring Vault replaces on its own before each
 * expiry, and hands every new user to Hikari: new connections log in with it, idle connections of
 * the old user are closed at once, busy ones when they are returned. Vault drops the old user
 * when its lease ends.
 *
 * <p>Metrics: {@code ebank_vault_database_credentials_expiry_seconds} (time left on the current
 * lease, after its first renewal or rotation) and {@code ebank_vault_database_credentials_rotations_total{outcome}}.
 */
@Component
@ConditionalOnBooleanProperty("spring.cloud.vault.database.enabled")
class VaultDatabaseCredentials implements LeaseListener, LeaseErrorListener {

    private static final Logger log = LoggerFactory.getLogger(VaultDatabaseCredentials.class);

    private final SecretLeaseContainer leases;
    private final HikariDataSource pool;
    private final String path;
    private final Clock clock;
    private final AtomicReference<RequestedSecret> rotating = new AtomicReference<>();
    private final AtomicReference<Instant> expiresAt = new AtomicReference<>();
    private final Counter rotated;
    private final Counter failed;

    @Autowired
    VaultDatabaseCredentials(
            SecretLeaseContainer leases,
            DataSource dataSource,
            MeterRegistry meters,
            @Value("${spring.cloud.vault.database.backend:database}") String backend,
            @Value("${spring.cloud.vault.database.role}") String role) throws SQLException {
        this(leases, dataSource.unwrap(HikariDataSource.class), meters, backend + "/creds/" + role, Clock.systemUTC());
    }

    VaultDatabaseCredentials(SecretLeaseContainer leases, HikariDataSource pool, MeterRegistry meters, String path, Clock clock) {
        this.leases = leases;
        this.pool = pool;
        this.path = path;
        this.clock = clock;
        this.rotated = Counter.builder("ebank.vault.database.credentials.rotations")
                .description("Database users received from Vault after startup")
                .tag("outcome", "success")
                .register(meters);
        this.failed = Counter.builder("ebank.vault.database.credentials.rotations")
                .description("Database users received from Vault after startup")
                .tag("outcome", "failure")
                .register(meters);
        Gauge.builder("ebank.vault.database.credentials.expiry", this, VaultDatabaseCredentials::secondsLeft)
                .description("Time left on the lease of the database user in use")
                .baseUnit("seconds")
                .register(meters);
        leases.addLeaseListener(this);
        leases.addErrorListener(this);
    }

    @Override
    public void onLeaseEvent(SecretLeaseEvent event) {
        if (!path.equals(event.getSource().getPath())) {
            return;
        }
        switch (event) {
            // The startup lease reached max_ttl: switch to a rotating secret, once.
            case SecretLeaseExpiredEvent expired when expired.getSource().getMode() == RequestedSecret.Mode.RENEW -> {
                if (rotating.compareAndSet(null, RequestedSecret.rotating(path))) {
                    log.info("Database credentials reached their maximum lifetime, requesting new ones from Vault");
                    leases.addRequestedSecret(rotating.get());
                }
            }
            // A new user, from the rotating secret (SecretLeaseRotatedEvent is a SecretLeaseCreatedEvent).
            case SecretLeaseCreatedEvent created when created.getSource().getMode() == RequestedSecret.Mode.ROTATE -> {
                useCredentials(created.getSecrets());
                track(created.getLease());
            }
            case SecretLeaseCreatedEvent created -> track(created.getLease());
            case AfterSecretLeaseRenewedEvent renewed -> track(renewed.getLease());
            default -> {}
        }
    }

    @Override
    public void onLeaseError(SecretLeaseEvent event, Exception error) {
        if (path.equals(event.getSource().getPath())) {
            failed.increment();
            // The message names the path and Vault's status, never the credentials.
            log.warn("Vault database credentials: {}", error.getMessage());
        }
    }

    private void useCredentials(Map<String, Object> secrets) {
        Object username = secrets.get("username");
        Object password = secrets.get("password");
        if (username == null || password == null) {
            failed.increment();
            log.error("Vault returned database credentials without a username or password at {}", path);
            return;
        }
        pool.getHikariConfigMXBean().setCredentials(Credentials.of(username.toString(), password.toString()));
        // Idle connections of the previous user close now, busy ones when they go back to the pool.
        if (pool.getHikariPoolMXBean() != null) {
            pool.getHikariPoolMXBean().softEvictConnections();
        }
        rotated.increment();
        log.info("Connection pool switched to a new database user from Vault");
    }

    private void track(Lease lease) {
        if (lease != null && lease.hasLeaseId()) {
            expiresAt.set(clock.instant().plus(lease.getLeaseDuration()));
        }
    }

    double secondsLeft() {
        Instant end = expiresAt.get();
        return end == null ? Double.NaN : Duration.between(clock.instant(), end).toMillis() / 1000.0;
    }
}
