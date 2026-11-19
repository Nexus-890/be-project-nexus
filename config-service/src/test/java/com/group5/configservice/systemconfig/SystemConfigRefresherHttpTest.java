package com.group5.configservice.systemconfig;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class SystemConfigRefresherHttpTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void refreshPostsJsonWithTheAdminTokenSoActuatorAcceptsIt() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/actuator/refresh", exchange -> {
            String type = exchange.getRequestHeaders().getFirst("Content-Type");
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(exchange.getRequestMethod() + " " + type + " " + body + " "
                    + exchange.getRequestHeaders().getFirst("Authorization"));
            int status = type != null && type.startsWith("application/json") ? 200 : 415;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        var refresher = new SystemConfigRefresher(url, url, url, url, url, url, url, url, url);

        refresher.refresh("auction-service", "admin-token");

        assertThat(seen).containsExactly("POST application/json {} Bearer admin-token");
    }
}
