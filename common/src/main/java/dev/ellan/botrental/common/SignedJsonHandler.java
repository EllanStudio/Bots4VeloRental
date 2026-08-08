package dev.ellan.botrental.common;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

public abstract class SignedJsonHandler<T> implements HttpHandler {
    private final HmacAuth auth;
    private final Class<T> requestType;

    protected SignedJsonHandler(HmacAuth auth, Class<T> requestType) {
        this.auth = auth;
        this.requestType = requestType;
    }

    @Override
    public final void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        String path = exchange.getRequestURI().getPath();
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod()) || !auth.verify(
            exchange.getRequestMethod(), path, body,
            exchange.getRequestHeaders().getFirst(HmacAuth.TIMESTAMP_HEADER),
            exchange.getRequestHeaders().getFirst(HmacAuth.NONCE_HEADER),
            exchange.getRequestHeaders().getFirst(HmacAuth.SIGNATURE_HEADER))) {
            write(exchange, 401, ApiMessages.BridgeResponse.fail("UNAUTHORIZED", "Unauthorized"));
            return;
        }
        try {
            T request = Json.read(body, requestType);
            write(exchange, 200, process(request));
        }
        catch (IllegalArgumentException failure) {
            write(exchange, 400, ApiMessages.BridgeResponse.fail("BAD_REQUEST", failure.getMessage()));
        }
        catch (Exception failure) {
            write(exchange, 500, ApiMessages.BridgeResponse.fail("INTERNAL_ERROR", "Internal error"));
            onFailure(failure);
        }
    }

    protected abstract Object process(T request) throws Exception;

    protected void onFailure(Exception failure) {
    }

    private static void write(HttpExchange exchange, int status, Object response) throws IOException {
        byte[] bytes = Json.write(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
