package com.mimocodes.mimorag.support;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.HttpServer;

/** Minimal in-process HTTP server publishing JWK Sets, standing in for connected systems' JWKS endpoints. */
public final class JwksTestServer {

	private static final JwksTestServer INSTANCE = new JwksTestServer();

	private final HttpServer server;

	private final Map<String, String> documents = new ConcurrentHashMap<>();

	private JwksTestServer() {
		try {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		server.createContext("/", exchange -> {
			String body = documents.get(exchange.getRequestURI().getPath());
			byte[] bytes = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(body == null ? 404 : 200, body == null ? -1 : bytes.length);
			if (body != null) {
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(bytes);
				}
			}
			exchange.close();
		});
		server.start();
	}

	public static JwksTestServer instance() {
		return INSTANCE;
	}

	/** Publishes {@code jwkSetJson} at {@code /jwks/<name>} and returns its absolute URL. */
	public String publish(String name, String jwkSetJson) {
		String path = "/jwks/" + name;
		documents.put(path, jwkSetJson);
		return "http://127.0.0.1:" + server.getAddress().getPort() + path;
	}

}
