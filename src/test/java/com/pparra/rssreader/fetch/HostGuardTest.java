package com.pparra.rssreader.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import org.junit.jupiter.api.Test;

class HostGuardTest {

    @Test
    void blocksLoopbackLinkLocalAndUnspecifiedAddresses() throws Exception {
        assertThat(HostGuard.isBlocked(InetAddress.getByName("127.0.0.1"))).isTrue();
        assertThat(HostGuard.isBlocked(InetAddress.getByName("::1"))).isTrue();
        assertThat(HostGuard.isBlocked(InetAddress.getByName("169.254.169.254"))).isTrue();
        assertThat(HostGuard.isBlocked(InetAddress.getByName("0.0.0.0"))).isTrue();
    }

    @Test
    void allowsPublicAndPrivateLanAddresses() throws Exception {
        assertThat(HostGuard.isBlocked(InetAddress.getByName("93.184.216.34"))).isFalse();
        assertThat(HostGuard.isBlocked(InetAddress.getByName("192.168.1.20"))).isFalse();
    }

    @Test
    void rejectsInternalHostsAndNonHttpSchemes() {
        assertThatThrownBy(() -> HostGuard.check(URI.create("http://169.254.169.254/latest/meta-data")))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> HostGuard.check(URI.create("http://127.0.0.1:8080/")))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> HostGuard.check(URI.create("file:///etc/passwd"))).isInstanceOf(IOException.class);
    }
}
