package dev.ellan.botrental.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HmacAuthTest {
    @Test
    void acceptsOnceAndRejectsReplayOrTampering() {
        HmacAuth auth = new HmacAuth("01234567890123456789012345678901");
        byte[] body = "payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        HmacAuth.SignedHeaders signed = auth.sign("POST", "/v1/test", body);

        assertThat(auth.verify("POST", "/v1/test", body, Long.toString(signed.timestamp()),
            signed.nonce(), signed.signature())).isTrue();
        assertThat(auth.verify("POST", "/v1/test", body, Long.toString(signed.timestamp()),
            signed.nonce(), signed.signature())).isFalse();

        HmacAuth.SignedHeaders tampered = auth.sign("POST", "/v1/test", body);
        assertThat(auth.verify("POST", "/v1/test", "other".getBytes(), Long.toString(tampered.timestamp()),
            tampered.nonce(), tampered.signature())).isFalse();
    }
}
