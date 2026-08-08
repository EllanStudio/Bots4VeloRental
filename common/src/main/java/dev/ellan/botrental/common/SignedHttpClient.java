package dev.ellan.botrental.common;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class SignedHttpClient {
    private final URI baseUri;
    private final HmacAuth auth;
    private final HttpClient client;

    public SignedHttpClient(String baseUrl, String secret) {
        this.baseUri = URI.create(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        this.auth = new HmacAuth(secret);
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public <T> T post(String path, Object request, Class<T> responseType) throws Exception {
        byte[] body = Json.write(request);
        HmacAuth.SignedHeaders signed = auth.sign("POST", path, body);
        HttpRequest httpRequest = HttpRequest.newBuilder(baseUri.resolve(path))
            .timeout(Duration.ofSeconds(8))
            .header("Content-Type", "application/json; charset=utf-8")
            .header(HmacAuth.TIMESTAMP_HEADER, Long.toString(signed.timestamp()))
            .header(HmacAuth.NONCE_HEADER, signed.nonce())
            .header(HmacAuth.SIGNATURE_HEADER, signed.signature())
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
        HttpResponse<byte[]> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " from " + path);
        }
        return Json.read(response.body(), responseType);
    }
}
