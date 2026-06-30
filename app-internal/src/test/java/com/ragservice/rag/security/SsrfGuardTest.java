package com.ragservice.rag.security;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SsrfGuard 단위 테스트.
 * isPrivateOrReserved()는 InetAddress를 직접 받으므로 네트워크 호출 없이 테스트 가능.
 */
class SsrfGuardTest {

    private final SsrfGuard guard = new SsrfGuard();

    @Test void privateIp_10_x_blocked() throws Exception {
        assertThat(guard.isPrivateOrReserved(InetAddress.getByAddress(new byte[]{10, 0, 0, 1}))).isTrue();
    }

    @Test void privateIp_172_16_blocked() throws Exception {
        assertThat(guard.isPrivateOrReserved(
                InetAddress.getByAddress(new byte[]{(byte)172, 16, 0, 1}))).isTrue();
    }

    @Test void privateIp_192_168_blocked() throws Exception {
        assertThat(guard.isPrivateOrReserved(
                InetAddress.getByAddress(new byte[]{(byte)192, (byte)168, 1, 1}))).isTrue();
    }

    @Test void loopback_127_blocked() throws Exception {
        assertThat(guard.isPrivateOrReserved(
                InetAddress.getByAddress(new byte[]{127, 0, 0, 1}))).isTrue();
    }

    @Test void awsMetadata_169_254_blocked() throws Exception {
        assertThat(guard.isPrivateOrReserved(
                InetAddress.getByAddress(new byte[]{(byte)169, (byte)254, (byte)169, (byte)254}))).isTrue();
    }

    @Test void publicIp_8_8_8_8_allowed() throws Exception {
        assertThat(guard.isPrivateOrReserved(
                InetAddress.getByAddress(new byte[]{8, 8, 8, 8}))).isFalse();
    }

    @Test void invalidScheme_ftp_denied() {
        assertThat(guard.validate("ftp://example.com").allowed()).isFalse();
    }

    @Test void nullUrl_denied() {
        assertThat(guard.validate(null).allowed()).isFalse();
    }

    @Test void blankUrl_denied() {
        assertThat(guard.validate("  ").allowed()).isFalse();
    }
}
