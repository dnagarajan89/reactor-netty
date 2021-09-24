package reactor.netty.http.client;

import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.Http11SslContextSpec;
import reactor.netty.http.Http2SslContextSpec;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.tcp.SslProvider;

import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public class HttpClientConnectionTest {

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
							"/echoConn",
							(req, resp) -> {
								final AtomicReference<String> id = new AtomicReference<>();
								final CountDownLatch latch = new CountDownLatch(1);
								resp.withConnection(conn -> {
									id.set(conn.channel().id().asShortText());
									System.out.println("Setting conn id: " + id.get());
									latch.countDown();
								});
								try {
									latch.await();
									return resp
											.addHeader("Content-Type", "text/plain")
											.sendString(Mono.just(id.get()));
								} catch (final Exception e) {
									return resp.sendString(Mono.error(e));
								}
							}))
					.wiretap(true).bindNow();
			return server;
		} catch (CertificateException e) {
			throw new RuntimeException(e);
		}
	}

	@Test
	public void testHttp1Client_connectionReuse() {
		testConnectionReuse(false);
	}

	@Test
	public void testHttp2Client_connectionReuse() {
		testConnectionReuse(true);
	}

	private void testConnectionReuse(final boolean isHttp2) {
		final ConnectionProvider provider = ConnectionProvider.builder("test_pool")
				.maxConnections(2)
				.build();

		final Http2SslContextSpec clientCtx =
				Http2SslContextSpec.forClient()
						.configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));

		final HttpClient client = HttpClient.create(provider)
				//.secure(sslContextSpec -> sslContextSpec.sslContext(SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE)))
				.secure(sslContextSpec -> sslContextSpec.sslContext(clientCtx))
				.protocol(isHttp2 ? HttpProtocol.H2 : HttpProtocol.HTTP11)
				.responseTimeout(Duration.ofMillis(400));

		final DisposableServer server = mkHttpServer(isHttp2);
		try {
			final String conn1 = mkHttpCall(client, server);
			sleep(100);
			final String conn2 = mkHttpCall(client, server);
			Assertions.assertEquals(conn1, conn2);
		} finally {
			server.dispose();
		}
	}

	private String mkHttpCall(HttpClient client, DisposableServer server) {
		return client.get()
				.uri(String.format("https://localhost:%s/echoConn", server.port()))
				.responseContent()
				.aggregate()
				.asString().block();
	}

	private void sleep(final long timeInMillis) {
		try {
			Thread.sleep(timeInMillis);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}
}
