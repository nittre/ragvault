package com.ragvault.core.service;

import com.ragvault.core.domain.DataSourceConfig;
import com.ragvault.core.dto.DataSourceRequest;
import com.ragvault.core.repository.DataSourceConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

/**
 * 데이터소스 CRUD + 연결 테스트 서비스.
 *
 * SSH 터널이 필요한 경우 openConnection() 이 투명하게 처리한다.
 * 호출자는 반환된 Connection 을 try-with-resources 로 닫으면 SSH 터널도 함께 닫힌다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataSourceConfigService {

    private final DataSourceConfigRepository repository;
    private final DataSourceEncryptionService encryptionService;
    private final SshTunnelService sshTunnelService;

    // ── CRUD ──────────────────────────────────────────────────────────────────

    @Transactional
    public DataSourceConfig create(DataSourceRequest req) {
        DataSourceConfig ds = DataSourceConfig.builder()
                .name(req.name())
                .description(req.description())
                .dbType(req.dbType() != null ? req.dbType() : "mysql")
                .host(req.host())
                .port(req.port() != null ? req.port() : 3306)
                .dbName(req.dbName())
                .username(req.username())
                .passwordEnc(encryptionService.encrypt(req.password()))
                .isActive(true)
                .sshEnabled(Boolean.TRUE.equals(req.sshEnabled()))
                .sshHost(req.sshHost())
                .sshPort(req.sshPort() != null ? req.sshPort() : 22)
                .sshUser(req.sshUser())
                .sshPrivateKeyEnc(encryptSecret(req.sshPrivateKey()))
                .sshPassphraseEnc(encryptSecret(req.sshPassphrase()))
                .build();
        return repository.save(ds);
    }

    @Transactional
    public DataSourceConfig update(Integer id, DataSourceRequest req) {
        DataSourceConfig ds = findById(id);
        if (req.name() != null)        ds.setName(req.name());
        if (req.description() != null) ds.setDescription(req.description());
        if (req.dbType() != null)      ds.setDbType(req.dbType());
        if (req.host() != null)        ds.setHost(req.host());
        if (req.port() != null)        ds.setPort(req.port());
        if (req.dbName() != null)      ds.setDbName(req.dbName());
        if (req.username() != null)    ds.setUsername(req.username());
        if (req.password() != null && !req.password().isBlank()) {
            ds.setPasswordEnc(encryptionService.encrypt(req.password()));
        }
        if (req.sshEnabled() != null)  ds.setSshEnabled(req.sshEnabled());
        if (req.sshHost() != null)     ds.setSshHost(req.sshHost());
        if (req.sshPort() != null)     ds.setSshPort(req.sshPort());
        if (req.sshUser() != null)     ds.setSshUser(req.sshUser());
        if (req.sshPrivateKey() != null && !req.sshPrivateKey().isBlank()) {
            ds.setSshPrivateKeyEnc(encryptionService.encrypt(req.sshPrivateKey()));
        }
        if (req.sshPassphrase() != null && !req.sshPassphrase().isBlank()) {
            ds.setSshPassphraseEnc(encryptionService.encrypt(req.sshPassphrase()));
        }
        return repository.save(ds);
    }

    @Transactional
    public void delete(Integer id) {
        repository.delete(findById(id));
    }

    public List<DataSourceConfig> findAll() { return repository.findAll(); }

    public DataSourceConfig findById(Integer id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("데이터소스를 찾을 수 없습니다. id=" + id));
    }

    public List<DataSourceConfig> findActiveAll() { return repository.findByIsActiveTrue(); }

    // ── 연결 ──────────────────────────────────────────────────────────────────

    /**
     * 데이터소스에 대한 JDBC Connection 을 반환한다.
     *
     * ssh_enabled = true 이면 SSH 터널을 먼저 열고, 반환된 Connection 의 close() 호출 시
     * 터널도 함께 닫히는 프록시 Connection 을 반환한다.
     */
    public Connection openConnection(DataSourceConfig config) throws Exception {
        if (!config.isSshEnabled()) {
            return DriverManager.getConnection(
                    buildJdbcUrl(config), config.getUsername(), getDecryptedPassword(config));
        }

        String pemKey = encryptionService.decrypt(config.getSshPrivateKeyEnc());
        String passphrase = config.getSshPassphraseEnc() != null && !config.getSshPassphraseEnc().isBlank()
                ? encryptionService.decrypt(config.getSshPassphraseEnc())
                : null;

        SshTunnelService.SshTunnel tunnel = sshTunnelService.openTunnel(
                config.getSshHost(),
                config.getSshPort() != null ? config.getSshPort() : 22,
                config.getSshUser(),
                pemKey, passphrase,
                config.getHost(), config.getPort()
        );

        try {
            String url = buildTunneledJdbcUrl(config, tunnel.localPort());
            Connection conn = DriverManager.getConnection(url, config.getUsername(), getDecryptedPassword(config));
            return proxyWithTunnel(conn, tunnel);
        } catch (Exception e) {
            tunnel.close();
            throw e;
        }
    }

    /**
     * Connection 프록시: close() 호출 시 SSH 터널도 함께 닫힌다.
     */
    private Connection proxyWithTunnel(Connection conn, SshTunnelService.SshTunnel tunnel) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class[]{Connection.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        try { conn.close(); } finally { tunnel.close(); }
                        return null;
                    }
                    try {
                        return method.invoke(conn, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                }
        );
    }

    // ── 연결 테스트 ───────────────────────────────────────────────────────────

    public record ConnectionTestResult(boolean connected, String reason) {}

    public ConnectionTestResult testConnection(Integer id) {
        DataSourceConfig ds = findById(id);
        try (Connection conn = openConnection(ds)) {
            boolean valid = conn.isValid(5);
            return new ConnectionTestResult(valid, valid ? "연결 성공" : "isValid() 실패");
        } catch (Exception e) {
            log.warn("Connection test failed for datasource id={}: {}", id, e.getMessage());
            return new ConnectionTestResult(false, e.getMessage());
        }
    }

    // ── URL / 복호화 헬퍼 ─────────────────────────────────────────────────────

    public String buildJdbcUrl(DataSourceConfig config) {
        String scheme = "mariadb".equalsIgnoreCase(config.getDbType()) ? "jdbc:mariadb" : "jdbc:mysql";
        return scheme + "://" + config.getHost() + ":" + config.getPort() + "/" + config.getDbName()
                + "?connectTimeout=5000&socketTimeout=10000";
    }

    private String buildTunneledJdbcUrl(DataSourceConfig config, int localPort) {
        String scheme = "mariadb".equalsIgnoreCase(config.getDbType()) ? "jdbc:mariadb" : "jdbc:mysql";
        return scheme + "://localhost:" + localPort + "/" + config.getDbName()
                + "?connectTimeout=5000&socketTimeout=30000";
    }

    public String getDecryptedPassword(DataSourceConfig config) {
        return encryptionService.decrypt(config.getPasswordEnc());
    }

    private String encryptSecret(String value) {
        return (value != null && !value.isBlank()) ? encryptionService.encrypt(value) : null;
    }
}
