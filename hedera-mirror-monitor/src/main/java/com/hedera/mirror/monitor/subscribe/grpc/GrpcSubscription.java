package com.hedera.mirror.monitor.subscribe.grpc;

/*-
 * ‌
 * Hedera Mirror Node
 * ​
 * Copyright (C) 2019 - 2021 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import com.google.common.base.Stopwatch;
import com.google.common.collect.ConcurrentHashMultiset;
import com.google.common.collect.Multiset;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.math3.util.Precision;

import com.hedera.datagenerator.sdk.supplier.TransactionType;
import com.hedera.hashgraph.sdk.TopicId;
import com.hedera.hashgraph.sdk.TopicMessage;
import com.hedera.hashgraph.sdk.TopicMessageQuery;
import com.hedera.mirror.monitor.subscribe.Subscription;

@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Log4j2
public class GrpcSubscription implements Subscription {

    @EqualsAndHashCode.Include
    private final int id;

    @EqualsAndHashCode.Include
    private final GrpcSubscriberProperties properties;

    private final AtomicLong count = new AtomicLong(0L);
    private final Multiset<String> errors = ConcurrentHashMultiset.create();
    private final AtomicLong lastCount = new AtomicLong(0L);
    private final AtomicLong lastElapsed = new AtomicLong(0L);
    private final Stopwatch stopwatch = Stopwatch.createStarted();
    private volatile Optional<TopicMessage> last = Optional.empty();

    @Override
    public long getCount() {
        return count.get();
    }

    @Override
    public Map<String, Integer> getErrors() {
        Map<String, Integer> errorCounts = new TreeMap<>();
        errors.forEachEntry(errorCounts::put);
        return Collections.unmodifiableMap(errorCounts);
    }

    @Override
    public double getRate() {
        long count = getCount();
        long elapsed = stopwatch.elapsed(TimeUnit.MICROSECONDS);
        long instantCount = count - lastCount.getAndSet(count);
        long instantElapsed = elapsed - lastElapsed.getAndSet(elapsed);
        return getRate(instantCount, instantElapsed);
    }

    private double getRate(long count, long elapsedMicros) {
        return Precision.round(elapsedMicros > 0 ? (count * 1000000.0) / elapsedMicros : 0.0, 1);
    }

    @Override
    public TransactionType getType() {
        return TransactionType.CONSENSUS_SUBMIT_MESSAGE;
    }

    TopicMessageQuery getTopicMessageQuery() {
        long limit = properties.getLimit();
        Instant startTime = last.map(t -> t.consensusTimestamp.plusNanos(1))
                .orElseGet(properties::getStartTime);

        TopicMessageQuery topicMessageQuery = new TopicMessageQuery();
        topicMessageQuery.setEndTime(properties.getEndTime());
        topicMessageQuery.setLimit(limit > 0 ? limit - count.get() : 0);
        topicMessageQuery.setStartTime(startTime);
        topicMessageQuery.setTopicId(TopicId.fromString(properties.getTopicId()));
        return topicMessageQuery;
    }

    void onComplete() {
        stopwatch.stop();
    }

    void onNext(TopicMessage topicResponse) {
        count.incrementAndGet();
        log.trace("{}: Received message #{} with timestamp {}", this, topicResponse.sequenceNumber,
                topicResponse.consensusTimestamp);

        last.ifPresent(topicMessage -> {
            long expected = topicMessage.sequenceNumber + 1;
            if (topicResponse.sequenceNumber != expected) {
                log.warn("{}: Expected sequence number {} but received {}", this, expected,
                        topicResponse.sequenceNumber);
            }
        });

        last = Optional.of(topicResponse);
    }

    void onError(Throwable t) {
        Status.Code statusCode = Status.Code.UNKNOWN;
        if (t instanceof StatusRuntimeException) {
            statusCode = ((StatusRuntimeException) t).getStatus().getCode();
        }
        errors.add(statusCode.name());
    }

    @Override
    public String toString() {
        String name = getProperties().getName();
        return getProperties().getSubscribers() <= 1 ? name : name + " #" + getId();
    }
}
