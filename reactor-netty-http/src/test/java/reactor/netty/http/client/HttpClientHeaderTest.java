package reactor.netty.http.client;


import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.Http11SslContextSpec;
import reactor.netty.http.Http2SslContextSpec;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.tcp.SslProvider;
import reactor.test.StepVerifier;

import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

public class HttpClientHeaderTest {

	private static final Logger logger =  LoggerFactory.getLogger(HttpClientHeaderTest.class);

	private static final String CC_HEADER_NAME = "CamelCasedHeader";

	private static DisposableServer mkHttpServer(final boolean isHttp2) {
		try {
			final SelfSignedCertificate ssc = new SelfSignedCertificate();
			final SslProvider.ProtocolSslContextSpec sslContext = isHttp2 ?
					Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey())
					: Http11SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());

			final DisposableServer server = HttpServer.create()
					.protocol(HttpProtocol.H2, HttpProtocol.HTTP11)
					.secure(spec -> spec.sslContext(sslContext))
					.port(0)
					.route(httpServerRoutes -> httpServerRoutes.get(
							"/echoHeaderNames",
							HttpClientHeaderTest::echoHeaderNames
					))
					.wiretap(true).bindNow();
			logger.info("Reactor Netty started on " + server.port());
			return server;
		} catch (CertificateException e) {
			throw new RuntimeException(e);
		}
	}

	private static Publisher<Void> echoHeaderNames(final HttpServerRequest httpServerRequest,
	                                               final HttpServerResponse httpServerResponse) {
		return httpServerResponse.sendString(
				Mono.just(
						httpServerRequest
								.requestHeaders()
								.entries()
								.stream()
								.map(Map.Entry::getKey)
								.collect(Collectors.joining(","))
				)
		);
	}

	@Test
	public void testHttp1Client_echoHeaders() {
		testEchoHeaders(false);
	}

	@Test
	public void testHttp2Client_echoHeaders() {
		testEchoHeaders(true);
	}

	private void testEchoHeaders(final boolean isHttp2) {
		final ConnectionProvider provider = ConnectionProvider.builder("test_pool")
				.maxConnections(1)
				.build();

		final Http2SslContextSpec clientCtx =
				Http2SslContextSpec.forClient()
						.configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));

		final HttpClient client = HttpClient.create(provider)
				.secure(sslContextSpec -> sslContextSpec.sslContext(clientCtx))
				.protocol(isHttp2 ? HttpProtocol.H2 : HttpProtocol.HTTP11)
				.responseTimeout(Duration.ofMillis(400))
				.wiretap(true);

		final DisposableServer server = mkHttpServer(isHttp2);
		try {
			StepVerifier.create(client.headers(headers -> headers.add(CC_HEADER_NAME, "somevalue")).get()
					.uri(String.format("https://localhost:%s/echoHeaderNames", server.port()))
					.responseContent()
					.aggregate()
					.asString()
			).expectNextMatches(responseString -> responseString.contains(CC_HEADER_NAME)).verifyComplete();
		} finally {
			server.dispose();
		}
	}
}