/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2015 ForgeRock AS.
 */
package org.forgerock.openig.filter;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.forgerock.openig.util.JsonValues.asExpression;
import static org.forgerock.util.time.Duration.duration;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.forgerock.guava.common.base.Ticker;
import org.forgerock.guava.common.cache.CacheBuilder;
import org.forgerock.guava.common.cache.CacheLoader;
import org.forgerock.guava.common.cache.LoadingCache;
import org.forgerock.http.Context;
import org.forgerock.http.Filter;
import org.forgerock.http.Handler;
import org.forgerock.http.protocol.Request;
import org.forgerock.http.protocol.Response;
import org.forgerock.http.protocol.Status;
import org.forgerock.json.fluent.JsonValue;
import org.forgerock.openig.el.Expression;
import org.forgerock.openig.heap.GenericHeapObject;
import org.forgerock.openig.heap.GenericHeaplet;
import org.forgerock.openig.heap.HeapException;
import org.forgerock.openig.heap.Keys;
import org.forgerock.openig.http.Exchange;
import org.forgerock.openig.http.Responses;
import org.forgerock.util.promise.NeverThrowsException;
import org.forgerock.util.promise.Promise;
import org.forgerock.util.promise.Promises;
import org.forgerock.util.time.Duration;
import org.forgerock.util.time.TimeService;

/**
 * This filter allows to limit the output rate to the specified handler. If the output rate is over, there a response
 * with status 429 (Too Many Requests) is sent.
 */
public class ThrottlingFilter extends GenericHeapObject implements Filter {

    private final LoadingCache<String, TokenBucket> buckets;
    private final Expression<String> partitionKey;

    /**
     * Constructs a ThrottlingFilter with no partition key.
     *
     * @param time
     *            the time service.
     * @param numberOfRequests
     *            the maximum of requests that can be filtered out during the duration.
     * @param duration
     *            the duration of the sliding window.
     */
    public ThrottlingFilter(final TimeService time,
                            final int numberOfRequests,
                            final Duration duration) {
        this(time, numberOfRequests, duration, null);
    }

    /**
     * Constructs a ThrottlingFilter.
     *
     * @param time
     *            the time service.
     * @param numberOfRequests
     *            the maximum of requests that can be filtered out during the duration.
     * @param duration
     *            the duration of the sliding window.
     * @param partitionKey
     *            the optional expression that tells in which bucket we have to take some token to count the output
     *            rate.
     */
    public ThrottlingFilter(final TimeService time,
                            final int numberOfRequests,
                            final Duration duration,
                            final Expression<String> partitionKey) {
        this.buckets = setupBuckets(time, numberOfRequests, duration);
        this.partitionKey = partitionKey;
    }

    private LoadingCache<String, TokenBucket> setupBuckets(final TimeService time,
                                                           final int numberOfRequests,
                                                           final Duration duration) {

        CacheLoader<String, TokenBucket> loader = new CacheLoader<String, TokenBucket>() {
            @Override
            public TokenBucket load(String key) {
                return new TokenBucket(time, numberOfRequests, duration);
            }
        };

        Ticker ticker = new Ticker() {

            @Override
            public long read() {
                // We need to return now in nanoseconds.
                return time.now() * 1000;
            }
        };
        // Let's give some delay for the eviction
        long expire = duration.to(TimeUnit.MILLISECONDS) + 3;
        return CacheBuilder.newBuilder()
                           .ticker(ticker)
                           .expireAfterAccess(expire, TimeUnit.MILLISECONDS)
                           .recordStats()
                           .build(loader);
    }

    @Override
    public Promise<Response, NeverThrowsException> filter(Context context, Request request, Handler next) {
        final Exchange exchange = context.asContext(Exchange.class);

        final String key = partitionKey == null ? "" : partitionKey.eval(exchange);
        if (key == null) {
            return Promises.newResultPromise(Responses.newInternalServerError());
        }

        TokenBucket bucket;
        try {
            bucket = buckets.get(key);

            return filter(bucket, context, request, next);
        } catch (ExecutionException e) {
            return Promises.newResultPromise(Responses.newInternalServerError());
        }
    }

    private Promise<Response, NeverThrowsException> filter(TokenBucket bucket,
                                                           Context context,
                                                           Request request,
                                                           Handler next) {
        final long delay = bucket.tryConsume();
        if (delay <= 0) {
            return next.handle(context, request);
        } else {
            // http://tools.ietf.org/html/rfc6585#section-4
            Response result = new Response(Status.TOO_MANY_REQUESTS);
            // http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.37
            result.getHeaders().add("Retry-After", Long.toString(SECONDS.convert(delay, MILLISECONDS)));
            return Promises.newResultPromise(result);
        }
    }

    /**
     * Creates and initializes a throttling filter in a heap environment.
     */
    public static class Heaplet extends GenericHeaplet {
        @Override
        public Object create() throws HeapException {
            TimeService time = heap.get(Keys.TIME_SERVICE_HEAP_KEY, TimeService.class);

            JsonValue rate = config.get("rate").required();

            Integer numberOfRequests = rate.get("numberOfRequests").required().asInteger();
            Duration duration = duration(rate.get("duration").required().asString());
            Expression<String> partitionKey = asExpression(config.get("partitionKey"), String.class);

            return new ThrottlingFilter(time, numberOfRequests, duration, partitionKey);
        }
    }
}