/*
 * Copyright ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.ethereum.api.jsonrpc;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Streams.stream;
import static java.util.stream.Collectors.toList;
import static org.apache.tuweni.net.tls.VertxTrustOptions.allowlistClients;
import static org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcError.INVALID_REQUEST;

import org.hyperledger.besu.ethereum.api.handlers.HandlerFactory;
import org.hyperledger.besu.ethereum.api.handlers.TimeoutOptions;
import org.hyperledger.besu.ethereum.api.jsonrpc.authentication.AuthenticationService;
import org.hyperledger.besu.ethereum.api.jsonrpc.authentication.AuthenticationUtils;
import org.hyperledger.besu.ethereum.api.jsonrpc.context.ContextKey;
import org.hyperledger.besu.ethereum.api.jsonrpc.health.HealthService;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequest;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestId;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.exception.InvalidJsonRpcParameters;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.JsonRpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcError;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcErrorResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcNoResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponseType;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcUnauthorizedResponse;
import org.hyperledger.besu.ethereum.api.tls.TlsClientAuthConfiguration;
import org.hyperledger.besu.ethereum.api.tls.TlsConfiguration;
import org.hyperledger.besu.ethereum.privacy.MultiTenancyValidationException;
import org.hyperledger.besu.metrics.BesuMetricCategory;
import org.hyperledger.besu.nat.NatMethod;
import org.hyperledger.besu.nat.NatService;
import org.hyperledger.besu.nat.core.domain.NatServiceType;
import org.hyperledger.besu.nat.core.domain.NetworkProtocol;
import org.hyperledger.besu.nat.upnp.UpnpNatManager;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.metrics.LabelledMetric;
import org.hyperledger.besu.plugin.services.metrics.OperationTimer;
import org.hyperledger.besu.util.ExceptionUtils;
import org.hyperledger.besu.util.NetworkUtility;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.extension.trace.propagation.B3Propagator;
import io.opentelemetry.extension.trace.propagation.JaegerPropagator;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.VertxException;
import io.vertx.core.http.ClientAuth;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.PfxOptions;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JsonRpcHttpService {

  private static final Logger LOG = LoggerFactory.getLogger(JsonRpcHttpService.class);

  private static final String SPAN_CONTEXT = "span_context";
  private static final InetSocketAddress EMPTY_SOCKET_ADDRESS = new InetSocketAddress("0.0.0.0", 0);
  private static final String APPLICATION_JSON = "application/json";
  private static final JsonRpcResponse NO_RESPONSE = new JsonRpcNoResponse();
  private static final ObjectWriter JSON_OBJECT_WRITER =
      new ObjectMapper()
          .registerModule(new Jdk8Module()) // Handle JDK8 Optionals (de)serialization
          .writerWithDefaultPrettyPrinter()
          .without(JsonGenerator.Feature.FLUSH_PASSED_TO_STREAM);
  private static final String EMPTY_RESPONSE = "";

  private static final TextMapPropagator traceFormats =
      TextMapPropagator.composite(
          JaegerPropagator.getInstance(),
          B3Propagator.injectingSingleHeader(),
          W3CBaggagePropagator.getInstance());

  private static final TextMapGetter<HttpServerRequest> requestAttributesGetter =
      new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(final HttpServerRequest carrier) {
          return carrier.headers().names();
        }

        @Nullable
        @Override
        public String get(final @Nullable HttpServerRequest carrier, final String key) {
          if (carrier == null) {
            return null;
          }
          return carrier.headers().get(key);
        }
      };

  private final Vertx vertx;
  private final JsonRpcConfiguration config;
  private final Map<String, JsonRpcMethod> rpcMethods;
  private final NatService natService;
  private final Path dataDir;
  private final LabelledMetric<OperationTimer> requestTimer;
  private Tracer tracer;
  private final int maxActiveConnections;
  private final AtomicInteger activeConnectionsCount = new AtomicInteger();

  @VisibleForTesting public final Optional<AuthenticationService> authenticationService;

  private HttpServer httpServer;
  private final HealthService livenessService;
  private final HealthService readinessService;

  /**
   * Construct a JsonRpcHttpService handler
   *
   * @param vertx The vertx process that will be running this service
   * @param dataDir The data directory where requests can be buffered
   * @param config Configuration for the rpc methods being loaded
   * @param metricsSystem The metrics service that activities should be reported to
   * @param natService The NAT environment manager.
   * @param methods The json rpc methods that should be enabled
   * @param livenessService A service responsible for reporting whether this node is live
   * @param readinessService A service responsible for reporting whether this node has fully started
   */
  public JsonRpcHttpService(
      final Vertx vertx,
      final Path dataDir,
      final JsonRpcConfiguration config,
      final MetricsSystem metricsSystem,
      final NatService natService,
      final Map<String, JsonRpcMethod> methods,
      final HealthService livenessService,
      final HealthService readinessService) {
    this(
        vertx,
        dataDir,
        config,
        metricsSystem,
        natService,
        methods,
        AuthenticationService.create(vertx, config),
        livenessService,
        readinessService);
  }

  private JsonRpcHttpService(
      final Vertx vertx,
      final Path dataDir,
      final JsonRpcConfiguration config,
      final MetricsSystem metricsSystem,
      final NatService natService,
      final Map<String, JsonRpcMethod> methods,
      final Optional<AuthenticationService> authenticationService,
      final HealthService livenessService,
      final HealthService readinessService) {
    this.dataDir = dataDir;
    requestTimer =
        metricsSystem.createLabelledTimer(
            BesuMetricCategory.RPC,
            "request_time",
            "Time taken to process a JSON-RPC request",
            "methodName");
    validateConfig(config);
    this.config = config;
    this.vertx = vertx;
    this.natService = natService;
    this.rpcMethods = methods;
    this.authenticationService = authenticationService;
    this.livenessService = livenessService;
    this.readinessService = readinessService;
    this.maxActiveConnections = config.getMaxActiveConnections();
  }

  private void validateConfig(final JsonRpcConfiguration config) {
    checkArgument(
        config.getPort() == 0 || NetworkUtility.isValidPort(config.getPort()),
        "Invalid port configuration.");
    checkArgument(config.getHost() != null, "Required host is not configured.");
    checkArgument(
        config.getMaxActiveConnections() > 0, "Invalid max active connections configuration.");
  }

  public CompletableFuture<?> start() {
    LOG.info("Starting JSON-RPC service on {}:{}", config.getHost(), config.getPort());
    LOG.debug("max number of active connections {}", maxActiveConnections);
    this.tracer = GlobalOpenTelemetry.getTracer("org.hyperledger.besu.jsonrpc", "1.0.0");

    final CompletableFuture<?> resultFuture = new CompletableFuture<>();
    try {
      // Create the HTTP server and a router object.
      httpServer = vertx.createHttpServer(getHttpServerOptions());

      httpServer.connectionHandler(connectionHandler());

      httpServer
          .requestHandler(buildRouter())
          .listen(
              res -> {
                if (!res.failed()) {
                  resultFuture.complete(null);
                  config.setPort(httpServer.actualPort());
                  LOG.info(
                      "JSON-RPC service started and listening on {}:{}{}",
                      config.getHost(),
                      config.getPort(),
                      tlsLogMessage());

                  natService.ifNatEnvironment(
                      NatMethod.UPNP,
                      natManager ->
                          ((UpnpNatManager) natManager)
                              .requestPortForward(
                                  config.getPort(), NetworkProtocol.TCP, NatServiceType.JSON_RPC));

                  return;
                }

                httpServer = null;
                resultFuture.completeExceptionally(getFailureException(res.cause()));
              });
    } catch (final JsonRpcServiceException tlsException) {
      httpServer = null;
      resultFuture.completeExceptionally(tlsException);
    } catch (final VertxException listenException) {
      httpServer = null;
      resultFuture.completeExceptionally(
          new JsonRpcServiceException(
              String.format(
                  "Ethereum JSON-RPC listener failed to start: %s",
                  ExceptionUtils.rootCause(listenException).getMessage())));
    }

    return resultFuture;
  }

  private Handler<HttpConnection> connectionHandler() {

    return connection -> {
      if (activeConnectionsCount.get() >= maxActiveConnections) {
        // disallow new connections to prevent DoS
        LOG.warn(
            "Rejecting new connection from {}. Max {} active connections limit reached.",
            connection.remoteAddress(),
            activeConnectionsCount.getAndIncrement());
        connection.close();
      } else {
        LOG.debug(
            "Opened connection from {}. Total of active connections: {}/{}",
            connection.remoteAddress(),
            activeConnectionsCount.incrementAndGet(),
            maxActiveConnections);
      }
      connection.closeHandler(
          c ->
              LOG.debug(
                  "Connection closed from {}. Total of active connections: {}/{}",
                  connection.remoteAddress(),
                  activeConnectionsCount.decrementAndGet(),
                  maxActiveConnections));
    };
  }

  private Router buildRouter() {
    // Handle json rpc requests
    final Router router = Router.router(vertx);
    router.route().handler(this::createSpan);

    // Verify Host header to avoid rebind attack.
    router.route().handler(checkAllowlistHostHeader());

    router
        .route()
        .handler(
            CorsHandler.create(buildCorsRegexFromConfig())
                .allowedHeader("*")
                .allowedHeader("content-type"));
    router
        .route()
        .handler(
            BodyHandler.create()
                .setUploadsDirectory(dataDir.resolve("uploads").toString())
                .setDeleteUploadedFilesOnEnd(true));
    router.route("/").method(HttpMethod.GET).handler(this::handleEmptyRequest);
    router
        .route(HealthService.LIVENESS_PATH)
        .method(HttpMethod.GET)
        .handler(livenessService::handleRequest);
    router
        .route(HealthService.READINESS_PATH)
        .method(HttpMethod.GET)
        .handler(readinessService::handleRequest);
    router
        .route("/")
        .method(HttpMethod.POST)
        .produces(APPLICATION_JSON)
        .handler(
            HandlerFactory.timeout(
                new TimeoutOptions(config.getHttpTimeoutSec()), rpcMethods, true))
        .handler(this::handleJsonRPCRequest);

    if (authenticationService.isPresent()) {
      router
          .route("/login")
          .method(HttpMethod.POST)
          .produces(APPLICATION_JSON)
          .handler(authenticationService.get()::handleLogin);
    } else {
      router
          .route("/login")
          .method(HttpMethod.POST)
          .produces(APPLICATION_JSON)
          .handler(AuthenticationService::handleDisabledLogin);
    }
    return router;
  }

  private void createSpan(final RoutingContext routingContext) {
    final SocketAddress address = routingContext.request().connection().remoteAddress();

    Context parent =
        traceFormats.extract(Context.current(), routingContext.request(), requestAttributesGetter);
    final Span serverSpan =
        tracer
            .spanBuilder(address.host() + ":" + address.port())
            .setParent(parent)
            .setSpanKind(SpanKind.SERVER)
            .startSpan();
    routingContext.put(SPAN_CONTEXT, Context.current().with(serverSpan));

    routingContext.addEndHandler(
        event -> {
          if (event.failed()) {
            serverSpan.recordException(event.cause());
            serverSpan.setStatus(StatusCode.ERROR);
          }
          serverSpan.end();
        });
    routingContext.next();
  }

  private HttpServerOptions getHttpServerOptions() {
    final HttpServerOptions httpServerOptions =
        new HttpServerOptions()
            .setHost(config.getHost())
            .setPort(config.getPort())
            .setHandle100ContinueAutomatically(true)
            .setCompressionSupported(true);

    applyTlsConfig(httpServerOptions);
    return httpServerOptions;
  }

  private void applyTlsConfig(final HttpServerOptions httpServerOptions) {
    if (config.getTlsConfiguration().isEmpty()) {
      return;
    }

    final TlsConfiguration tlsConfiguration = config.getTlsConfiguration().get();
    try {
      httpServerOptions
          .setSsl(true)
          .setPfxKeyCertOptions(
              new PfxOptions()
                  .setPath(tlsConfiguration.getKeyStorePath().toString())
                  .setPassword(tlsConfiguration.getKeyStorePassword()))
          .setUseAlpn(true);

      tlsConfiguration
          .getSecureTransportProtocols()
          .ifPresent(httpServerOptions::setEnabledSecureTransportProtocols);

      tlsConfiguration
          .getCipherSuites()
          .ifPresent(
              cipherSuites -> {
                for (String cs : cipherSuites) {
                  httpServerOptions.addEnabledCipherSuite(cs);
                }
              });

      tlsConfiguration
          .getClientAuthConfiguration()
          .ifPresent(
              clientAuthConfiguration ->
                  applyTlsClientAuth(clientAuthConfiguration, httpServerOptions));
    } catch (final RuntimeException re) {
      throw new JsonRpcServiceException(
          String.format(
              "TLS options failed to initialize for Ethereum JSON-RPC listener: %s",
              re.getMessage()));
    }
  }

  private void applyTlsClientAuth(
      final TlsClientAuthConfiguration clientAuthConfiguration,
      final HttpServerOptions httpServerOptions) {
    httpServerOptions.setClientAuth(ClientAuth.REQUIRED);
    clientAuthConfiguration
        .getKnownClientsFile()
        .ifPresent(
            knownClientsFile ->
                httpServerOptions.setTrustOptions(
                    allowlistClients(
                        knownClientsFile, clientAuthConfiguration.isCaClientsEnabled())));
  }

  private String tlsLogMessage() {
    return config.getTlsConfiguration().isPresent() ? " with TLS enabled." : "";
  }

  private Throwable getFailureException(final Throwable listenFailure) {

    JsonRpcServiceException servFail =
        new JsonRpcServiceException(
            String.format(
                "Failed to bind Ethereum JSON-RPC listener to %s:%s: %s",
                config.getHost(), config.getPort(), listenFailure.getMessage()));
    servFail.initCause(listenFailure);

    return servFail;
  }

  private Handler<RoutingContext> checkAllowlistHostHeader() {
    return event -> {
      final Optional<String> hostHeader = getAndValidateHostHeader(event);
      if (config.getHostsAllowlist().contains("*")
          || (hostHeader.isPresent() && hostIsInAllowlist(hostHeader.get()))) {
        event.next();
      } else {
        final HttpServerResponse response = event.response();
        if (!response.closed()) {
          response
              .setStatusCode(403)
              .putHeader("Content-Type", "application/json; charset=utf-8")
              .end("{\"message\":\"Host not authorized.\"}");
        }
      }
    };
  }

  private String getAuthToken(final RoutingContext routingContext) {
    return AuthenticationUtils.getJwtTokenFromAuthorizationHeaderValue(
        routingContext.request().getHeader("Authorization"));
  }

  private Optional<String> getAndValidateHostHeader(final RoutingContext event) {
    String hostname =
        event.request().getHeader(HttpHeaders.HOST) != null
            ? event.request().getHeader(HttpHeaders.HOST)
            : event.request().host();
    final Iterable<String> splitHostHeader = Splitter.on(':').split(hostname);
    final long hostPieces = stream(splitHostHeader).count();
    if (hostPieces > 1) {
      // If the host contains a colon, verify the host is correctly formed - host [ ":" port ]
      if (hostPieces > 2 || !Iterables.get(splitHostHeader, 1).matches("\\d{1,5}+")) {
        return Optional.empty();
      }
    }
    return Optional.ofNullable(Iterables.get(splitHostHeader, 0));
  }

  private boolean hostIsInAllowlist(final String hostHeader) {
    if (config.getHostsAllowlist().stream()
        .anyMatch(
            allowlistEntry -> allowlistEntry.toLowerCase().equals(hostHeader.toLowerCase()))) {
      return true;
    } else {
      LOG.trace("Host not in allowlist: '{}'", hostHeader);
      return false;
    }
  }

  public CompletableFuture<?> stop() {
    if (httpServer == null) {
      return CompletableFuture.completedFuture(null);
    }

    final CompletableFuture<?> resultFuture = new CompletableFuture<>();
    httpServer.close(
        res -> {
          if (res.failed()) {
            resultFuture.completeExceptionally(res.cause());
          } else {
            httpServer = null;
            resultFuture.complete(null);
          }
        });
    return resultFuture;
  }

  public InetSocketAddress socketAddress() {
    if (httpServer == null) {
      return EMPTY_SOCKET_ADDRESS;
    }
    return new InetSocketAddress(config.getHost(), httpServer.actualPort());
  }

  @VisibleForTesting
  public String url() {
    if (httpServer == null) {
      return "";
    }
    return NetworkUtility.urlForSocketAddress(getScheme(), socketAddress());
  }

  private String getScheme() {
    return config.getTlsConfiguration().isPresent() ? "https" : "http";
  }

  private void handleJsonRPCRequest(final RoutingContext routingContext) {
    // first check token if authentication is required
    final String token = getAuthToken(routingContext);
    if (authenticationService.isPresent() && token == null) {
      // no auth token when auth required
      handleJsonRpcUnauthorizedError(routingContext, null, JsonRpcError.UNAUTHORIZED);
    } else {
      // Parse json
      try {
        final String json = routingContext.getBodyAsString().trim();
        if (!json.isEmpty() && json.charAt(0) == '{') {
          final JsonObject requestBodyJsonObject =
              ContextKey.REQUEST_BODY_AS_JSON_OBJECT.extractFrom(
                  routingContext, () -> new JsonObject(json));
          AuthenticationUtils.getUser(
              authenticationService,
              token,
              user -> handleJsonSingleRequest(routingContext, requestBodyJsonObject, user));
        } else {
          final JsonArray array = new JsonArray(json);
          if (array.size() < 1) {
            handleJsonRpcError(routingContext, null, INVALID_REQUEST);
            return;
          }
          AuthenticationUtils.getUser(
              authenticationService,
              token,
              user -> handleJsonBatchRequest(routingContext, array, user));
        }
      } catch (final DecodeException | NullPointerException ex) {
        handleJsonRpcError(routingContext, null, JsonRpcError.PARSE_ERROR);
      }
    }
  }

  // Facilitate remote health-checks in AWS, inter alia.
  private void handleEmptyRequest(final RoutingContext routingContext) {
    routingContext.response().setStatusCode(201).end();
  }

  private void handleJsonSingleRequest(
      final RoutingContext routingContext, final JsonObject request, final Optional<User> user) {
    final HttpServerResponse response = routingContext.response();
    vertx.executeBlocking(
        future -> {
          final JsonRpcResponse jsonRpcResponse = process(routingContext, request, user);
          future.complete(jsonRpcResponse);
        },
        false,
        (res) -> {
          if (!response.closed() && !response.headWritten()) {
            if (res.failed()) {
              response.setStatusCode(HttpResponseStatus.INTERNAL_SERVER_ERROR.code()).end();
              return;
            }

            final JsonRpcResponse jsonRpcResponse = (JsonRpcResponse) res.result();

            response
                .setStatusCode(status(jsonRpcResponse).code())
                .putHeader("Content-Type", APPLICATION_JSON);

            if (jsonRpcResponse.getType() == JsonRpcResponseType.NONE) {
              response.end(EMPTY_RESPONSE);
            } else {
              try {
                JSON_OBJECT_WRITER.writeValue(new JsonResponseStreamer(response), jsonRpcResponse);
              } catch (IOException ex) {
                LOG.error("Error streaming JSON-RPC response", ex);
              }
            }
          }
        });
  }

  private HttpResponseStatus status(final JsonRpcResponse response) {
    switch (response.getType()) {
      case UNAUTHORIZED:
        return HttpResponseStatus.UNAUTHORIZED;
      case ERROR:
        return statusCodeFromError(((JsonRpcErrorResponse) response).getError());
      case SUCCESS:
      case NONE:
      default:
        return HttpResponseStatus.OK;
    }
  }

  private HttpResponseStatus statusCodeFromError(final JsonRpcError error) {
    switch (error) {
      case INVALID_REQUEST:
      case INVALID_PARAMS:
      case PARSE_ERROR:
        return HttpResponseStatus.BAD_REQUEST;
      default:
        return HttpResponseStatus.OK;
    }
  }

  @SuppressWarnings("rawtypes")
  private void handleJsonBatchRequest(
      final RoutingContext routingContext, final JsonArray jsonArray, final Optional<User> user) {
    // Interpret json as rpc request
    final List<Future> responses =
        jsonArray.stream()
            .map(
                obj -> {
                  if (!(obj instanceof JsonObject)) {
                    return Future.succeededFuture(errorResponse(null, INVALID_REQUEST));
                  }

                  final JsonObject req = (JsonObject) obj;
                  return vertx.executeBlocking(
                      future -> future.complete(process(routingContext, req, user)));
                })
            .collect(toList());

    CompositeFuture.all(responses)
        .onComplete(
            (res) -> {
              final HttpServerResponse response = routingContext.response();
              if (response.closed() || response.headWritten()) {
                return;
              }
              if (res.failed()) {
                response.setStatusCode(HttpResponseStatus.BAD_REQUEST.code()).end();
                return;
              }
              final JsonRpcResponse[] completed =
                  res.result().list().stream()
                      .map(JsonRpcResponse.class::cast)
                      .filter(this::isNonEmptyResponses)
                      .toArray(JsonRpcResponse[]::new);

              try {
                JSON_OBJECT_WRITER.writeValue(new JsonResponseStreamer(response), completed);
              } catch (IOException ex) {
                LOG.error("Error streaming JSON-RPC response", ex);
              }
            });
  }

  private boolean isNonEmptyResponses(final JsonRpcResponse result) {
    return result.getType() != JsonRpcResponseType.NONE;
  }

  private JsonRpcResponse process(
      final RoutingContext ctx, final JsonObject requestJson, final Optional<User> user) {
    final JsonRpcRequest requestBody;
    Object id = null;
    try {
      id = new JsonRpcRequestId(requestJson.getValue("id")).getValue();
      requestBody = requestJson.mapTo(JsonRpcRequest.class);
    } catch (final IllegalArgumentException exception) {
      return errorResponse(id, INVALID_REQUEST);
    }
    Span span =
        tracer
            .spanBuilder(requestBody.getMethod())
            .setSpanKind(SpanKind.INTERNAL)
            .setParent(ctx.get(SPAN_CONTEXT))
            .startSpan();
    try {
      // Handle notifications
      if (requestBody.isNotification()) {
        // Notifications aren't handled so create empty result for now.
        return NO_RESPONSE;
      }

      final Optional<JsonRpcError> unavailableMethod = validateMethodAvailability(requestBody);
      if (unavailableMethod.isPresent()) {
        span.setStatus(StatusCode.ERROR, "method unavailable");
        return errorResponse(id, unavailableMethod.get());
      }

      final JsonRpcMethod method = rpcMethods.get(requestBody.getMethod());

      if (AuthenticationUtils.isPermitted(authenticationService, user, method)) {
        // Generate response
        try (final OperationTimer.TimingContext ignored =
            requestTimer.labels(requestBody.getMethod()).startTimer()) {
          if (user.isPresent()) {
            return method.response(
                new JsonRpcRequestContext(requestBody, user.get(), () -> !ctx.response().closed()));
          }
          return method.response(
              new JsonRpcRequestContext(requestBody, () -> !ctx.response().closed()));
        } catch (final InvalidJsonRpcParameters e) {
          LOG.debug("Invalid Params", e);
          span.setStatus(StatusCode.ERROR, "Invalid Params");
          return errorResponse(id, JsonRpcError.INVALID_PARAMS);
        } catch (final MultiTenancyValidationException e) {
          span.setStatus(StatusCode.ERROR, "Unauthorized");
          return unauthorizedResponse(id, JsonRpcError.UNAUTHORIZED);
        } catch (final RuntimeException e) {
          LOG.error("Error processing JSON-RPC requestBody", e);
          span.setStatus(StatusCode.ERROR, "Error processing JSON-RPC requestBody");
          return errorResponse(id, JsonRpcError.INTERNAL_ERROR);
        }
      } else {
        span.setStatus(StatusCode.ERROR, "Unauthorized");
        return unauthorizedResponse(id, JsonRpcError.UNAUTHORIZED);
      }
    } finally {
      span.end();
    }
  }

  private Optional<JsonRpcError> validateMethodAvailability(final JsonRpcRequest request) {
    final String name = request.getMethod();
    LOG.debug("JSON-RPC request -> {}", name);

    final JsonRpcMethod method = rpcMethods.get(name);

    if (method == null) {
      if (!RpcMethod.rpcMethodExists(name)) {
        return Optional.of(JsonRpcError.METHOD_NOT_FOUND);
      }
      if (!rpcMethods.containsKey(name)) {
        return Optional.of(JsonRpcError.METHOD_NOT_ENABLED);
      }
    }

    return Optional.empty();
  }

  private void handleJsonRpcError(
      final RoutingContext routingContext, final Object id, final JsonRpcError error) {
    final HttpServerResponse response = routingContext.response();
    if (!response.closed()) {
      response
          .setStatusCode(statusCodeFromError(error).code())
          .end(Json.encode(new JsonRpcErrorResponse(id, error)));
    }
  }

  private void handleJsonRpcUnauthorizedError(
      final RoutingContext routingContext, final Object id, final JsonRpcError error) {
    final HttpServerResponse response = routingContext.response();
    if (!response.closed()) {
      response
          .setStatusCode(HttpResponseStatus.UNAUTHORIZED.code())
          .end(Json.encode(new JsonRpcErrorResponse(id, error)));
    }
  }

  private JsonRpcResponse errorResponse(final Object id, final JsonRpcError error) {
    return new JsonRpcErrorResponse(id, error);
  }

  private JsonRpcResponse unauthorizedResponse(final Object id, final JsonRpcError error) {
    return new JsonRpcUnauthorizedResponse(id, error);
  }

  private String buildCorsRegexFromConfig() {
    if (config.getCorsAllowedDomains().isEmpty()) {
      return "";
    }
    if (config.getCorsAllowedDomains().contains("*")) {
      return ".*";
    } else {
      final StringJoiner stringJoiner = new StringJoiner("|");
      config.getCorsAllowedDomains().stream().filter(s -> !s.isEmpty()).forEach(stringJoiner::add);
      return stringJoiner.toString();
    }
  }
}
