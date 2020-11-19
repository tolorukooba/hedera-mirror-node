package com.hedera.mirror.grpc.controller;

/*-
 * ‌
 * Hedera Mirror Node
 * ​
 * Copyright (C) 2019 - 2020 Hedera Hashgraph, LLC
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

import com.hederahashgraph.api.proto.java.Timestamp;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Instant;
import java.util.concurrent.TimeoutException;
import javax.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.dao.NonTransientDataAccessResourceException;
import org.springframework.dao.TransientDataAccessException;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import com.hedera.mirror.api.proto.ConsensusTopicQuery;
import com.hedera.mirror.api.proto.ConsensusTopicResponse;
import com.hedera.mirror.api.proto.ReactorConsensusServiceGrpc;
import com.hedera.mirror.grpc.converter.InstantToLongConverter;
import com.hedera.mirror.grpc.domain.TopicMessage;
import com.hedera.mirror.grpc.domain.TopicMessageFilter;
import com.hedera.mirror.grpc.exception.TopicNotFoundException;
import com.hedera.mirror.grpc.service.TopicMessageService;
import com.hedera.mirror.grpc.util.ProtoUtil;

/**
 * GRPC calls their protocol adapter layer a service, but most of the industry calls this layer the controller layer.
 * See the Front Controller pattern or Model-View-Controller (MVC) pattern. The service layer is generally reserved for
 * non-protocol specific business logic so to avoid confusion with our TopicMessageService we'll name this GRPC layer as
 * controller.
 */
@GrpcService
@Log4j2
@RequiredArgsConstructor
public class ConsensusController extends ReactorConsensusServiceGrpc.ConsensusServiceImplBase {

    private static final String DB_ERROR = "Unable to connect to database. Please retry later";
    private static final String OVERFLOW_ERROR = "Client lags too much behind. Please retry later";

    private final TopicMessageService topicMessageService;

    @Override
    public Flux<ConsensusTopicResponse> subscribeTopic(Mono<ConsensusTopicQuery> request) {
        return request.map(this::toFilter)
                .flatMapMany(topicMessageService::subscribeTopic)
                .map(TopicMessage::getResponse)
                .onErrorMap(this::mapError); // consolidate error mappings to avoid deep flux operation chaining
    }

    private TopicMessageFilter toFilter(ConsensusTopicQuery query) {
        if (!query.hasTopicID()) {
            log.warn("Missing required topicID");
            throw Status.INVALID_ARGUMENT.augmentDescription("Missing required topicID").asRuntimeException();
        }

        TopicMessageFilter.TopicMessageFilterBuilder builder = TopicMessageFilter.builder()
                .limit(query.getLimit())
                .realmNum((int) query.getTopicID().getRealmNum())
                .topicNum((int) query.getTopicID().getTopicNum());

        if (query.hasConsensusStartTime()) {
            Timestamp startTimeStamp = query.getConsensusStartTime();
            Instant startInstant = ProtoUtil.fromTimestamp(startTimeStamp);
            builder.startTime(startInstant.isBefore(Instant.EPOCH) ? Instant.EPOCH : startInstant);
        }

        if (query.hasConsensusEndTime()) {
            Timestamp endTimeStamp = query.getConsensusEndTime();
            Instant endInstant = ProtoUtil.fromTimestamp(endTimeStamp);
            builder.endTime(endInstant.isAfter(InstantToLongConverter.LONG_MAX_INSTANT) ?
                    InstantToLongConverter.LONG_MAX_INSTANT : endInstant);
        }

        return builder.build();
    }

    private StatusRuntimeException mapError(Throwable t) {
        if (t instanceof ConstraintViolationException || t instanceof IllegalArgumentException) {
            return error(t, Status.INVALID_ARGUMENT);
        } else if (t instanceof NonTransientDataAccessResourceException) {
            return error(t, Status.UNAVAILABLE, DB_ERROR);
        } else if (t instanceof TimeoutException) {
            return error(t, Status.RESOURCE_EXHAUSTED);
        } else if (t instanceof TopicNotFoundException) {
            return error(t, Status.NOT_FOUND);
        } else if (t instanceof TransientDataAccessException) {
            return error(t, Status.RESOURCE_EXHAUSTED);
        } else if (Exceptions.isOverflow(t)) {
            return error(t, Status.DEADLINE_EXCEEDED, OVERFLOW_ERROR);
        } else if (t instanceof StatusRuntimeException) {
            return (StatusRuntimeException) t;
        }

        final String message = "Unknown error subscribing to topic";
        log.error(message, t);
        return Status.UNKNOWN.augmentDescription(message).asRuntimeException();
    }

    private StatusRuntimeException error(Throwable t, Status status) {
        return error(t, status, t.getMessage());
    }

    private StatusRuntimeException error(Throwable t, Status status, String message) {
        log.warn("Received {} subscribing to topic: {}", t.getClass().getSimpleName(), t.getMessage());
        return status.augmentDescription(message).asRuntimeException();
    }
}
