package com.ragvault.core.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMDecryptorProvider;
import org.bouncycastle.openssl.PEMEncryptedKeyPair;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.StringReader;
import java.net.ServerSocket;
import java.security.KeyPair;
import java.security.Security;
import java.util.concurrent.TimeUnit;

/**
 * SSH 터널 서비스.
 *
 * Bastion EC2 에 PEM 키로 인증 후 로컬 포트 포워딩을 설정한다.
 * 호출자는 반환된 SshTunnel 을 try-with-resources 로 닫아야 한다.
 *
 * 사용 예:
 *   try (SshTunnel tunnel = sshTunnelService.openTunnel(...)) {
 *       String url = "jdbc:mariadb://localhost:" + tunnel.localPort() + "/db";
 *       ...
 *   }
 */
@Slf4j
@Service
public class SshTunnelService {

    @PostConstruct
    void registerBouncyCastle() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    public record SshTunnel(ClientSession session, SshClient client, int localPort)
            implements AutoCloseable {
        @Override
        public void close() {
            try { session.close(false); } catch (Exception e) {
                log.debug("SSH session close: {}", e.getMessage());
            }
            try { client.stop(); } catch (Exception e) {
                log.debug("SSH client stop: {}", e.getMessage());
            }
            log.debug("SSH tunnel closed (local port {})", localPort);
        }
    }

    /**
     * SSH 터널을 열고 포트 포워딩을 설정한다.
     *
     * @param bastionHost   Bastion 서버 IP/호스트
     * @param bastionPort   Bastion SSH 포트 (보통 22)
     * @param bastionUser   SSH 사용자명 (e.g. ec2-user)
     * @param pemContent    PEM 개인키 전체 내용 (평문)
     * @param passphrase    PEM 키 passphrase — 없으면 null/blank
     * @param targetHost    실제 DB 호스트 (RDS 엔드포인트 등)
     * @param targetPort    실제 DB 포트
     * @return 열린 터널 (localPort 로 DB 에 접근)
     */
    public SshTunnel openTunnel(
            String bastionHost, int bastionPort, String bastionUser,
            String pemContent, String passphrase,
            String targetHost, int targetPort) {

        SshClient client = SshClient.setUpDefaultClient();
        client.start();

        try {
            KeyPair keyPair = loadKeyPair(pemContent, passphrase);

            ConnectFuture connectFuture = client.connect(bastionUser, bastionHost, bastionPort);
            ClientSession session = connectFuture.verify(15, TimeUnit.SECONDS).getSession();

            session.addPublicKeyIdentity(keyPair);
            session.auth().verify(15, TimeUnit.SECONDS);

            int localPort = findFreePort();
            session.startLocalPortForwarding(
                    new SshdSocketAddress("localhost", localPort),
                    new SshdSocketAddress(targetHost, targetPort)
            );

            log.info("SSH tunnel opened: localhost:{} → {}:{} via {}@{}:{}",
                    localPort, targetHost, targetPort, bastionUser, bastionHost, bastionPort);
            return new SshTunnel(session, client, localPort);

        } catch (Exception e) {
            try { client.stop(); } catch (Exception ignored) {}
            throw new RuntimeException("SSH 터널 연결 실패 (" + bastionHost + "): " + e.getMessage(), e);
        }
    }

    private KeyPair loadKeyPair(String pemContent, String passphrase) throws IOException {
        try (PEMParser parser = new PEMParser(new StringReader(pemContent))) {
            Object obj = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter()
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME);

            if (obj instanceof PEMEncryptedKeyPair encrypted) {
                if (passphrase == null || passphrase.isBlank()) {
                    throw new IllegalArgumentException("PEM 키에 passphrase 가 필요합니다.");
                }
                PEMDecryptorProvider decryptor =
                        new JcePEMDecryptorProviderBuilder().build(passphrase.toCharArray());
                return converter.getKeyPair(encrypted.decryptKeyPair(decryptor));
            }
            if (obj instanceof PEMKeyPair keyPair) {
                return converter.getKeyPair(keyPair);
            }
            throw new IllegalArgumentException(
                    "지원하지 않는 PEM 키 형식입니다: " + (obj != null ? obj.getClass().getSimpleName() : "null"));
        }
    }

    private int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        }
    }
}
