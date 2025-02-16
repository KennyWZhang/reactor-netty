/*
 * Copyright (c) 2018-2024 VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.http.client;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.NoopAddressResolverGroup;
import io.specto.hoverfly.junit.core.Hoverfly;
import io.specto.hoverfly.junit.core.HoverflyMode;
import io.specto.hoverfly.junit5.HoverflyExtension;
import io.specto.hoverfly.junit5.api.HoverflyConfig;
import io.specto.hoverfly.junit5.api.HoverflyCore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import reactor.core.publisher.Mono;
import reactor.netty.BaseHttpTest;
import reactor.netty.NettyPipeline;
import reactor.netty.http.Http11SslContextSpec;
import reactor.netty.transport.ProxyProvider;
import reactor.test.StepVerifier;
import reactor.util.annotation.Nullable;
import reactor.util.function.Tuple2;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static io.specto.hoverfly.junit.core.SimulationSource.dsl;
import static io.specto.hoverfly.junit.dsl.HoverflyDsl.service;
import static io.specto.hoverfly.junit.dsl.ResponseCreators.success;
import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty.Metrics.ACTIVE_CONNECTIONS;
import static reactor.netty.Metrics.CONNECTION_PROVIDER_PREFIX;
import static reactor.netty.Metrics.CONNECT_TIME;
import static reactor.netty.Metrics.DATA_RECEIVED;
import static reactor.netty.Metrics.DATA_RECEIVED_TIME;
import static reactor.netty.Metrics.DATA_SENT;
import static reactor.netty.Metrics.DATA_SENT_TIME;
import static reactor.netty.Metrics.HTTP_CLIENT_PREFIX;
import static reactor.netty.Metrics.IDLE_CONNECTIONS;
import static reactor.netty.Metrics.MAX_CONNECTIONS;
import static reactor.netty.Metrics.MAX_PENDING_CONNECTIONS;
import static reactor.netty.Metrics.METHOD;
import static reactor.netty.Metrics.NAME;
import static reactor.netty.Metrics.PENDING_CONNECTIONS;
import static reactor.netty.Metrics.REMOTE_ADDRESS;
import static reactor.netty.Metrics.RESPONSE_TIME;
import static reactor.netty.Metrics.STATUS;
import static reactor.netty.Metrics.SUCCESS;
import static reactor.netty.Metrics.TOTAL_CONNECTIONS;
import static reactor.netty.Metrics.URI;
import static reactor.netty.micrometer.DistributionSummaryAssert.assertDistributionSummary;
import static reactor.netty.micrometer.GaugeAssert.assertGauge;
import static reactor.netty.micrometer.TimerAssert.assertTimer;

/**
 * This test class verifies {@link HttpClient} proxy functionality.
 *
 * @author Violeta Georgieva
 */
@HoverflyCore(mode = HoverflyMode.SIMULATE, config = @HoverflyConfig(plainHttpTunneling = true))
@ExtendWith(HoverflyExtension.class)
class HttpClientProxyTest extends BaseHttpTest {

	private int port;
	private static final String LOCALLY_NOT_RESOLVABLE_ADDRESS =
			"https://some-random-address-that-is-only-resolvable-by-the-proxy-1234.com";

	@BeforeEach
	void setUp(Hoverfly hoverfly) {
		disposableServer = createServer()
		                   .host("localhost")
		                   .handle((req, res) -> res.sendString(Mono.just("test")))
		                   .bindNow();

		port = disposableServer.port();

		hoverfly.simulate(
				dsl(service("http://127.0.0.1:" + port)
				        .get("/")
				        .willReturn(success()
				                .body("test")
				                .header("Hoverfly", "Was-Here")),
				    service(LOCALLY_NOT_RESOLVABLE_ADDRESS)
				        .get("/")
				        .willReturn(success().body("Hi from " + LOCALLY_NOT_RESOLVABLE_ADDRESS))));
	}

	@Test
	void proxy_1(Hoverfly hoverfly) {
		StepVerifier.create(
				sendRequest(ops -> ops.type(ProxyProvider.Proxy.HTTP)
				                      .host("localhost")
				                      .port(hoverfly.getHoverflyConfig().getProxyPort()),
				            disposableServer::address,
				            "/",
				            true))
				    .expectNextMatches(t ->
				            t.getT2().contains("Hoverfly") &&
				                "FOUND".equals(t.getT2().get("Logging-Handler")) &&
				                "test".equals(t.getT1()))
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));
	}

	@Test
	void proxy_2(Hoverfly hoverfly) {
		StepVerifier.create(
				sendRequest(ops -> ops.type(ProxyProvider.Proxy.HTTP)
				                      .host("localhost")
				                      .port(hoverfly.getHoverflyConfig().getProxyPort()),
				            null,
				            "http://127.0.0.1:" + port + "/",
				            true))
				    .expectNextMatches(t ->
				            t.getT2().contains("Hoverfly") &&
				                "FOUND".equals(t.getT2().get("Logging-Handler")) &&
				                "test".equals(t.getT1()))
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));
	}

	@Test
	void nonProxyHosts_1(Hoverfly hoverfly) {
		StepVerifier.create(
				sendRequest(ops -> ops.type(ProxyProvider.Proxy.HTTP)
				                      .host("localhost")
				                      .port(hoverfly.getHoverflyConfig().getProxyPort())
				                      .nonProxyHosts("127.0.0.1"),
				            disposableServer::address,
				            "/",
				            true))
				    .expectNextMatches(t ->
				            !t.getT2().contains("Hoverfly") &&
				                "test".equals(t.getT1()))
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));
	}

	@Test
	void nonProxyHosts_2(Hoverfly hoverfly) {
		StepVerifier.create(
				sendRequest(ops -> ops.type(ProxyProvider.Proxy.HTTP)
				                      .host("localhost")
				                      .port(hoverfly.getHoverflyConfig().getProxyPort())
				                      .nonProxyHosts("localhost"),
				            null,
				             "http://localhost:" + port + "/",
				             true))
				    .expectNextMatches(t ->
				            !t.getT2().contains("Hoverfly") &&
				                "test".equals(t.getT1()))
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));
	}

	@Test
	void testIssue804(Hoverfly hoverfly) {
		StepVerifier.create(
				sendRequest(ops -> ops.type(ProxyProvider.Proxy.HTTP)
				                      .host("localhost")
				                      .port(hoverfly.getHoverflyConfig().getProxyPort()),
				            disposableServer::address,
				            "/",
				            false))
				    .expectNextMatches(t ->
				           t.getT2().contains("Hoverfly") &&
				                "NOT FOUND".equals(t.getT2().get("Logging-Handler")) &&
				                "test".equals(t.getT1()))
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));
	}

	@Test
	void shouldNotResolveTargetHostnameWhenMetricsEnabled(Hoverfly hoverfly) {
		StepVerifier.create(
				sendRequest(ops -> ops.type(ProxyProvider.Proxy.HTTP)
				                      .host("localhost")
				                      .port(hoverfly.getHoverflyConfig().getProxyPort()),
				            null,
				            LOCALLY_NOT_RESOLVABLE_ADDRESS,
				            true,
				            true,
				            true))
				    .expectNextMatches(t -> ("Hi from " + LOCALLY_NOT_RESOLVABLE_ADDRESS).equals(t.getT1()))
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));
	}

	@Test
	void shouldNotResolveTargetHostnameWhenMetricsDisabled(Hoverfly hoverfly) {
		StepVerifier.create(
				sendRequest(ops -> ops.type(ProxyProvider.Proxy.HTTP)
				                      .host("localhost")
				                      .port(hoverfly.getHoverflyConfig().getProxyPort()),
				            null,
				            LOCALLY_NOT_RESOLVABLE_ADDRESS,
				            true,
				            false,
				            true))
				    .expectNextMatches(t -> ("Hi from " + LOCALLY_NOT_RESOLVABLE_ADDRESS).equals(t.getT1()))
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));
	}

	@Test
	void shouldUseDifferentResolvers(Hoverfly hoverfly) {
		Http11SslContextSpec http11SslContextSpec =
				Http11SslContextSpec.forClient()
				                    .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		HttpClient client =
				createClient(disposableServer::address)
				          .secure(spec -> spec.sslContext(http11SslContextSpec))
				          .metrics(true, () -> MicrometerHttpClientMetricsRecorder.INSTANCE);

		AtomicReference<AddressResolverGroup<?>> resolver1 = new AtomicReference<>();
		client.doOnConnect(config -> resolver1.set(config.resolver()))
		      .get()
		      .uri("https://example.com")
		      .responseSingle((response, body) -> Mono.zip(body.asString(),
		                                                   Mono.just(response.responseHeaders())))
		      .block(Duration.ofSeconds(30));

		AtomicReference<AddressResolverGroup<?>> resolver2 = new AtomicReference<>();
		client.proxy(ops -> ops.type(ProxyProvider.Proxy.HTTP)
		                       .host("localhost")
		                       .port(hoverfly.getHoverflyConfig().getProxyPort()))
		      .doOnConnect(config -> resolver2.set(config.resolver()))
		      .get()
		      .uri(LOCALLY_NOT_RESOLVABLE_ADDRESS)
		      .responseSingle((response, body) -> Mono.zip(body.asString(),
		                                                   Mono.just(response.responseHeaders())))
		      .block(Duration.ofSeconds(30));

		AtomicReference<AddressResolverGroup<?>> resolver3 = new AtomicReference<>();
		client.doOnConnect(config -> resolver3.set(config.resolver()))
		      .get()
		      .uri("https://example.com")
		      .responseSingle((response, body) -> Mono.zip(body.asString(),
		                                                   Mono.just(response.responseHeaders())))
		      .block(Duration.ofSeconds(30));

		// Uses the default resolver
		assertThat(resolver1.get()).isNull();
		assertThat(resolver2.get()).isNotNull()
			.isEqualTo(NoopAddressResolverGroup.INSTANCE);
		// Uses the default resolver
		assertThat(resolver3.get()).isNull();
	}

	private Mono<Tuple2<String, HttpHeaders>>  sendRequest(
			Consumer<? super ProxyProvider.TypeSpec> proxyOptions,
			@Nullable Supplier<? extends SocketAddress> connectAddressSupplier,
			String uri,
			boolean wiretap) {
		return sendRequest(proxyOptions, connectAddressSupplier, uri, wiretap, false, false);
	}

	private Mono<Tuple2<String, HttpHeaders>>  sendRequest(
			Consumer<? super ProxyProvider.TypeSpec> proxyOptions,
			@Nullable Supplier<? extends SocketAddress> connectAddressSupplier,
			String uri,
			boolean wiretap,
			boolean metricsEnabled,
			boolean securityEnabled) {
		HttpClient client =
				HttpClient.create()
				          .proxy(proxyOptions)
				          .metrics(metricsEnabled, () -> MicrometerHttpClientMetricsRecorder.INSTANCE)
				          .doOnResponse((res, conn) -> {
				              ChannelHandler handler = conn.channel().pipeline().get(NettyPipeline.ProxyLoggingHandler);
				              res.responseHeaders()
				                 .add("Logging-Handler", handler != null ? "FOUND" : "NOT FOUND");
				          });

		if (connectAddressSupplier != null) {
			client = client.remoteAddress(disposableServer::address);
		}

		if (securityEnabled) {
			Http11SslContextSpec http11SslContextSpec =
					Http11SslContextSpec.forClient()
					                    .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
			client = client.secure(spec -> spec.sslContext(http11SslContextSpec));
		}

		return client.wiretap(wiretap)
		             .get()
		             .uri(uri)
		             .responseSingle((response, body) -> Mono.zip(body.asString(),
		                                                          Mono.just(response.responseHeaders())));
	}

	@Test
	void testIssue1261(Hoverfly hoverfly) {
		AtomicReference<AddressResolverGroup<?>> resolver = new AtomicReference<>();
		HttpClient client =
				HttpClient.create()
				          .proxy(ops -> ops.type(ProxyProvider.Proxy.HTTP)
				                           .host("localhost")
				                           .port(hoverfly.getHoverflyConfig().getProxyPort()))
				          .doOnConnect(conf -> resolver.set(conf.resolver()));

		client.get()
		      .uri("http://localhost:" + port + "/")
		      .responseContent()
		      .blockLast(Duration.ofSeconds(30));

		assertThat(resolver.get()).isNotNull();
		assertThat(resolver.get()).isInstanceOf(NoopAddressResolverGroup.class);

		client.noProxy()
		      .get()
		      .uri("http://localhost:" + port + "/")
		      .responseContent()
		      .blockLast(Duration.ofSeconds(30));

		assertThat(resolver.get()).isNull();

		client.get()
		      .uri("http://localhost:" + port + "/")
		      .responseContent()
		      .blockLast(Duration.ofSeconds(30));

		assertThat(resolver.get()).isNotNull();
		assertThat(resolver.get()).isInstanceOf(NoopAddressResolverGroup.class);
	}

	@Test
	void testIssue3060(Hoverfly hoverfly) {
		MeterRegistry registry = new SimpleMeterRegistry();
		Metrics.addRegistry(registry);

		try {
			StepVerifier.create(
					sendRequest(ops -> ops.type(ProxyProvider.Proxy.HTTP)
					                      .host("localhost")
					                      .port(hoverfly.getHoverflyConfig().getProxyPort()),
					            disposableServer::address,
					            "/",
					            true,
					            true,
					            false))
					    .expectNextMatches(t ->
					            t.getT2().contains("Hoverfly") &&
					                "FOUND".equals(t.getT2().get("Logging-Handler")) &&
					                "test".equals(t.getT1()))
					    .expectComplete()
					    .verify(Duration.ofSeconds(30));

			String serverAddress = disposableServer.host() + ":" + disposableServer.port();

			String[] summaryTags1 = new String[] {REMOTE_ADDRESS, serverAddress, URI, "/"};
			assertDistributionSummary(registry, HTTP_CLIENT_PREFIX + DATA_RECEIVED, summaryTags1).isNotNull();
			assertDistributionSummary(registry, HTTP_CLIENT_PREFIX + DATA_SENT, summaryTags1).isNotNull();

			String[] summaryTags2 = new String[] {REMOTE_ADDRESS, serverAddress, URI, "http"};
			assertDistributionSummary(registry, HTTP_CLIENT_PREFIX + DATA_RECEIVED, summaryTags2).isNotNull();
			assertDistributionSummary(registry, HTTP_CLIENT_PREFIX + DATA_SENT, summaryTags2).isNotNull();

			String[] timerTags1 = new String[] {REMOTE_ADDRESS, serverAddress, STATUS, SUCCESS};
			assertTimer(registry, HTTP_CLIENT_PREFIX + CONNECT_TIME, timerTags1).isNotNull();

			String[] timerTags2 = new String[] {REMOTE_ADDRESS, serverAddress, STATUS, "200", URI, "/", METHOD, "GET"};
			assertTimer(registry, HTTP_CLIENT_PREFIX + DATA_RECEIVED_TIME, timerTags2).isNotNull();
			assertTimer(registry, HTTP_CLIENT_PREFIX + RESPONSE_TIME, timerTags2).isNotNull();

			String[] timerTags3 = new String[] {REMOTE_ADDRESS, serverAddress, URI, "/", METHOD, "GET"};
			assertTimer(registry, HTTP_CLIENT_PREFIX + DATA_SENT_TIME, timerTags3).isNotNull();

			String[] gaugeTags = new String[] {REMOTE_ADDRESS, serverAddress, NAME, "http"};
			assertGauge(registry, CONNECTION_PROVIDER_PREFIX + IDLE_CONNECTIONS, gaugeTags).isNotNull();
			assertGauge(registry, CONNECTION_PROVIDER_PREFIX + ACTIVE_CONNECTIONS, gaugeTags).isNotNull();
			assertGauge(registry, CONNECTION_PROVIDER_PREFIX + PENDING_CONNECTIONS, gaugeTags).isNotNull();
			assertGauge(registry, CONNECTION_PROVIDER_PREFIX + TOTAL_CONNECTIONS, gaugeTags).isNotNull();
			assertGauge(registry, CONNECTION_PROVIDER_PREFIX + MAX_CONNECTIONS, gaugeTags).isNotNull();
			assertGauge(registry, CONNECTION_PROVIDER_PREFIX + MAX_PENDING_CONNECTIONS, gaugeTags).isNotNull();
		}
		finally {
			Metrics.removeRegistry(registry);
			registry.clear();
			registry.close();
		}
	}
}
