package org.jocean.xharbor.reactor;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.jocean.http.Feature;
import org.jocean.http.FullMessage;
import org.jocean.http.MessageBody;
import org.jocean.http.TransportException;
import org.jocean.http.WriteCtrl;
import org.jocean.http.client.HttpClient;
import org.jocean.http.client.HttpClient.HttpInitiator;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.idiom.BeanFinder;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.DisposableWrapperUtil;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.StopWatch;
import org.jocean.idiom.rx.RxObservables;
import org.jocean.svr.StringTags;
import org.jocean.svr.tracing.TraceUtil;
import org.jocean.xharbor.api.RelayMemo;
import org.jocean.xharbor.api.RelayMemo.RESULT;
import org.jocean.xharbor.api.RoutingInfo;
import org.jocean.xharbor.api.ServiceMemo;
import org.jocean.xharbor.api.Target;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;

import io.jaegertracing.internal.JaegerSpan;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.BaseUnits;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.opentracing.Span;
import io.opentracing.propagation.Format;
import io.opentracing.tag.Tags;
import rx.Observable;
import rx.Observable.Transformer;
import rx.Single;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.functions.Func1;

public class ForwardTrade extends SingleReactor {

    private static final int MAX_RETAINED_SIZE = 8 * 1024;
    private static final long _period = 20; // 30 seconds
    private static final Logger LOG = LoggerFactory.getLogger(ForwardTrade.class);

    public ForwardTrade(
            final String serviceName,
            final MatchRule  matcher,
            final BeanFinder finder,
            final RelayMemo.Builder memoBuilder,
            final ServiceMemo serviceMemo,
            final io.netty.util.Timer timer,
            final MeterRegistry meterRegistry ) {
        this._serviceName = serviceName;
        this._matcher = matcher;
        this._finder = finder;
        this._memoBuilder = memoBuilder;
        this._serviceMemo = serviceMemo;
        this._timer = timer;
        this._meterRegistry = meterRegistry;
    }

    @Override
    public String toString() {
        final int maxLen = 10;
        final StringBuilder builder = new StringBuilder();
        builder.append("ForwardTrade [service=").append(_serviceName).append(", matcher=").append(_matcher).append(", targets=")
                .append(_targets != null ? _targets.subList(0, Math.min(_targets.size(), maxLen)) : null).append("]");
        return builder.toString();
    }

    public void addTarget(final Target target) {
        this._targets.add(new MarkableTargetImpl(target));
    }

    @Override
    public Single<Boolean> match(final ReactContext ctx, final InOut io) {
        if (null != io.outbound()) {
            return Single.just(false);
        }
        return io.inbound().first().map(fullreq -> this._matcher.match(fullreq.message())).toSingle();
    }

    @Override
    public Single<? extends InOut> react(final ReactContext ctx, final InOut io) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("try {} for trade {}", this, ctx.trade());
        }
        if (null != io.outbound()) {
            return Single.<InOut>just(null);
        }
        return io.inbound().first().flatMap(fullreq -> {
            if (this._matcher.match(fullreq.message())) {
                final MarkableTargetImpl target = selectTarget();
                if (null == target) {
                    // no target
                    LOG.warn("NONE_TARGET to forward for trade {}", ctx.trade());
                    return Observable.just(null);
                } else {
                    LOG.debug("forward to {} for trade {}", target, ctx.trade());
                    return io4forward(ctx, io, target, this._matcher.summary(), fullreq.message());
                }
            } else {
                // not handle this trade
                return Observable.just(null);
            }
        }).compose(RxObservables.<InOut>ensureSubscribeAtmostOnce()).toSingle();
    }

    private String buildOperationName(final String uri) {
        final String operationName = this._matcher.matchedPath(uri);
        return null != operationName ? operationName : "httpin";
    }

    private Observable<InOut> io4forward(
            final ReactContext ctx,
            final InOut orgio,
            final MarkableTargetImpl target,
            final String summary,
            final HttpRequest request) {
//        return new HystrixObservableCommand<InOut>(HystrixObservableCommand.Setter
//                        .withGroupKey(HystrixCommandGroupKey.Factory.asKey("forward"))
//                        .andCommandKey(HystrixCommandKey.Factory.asKey(summary + "-request"))
//                        .andCommandPropertiesDefaults(HystrixCommandProperties.Setter()
//                                .withExecutionIsolationStrategy(ExecutionIsolationStrategy.SEMAPHORE)
////                                .withExecutionTimeoutEnabled(false)
//                                .withExecutionTimeoutInMilliseconds(30 * 1000)
//                                .withExecutionIsolationSemaphoreMaxConcurrentRequests(1000)
//                                .withFallbackIsolationSemaphoreMaxConcurrentRequests(2000)
//                                )
//                        ) {
//                    @Override
//                    protected Observable<InOut> construct() {
                        return buildOutbound(ctx, orgio.inbound(), target, request)
                            .doOnError(onCommunicationError(target)).compose(makeupio(orgio, target, ctx, summary)).first();
//                    }
//                }.toObservable();
    }

    private Transformer<FullMessage<HttpResponse>, InOut> makeupio(
            final InOut orgio,
            final MarkableTargetImpl target,
            final ReactContext ctx,
            final String summary) {
        return getfullresp -> {
            final Observable<FullMessage<HttpResponse>> cached = getfullresp.cache();

            cached.subscribe(fullresp -> LOG.debug("try get resp: {}", fullresp.message()));

            //  启动转发 (forward)
            return cached.flatMap(fullresp -> {
                LOG.debug("recv response head part {}.", fullresp.message());

                // 404 Not Found
                if (fullresp.message().status().equals(HttpResponseStatus.NOT_FOUND)) {
                    // Request-URI not found in target service, so try next
                    // matched forward target
                    LOG.info("API_NOT_SUPPORTED for target {}, so forward trade({}) to next reactor", target,
                            ctx.trade());
                    return Observable.<InOut>just(null);
                }

                // 5XX Server Internal Error
                if (fullresp.message().status().code() >= 500) {
                    // Server Internal Error
                    target.markAPIDownStatus(true);
                    LOG.warn("SERVER_ERROR({}), so mark service [{}]'s matched {} APIs down.", fullresp.message().status(),
                            target.serviceUri(), _matcher);
                    _timer.newTimeout(timeout -> {
                        // reset down flag
                        target.markAPIDownStatus(false);
                        LOG.info(
                                "reset service [{}]'s matched {} APIs down to false, after {} second cause by SERVER_ERROR({}).",
                                target.serviceUri(), _matcher, _period, fullresp.message().status());
                    }, _period, TimeUnit.SECONDS);
                    return Observable.error(new TransportException("SERVER_ERROR(" + fullresp.message().status() + ")"));
                }

                return Observable.<InOut>just(new InOut() {
                    @Override
                    public Observable<FullMessage<HttpRequest>> inbound() {
                        return orgio.inbound();
                    }

                    @Override
                    public Observable<FullMessage<HttpResponse>> outbound() {
                        return cached.doOnCompleted(()-> LOG.info("forward outbound completed"));
                    }
                });
            });
        };
    }

    private Action1<? super Throwable> onCommunicationError(final MarkableTargetImpl target) {
        return error -> {
            // remember reset to false after a while
            if (isCommunicationFailure(error)) {
                markServiceDownStatus(target, true);
                LOG.warn("COMMUNICATION_FAILURE({}), so mark service [{}] down.",
                        ExceptionUtils.exception2detail(error), target.serviceUri());
                _timer.newTimeout(timeout -> {
                        // reset down flag
                        markServiceDownStatus(target, false);
                        LOG.info(
                                "reset service [{}] down to false, after {} second cause by COMMUNICATION_FAILURE({}).",
                                target.serviceUri(), _period, ExceptionUtils.exception2detail(error));
                    }, _period, TimeUnit.SECONDS);
            }
        };
    }

    private Observable<FullMessage<HttpResponse>> buildOutbound(
            final ReactContext ctx,
            final Observable<FullMessage<HttpRequest>> inbound,
            final Target target,
            final HttpRequest request) {
        final HttpTrade trade = ctx.trade();
        final StopWatch stopWatch = ctx.watch();

        return forwardTo(target).doOnNext(upstream->trade.doOnHalt(upstream.closer()))
                .flatMap(upstream -> {
                    final AtomicBoolean isKeepAliveFromClient = new AtomicBoolean(true);
                    final AtomicReference<HttpRequest> refReq = new AtomicReference<>();
                    final AtomicReference<HttpResponse> refResp = new AtomicReference<>();

                    trade.doOnHalt(() -> {
                            final long ttl = stopWatch.stopAndRestart();
                            final RelayMemo memo = _memoBuilder.build(target, buildRoutingInfo(refReq.get()));
                            memo.incBizResult(RESULT.RELAY_SUCCESS, ttl);
                            LOG.info("FORWARD_SUCCESS" + "\ncost:[{}]s,forward_to:[{}]"
                                            + "\nINCOME:channel:{},request:[{}]bytes,response:[{}]bytes"
                                            + "\nUPSTREAM:channel:{},request:[{}]bytes,response:[{}]bytes"
                                            + "\nREQ\n[{}]\nsendback\nRESP\n[{}]",
                                    ttl / (float) 1000.0,
                                    target.serviceUri(),
                                    trade.transport(),
                                    trade.traffic().inboundBytes(),
                                    trade.traffic().outboundBytes(),
                                    upstream.transport(),
                                    upstream.traffic().outboundBytes(),
                                    upstream.traffic().inboundBytes(),
                                    refReq.get(),
                                    refResp.get()
                                    );
                        });

                    enableDisposeSended(upstream.writeCtrl(), MAX_RETAINED_SIZE);
                    final Span span = ctx2span(ctx, target, request);
                    TraceUtil.addTagNotNull(span, "http.host", request.headers().get(HttpHeaderNames.HOST));

                    upstream.writeCtrl().sending().subscribe(obj -> {
                        if (obj instanceof HttpRequest) {
                            final HttpRequest req = (HttpRequest)obj;
                            ctx.tracer().inject(span.context(), Format.Builtin.HTTP_HEADERS, TraceUtil.message2textmap(req));
                        }
                    });

                    return isDBS().doOnNext(configDBS(trade))
                        .flatMap(any -> upstream.defineInteraction(
                            inbound.map(addKeepAliveIfNeeded(refReq, isKeepAliveFromClient))
                            .compose(fullreq2objs())))
//                        .observeOn(ctx.scheduler())  TODO : disable
                        .map(removeKeepAliveIfNeeded(refResp, isKeepAliveFromClient))
                        .doOnNext(TraceUtil.hookhttpresp(span))
                        .doOnError( e -> {
                            span.setTag(Tags.ERROR.getKey(), true);
                            span.log(Collections.singletonMap("error.detail", ExceptionUtils.exception2detail(e)));
                        })
                        .doOnTerminate(() -> {
                            span.finish();
                            if (span instanceof JaegerSpan) {
                                final String operation = ((JaegerSpan)span).getOperationName();
                                getOrCreateInteractTimer("operation", operation).record(((JaegerSpan)span).getDuration(), TimeUnit.MICROSECONDS);
                                getOrCreateInboundSummary("operation", operation).record(upstream.traffic().inboundBytes());
                                getOrCreateOutboundSummary("operation", operation).record(upstream.traffic().outboundBytes());
                            }
                        });
                });
    }

    private Span ctx2span(final ReactContext ctx, final Target target, final HttpRequest request) {
        final String operationName = buildOperationName(request.uri());
        ctx.span().setOperationName(operationName);
        return ctx.tracer().buildSpan(operationName)
            .withTag(Tags.COMPONENT.getKey(), "jocean-http")
            .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CLIENT)
            .withTag(Tags.HTTP_URL.getKey(), request.uri())
            .withTag(Tags.HTTP_METHOD.getKey(), request.method().name())
            .withTag(Tags.PEER_HOST_IPV4.getKey(), target.serviceUri().getHost())
            .withTag(Tags.PEER_PORT.getKey(), target.serviceUri().getPort())
            .withTag(Tags.PEER_SERVICE.getKey(), this._serviceName)
            .asChildOf(ctx.span())
            .start();
    }

    private Transformer<FullMessage<HttpRequest>, Object> fullreq2objs() {
        return getfullreq -> getfullreq.flatMap(fullreq -> Observable.<Object>just(fullreq.message()).concatWith(fullreq.body().concatMap(body -> body.content()))
                .concatWith(Observable.just(LastHttpContent.EMPTY_LAST_CONTENT)));
    }

    private Action1<? super Boolean> configDBS(final HttpTrade trade) {
        return dbs-> {
            LOG.info("forward: pathPattern {}'s dbs {}", _matcher.pathPattern(), dbs);
            if (dbs) {
                // 对已经发送成功的 DisposableWrapper<?>，及时 invoke it's dispose() 回收占用的资源 (memory, ...)
                trade.writeCtrl().sended().subscribe(sended -> DisposableWrapperUtil.dispose(sended));
            }
        };
    }

    private Observable<? extends HttpInitiator> forwardTo(final Target target) {
        return this._finder.find(HttpClient.class).flatMap(client -> client.initiator()
                .remoteAddress(buildAddress(target.serviceUri())).feature(target.features().call())
                .feature(Feature.ENABLE_LOGGING_OVER_SSL)
                .build());
    }

    private void enableDisposeSended(final WriteCtrl writeCtrl, final int size) {
        final AtomicInteger sendingSize = new AtomicInteger(0);

        writeCtrl.sending().subscribe(sending -> sendingSize.addAndGet(getReadableBytes(sending)));
        writeCtrl.sended().subscribe(sended -> {
            if (sendingSize.get() > size) {
                LOG.info("sendingSize is {}, try dispose sended {}, which DisposableWrapper({})",
                        sendingSize.get(), sended, (sended instanceof DisposableWrapper));
                DisposableWrapperUtil.dispose(sended);
            } else {
                LOG.info("sendingSize is {}, SKIP sended {}", sendingSize.get(), sended);
            }
        });
    }

    private Observable<Boolean> isDBS() {
        return this._finder.find("configs", Map.class).map(conf -> !istrue(conf.get(_matcher.pathPattern() + ":" + "disable_dbs")));
    }

    private static boolean istrue(final Object value) {
        return value == null ? false : value.toString().equals("true");
    }

    private int getReadableBytes(final Object sending) {
        final Object unwrap = DisposableWrapperUtil.unwrap(sending);
        int readableBytes = 0;
        if (unwrap instanceof ByteBuf) {
            readableBytes = ((ByteBuf) unwrap).readableBytes();
        } else if (unwrap instanceof ByteBufHolder) {
            readableBytes = ((ByteBufHolder) unwrap).content().readableBytes();
        }
        LOG.info("{}'s getReadableBytes: {} ", unwrap, readableBytes);
        return readableBytes;
    }

    private InetSocketAddress buildAddress(final URI uri) {
        return new InetSocketAddress(uri.getHost(), uri.getPort());
    }

    private  Func1<FullMessage<HttpRequest>, FullMessage<HttpRequest>> addKeepAliveIfNeeded(
            final AtomicReference<HttpRequest> refReq,
            final AtomicBoolean isKeepAliveFromClient) {
        return fullreq -> {
            refReq.set(fullreq.message());
            // only check first time, bcs inbound could be process many
            // times
            if (isKeepAliveFromClient.get()) {
                isKeepAliveFromClient.set(HttpUtil.isKeepAlive(fullreq.message()));
                if (!isKeepAliveFromClient.get()) {
                    // if NOT keep alive, force it
                    final HttpRequest newreq = new DefaultHttpRequest(
                            fullreq.message().protocolVersion(),
                            fullreq.message().method(),
                            fullreq.message().uri());
                    newreq.headers().add(fullreq.message().headers());
                    newreq.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
                    LOG.info("FORCE-KeepAlive: add Connection header with KeepAlive for incoming req:\n[{}]", fullreq.message());
                    return new FullMessage<HttpRequest>() {
                        @Override
                        public HttpRequest message() {
                            return newreq;
                        }
                        @Override
                        public Observable<? extends MessageBody> body() {
                            return fullreq.body();
                        }};
                }
            }
            return fullreq;
        };
    }

    private Func1<FullMessage<HttpResponse>, FullMessage<HttpResponse>> removeKeepAliveIfNeeded(
            final AtomicReference<HttpResponse> refResp,
            final AtomicBoolean isKeepAliveFromClient) {
        return fullresp -> {
            refResp.set(fullresp.message());
            if (!isKeepAliveFromClient.get()) {
                if (HttpUtil.isKeepAlive(fullresp.message())) {
                    // if NOT keep alive from client, remove keepalive
                    // header
                    final HttpResponse newresp = new DefaultHttpResponse(fullresp.message().protocolVersion(),
                            fullresp.message().status());
                    newresp.headers().add(fullresp.message().headers());
                    newresp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                    LOG.info("FORCE-KeepAlive: set Connection header with Close for sendback resp:\n[{}]", fullresp.message());
                    return new FullMessage<HttpResponse>() {
                        @Override
                        public HttpResponse message() {
                            return newresp;
                        }
                        @Override
                        public Observable<? extends MessageBody> body() {
                            return fullresp.body();
                        }};
                }
            }
            return fullresp;
        };
    }

    private MarkableTargetImpl selectTarget() {
        int total = 0;
        MarkableTargetImpl best = null;
        for ( final MarkableTargetImpl peer : this._targets ) {
            if ( isTargetActive(peer) ) {
                // nginx C code: peer->current_weight += peer->effective_weight;
                final int effectiveWeight = peer._effectiveWeight.get();
                final int currentWeight = peer._currentWeight.addAndGet( effectiveWeight );
                total += effectiveWeight;
//  nginx C code:
//                if (best == NULL || peer->current_weight > best->current_weight) {
//                    best = peer;
//                }
                if ( null == best || best._currentWeight.get() < currentWeight ) {
                    best = peer;
                }
            }
        }

        if (null == best) {
            return null;
        }

// nginx C code: best->current_weight -= total;
        best._currentWeight.addAndGet(-total);

        return best;
    }

    private boolean isTargetActive(final MarkableTargetImpl target) {
        return !(this._serviceMemo.isServiceDown(target.serviceUri()) || target._down.get());
    }

    private void markServiceDownStatus(final Target target, final boolean isDown) {
        this._serviceMemo.markServiceDownStatus(target.serviceUri(), isDown);
    }

    private RoutingInfo buildRoutingInfo(final HttpRequest req) {
        final String path = pathOf(req);
        return new RoutingInfo() {
            @Override
            public String getMethod() {
                return req.method().name();
            }

            @Override
            public String getPath() {
                return path;
            }};
    }

    private String pathOf(final HttpRequest req) {
        final QueryStringDecoder decoder = new QueryStringDecoder(req.uri());

        String path = decoder.path();
        final int p = path.indexOf(";");
        if (p>-1) {
            path = path.substring(0, p);
        }
        return path;
    }

    private boolean isCommunicationFailure(final Throwable error) {
        return error instanceof ConnectException;
    }

    private class MarkableTargetImpl implements Target {

        private static final int MAX_EFFECTIVEWEIGHT = 1000;

        @Override
        public String toString() {
            return this._target.toString();
        }

        MarkableTargetImpl(final Target target) {
            this._target = target;
        }

        @Override
        public URI serviceUri() {
            return this._target.serviceUri();
        }

        @Override
        public Func0<Feature[]> features() {
            return this._target.features();
        }

        @SuppressWarnings("unused")
        public int addWeight(final int deltaWeight) {
            int weight = this._effectiveWeight.addAndGet(deltaWeight);
            if ( weight > MAX_EFFECTIVEWEIGHT ) {
                weight = this._effectiveWeight.addAndGet(-deltaWeight);
            }
            return weight;
        }

        public void markAPIDownStatus(final boolean isDown) {
            this._down.set(isDown);
        }

        private final Target _target;
        private final AtomicInteger _currentWeight = new AtomicInteger(1);
        private final AtomicInteger _effectiveWeight = new AtomicInteger(1);
        private final AtomicBoolean _down = new AtomicBoolean(false);
    }

    private Timer getOrCreateInteractTimer(final String... tags) {
        final StringTags keyOfTags = new StringTags(tags);

        Timer timer = this._interactTimers.get(keyOfTags);

        if (null == timer) {
            timer = Timer.builder("jocean.xharbor.interact.duration")
                .tags(tags)
                .description("The duration of jocean xharbor interact")
                .publishPercentileHistogram()
                .maximumExpectedValue(Duration.ofSeconds(30))
                .register(_meterRegistry);

            final Timer old = this._interactTimers.putIfAbsent(keyOfTags, timer);
            if (null != old) {
                timer = old;
            }
        }
        return timer;
    }

    private DistributionSummary getOrCreateInboundSummary(final String... tags) {
        final StringTags keyOfTags = new StringTags(tags);

        DistributionSummary summary = this._inboundSummarys.get(keyOfTags);

        if (null == summary) {
            summary = DistributionSummary.builder("jocean.xharbor.interact.inbound")
                .tags(tags)
                .description("The inbound size of jocean xharbor interact") // optional
                .baseUnit(BaseUnits.BYTES)
                .publishPercentileHistogram()
                .maximumExpectedValue( 8 * 1024L)
                .register(_meterRegistry);

            final DistributionSummary old = this._inboundSummarys.putIfAbsent(keyOfTags, summary);
            if (null != old) {
                summary = old;
            }
        }
        return summary;
    }

    private DistributionSummary getOrCreateOutboundSummary(final String... tags) {
        final StringTags keyOfTags = new StringTags(tags);

        DistributionSummary summary = this._outboundSummarys.get(keyOfTags);

        if (null == summary) {
            summary = DistributionSummary.builder("jocean.xharbor.interact.outbound")
                .tags(tags)
                .description("The outbound size of jocean xharbor interact") // optional
                .baseUnit(BaseUnits.BYTES)
                .publishPercentileHistogram()
                .maximumExpectedValue( 8 * 1024L)
                .register(_meterRegistry);

            final DistributionSummary old = this._outboundSummarys.putIfAbsent(keyOfTags, summary);
            if (null != old) {
                summary = old;
            }
        }
        return summary;
    }

    private final MatchRule     _matcher;
    private final List<MarkableTargetImpl>  _targets = Lists.newCopyOnWriteArrayList();

    private final String        _serviceName;
    private final BeanFinder    _finder;
    private final RelayMemo.Builder _memoBuilder;
    private final ServiceMemo   _serviceMemo;
    private final io.netty.util.Timer _timer;
    private final MeterRegistry _meterRegistry;

    private final ConcurrentMap<StringTags, Timer> _interactTimers = new ConcurrentHashMap<>();
    private final ConcurrentMap<StringTags, DistributionSummary> _inboundSummarys = new ConcurrentHashMap<>();
    private final ConcurrentMap<StringTags, DistributionSummary> _outboundSummarys = new ConcurrentHashMap<>();
}
