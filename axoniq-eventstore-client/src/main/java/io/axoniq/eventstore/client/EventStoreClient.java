/*
 * Copyright (c) 2017. AxonIQ
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.axoniq.eventstore.client;

import io.axoniq.eventstore.Event;
import io.axoniq.eventstore.client.util.Broadcaster;
import io.axoniq.eventstore.client.util.EventCipher;
import io.axoniq.eventstore.client.util.EventStoreClientException;
import io.axoniq.eventstore.client.util.GrpcExceptionParser;
import io.axoniq.eventstore.grpc.*;
import io.grpc.Channel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 */
public class EventStoreClient {
    private final Logger logger = LoggerFactory.getLogger(EventStoreClient.class);

    private final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1);
    private final EventStoreConfiguration eventStoreConfiguration;
    private final TokenAddingInterceptor tokenAddingInterceptor;
    private final EventCipher eventCipher;

    private final AtomicReference<ClusterInfo> eventStoreServer = new AtomicReference<>();
    private final ChannelManager channelManager;
    private boolean shutdown;

    public EventStoreClient(EventStoreConfiguration eventStoreConfiguration) {
        this.eventStoreConfiguration = eventStoreConfiguration;
        this.tokenAddingInterceptor = new TokenAddingInterceptor(eventStoreConfiguration.getToken());
        this.channelManager = new ChannelManager(eventStoreConfiguration.getCertFile());
        this.eventCipher = eventStoreConfiguration.getEventCipher();
    }

    public void shutdown() {
        shutdown = true;
        channelManager.cleanup();
    }

    private EventStoreGrpc.EventStoreStub eventStoreStub() {
        return EventStoreGrpc.newStub(getChannelToEventStore()).withInterceptors(tokenAddingInterceptor);
    }

    private ClusterInfo discoverEventStore() {
        eventStoreServer.set(null);
        Broadcaster<ClusterInfo> b = new Broadcaster<>(eventStoreConfiguration.getServerNodes(), this::retrieveClusterInfo, this::nodeReceived);
        try {
            b.broadcast(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClientConnectionException("Thread was interrupted while attempting to connect to the server", e);
        }
        return eventStoreServer.get();
    }

    private void nodeReceived(ClusterInfo node) {
        logger.info("Received: {}:{}", node.getMaster().getHostName(), node.getMaster().getGrpcPort());
        eventStoreServer.set(node);
    }

    private void retrieveClusterInfo(NodeInfo nodeInfo, StreamObserver<ClusterInfo> streamObserver) {
        Channel channel = channelManager.getChannel(nodeInfo);
        ClusterGrpc.ClusterStub clusterManagerStub = ClusterGrpc.newStub(channel).withInterceptors(new TokenAddingInterceptor(eventStoreConfiguration.getToken()));
        clusterManagerStub.retrieveClusterInfo(RetrieveClusterInfoRequest.newBuilder().build(), streamObserver);
    }

    private Channel getChannelToEventStore() {
        if (shutdown) return null;
        CompletableFuture<ClusterInfo> masterInfoCompletableFuture = new CompletableFuture<>();
        getEventStoreAsync(eventStoreConfiguration.getConnectionRetryCount(), masterInfoCompletableFuture);
        try {
            return channelManager.getChannel(masterInfoCompletableFuture.get().getMaster());
        } catch( ExecutionException e) {
            throw (RuntimeException) e.getCause();
        } catch (InterruptedException e) {
            throw new EventStoreClientException("AXONIQ-0001", e.getMessage(), e);
        }
    }

    private void getEventStoreAsync(int retries, CompletableFuture<ClusterInfo> result) {
        ClusterInfo currentEventStore = eventStoreServer.get();
        if (currentEventStore != null) {
            result.complete(currentEventStore);
        } else {
            currentEventStore = discoverEventStore();
            if (currentEventStore != null) {
                result.complete(currentEventStore);
            } else {
                if (retries > 0)
                    executorService.schedule(() -> getEventStoreAsync(retries - 1, result),
                                             eventStoreConfiguration.getConnectionRetry(), TimeUnit.MILLISECONDS);
                else
                    result.completeExceptionally(new EventStoreClientException("AXONIQ-0001", "No available event store server"));
            }
        }
    }

    private void stopChannelToEventStore() {
        ClusterInfo current = eventStoreServer.getAndSet(null);
        if (current != null) {
            logger.info("Shutting down gRPC channel");
            channelManager.shutdown(current);
        }
    }

    /**
     * Retrieves the events for an aggregate described in given {@code request}.
     *
     * @param request The request describing the aggregate to retrieve messages for
     * @return a Stream providing access to Events published by the aggregate described in the request
     * @throws ExecutionException   when an error was reported while reading events
     * @throws InterruptedException when the thread was interrupted while reading events from the server
     */
    public Stream<Event> listAggregateEvents(GetAggregateEventsRequest request) throws ExecutionException, InterruptedException {
        CompletableFuture<Stream<Event>> stream = new CompletableFuture<>();
        long before = System.currentTimeMillis();

        eventStoreStub().listAggregateEvents(request, new StreamObserver<Event>() {
            Stream.Builder<Event> eventStream = Stream.builder();
            int count;

            @Override
            public void onNext(Event event) {
                eventStream.accept(eventCipher.decrypt(event));
                count++;
            }

            @Override
            public void onError(Throwable throwable) {
                checkConnectionException(throwable);
                stream.completeExceptionally(GrpcExceptionParser.parse(throwable));
            }

            @Override
            public void onCompleted() {
                stream.complete(eventStream.build());
                if (logger.isDebugEnabled()) {
                    logger.debug("Done request for {}: {}ms, {} events", request.getAggregateId(), System.currentTimeMillis() - before, count);
                }
            }
        });
        return stream.get();
    }

    /**
     *
     * @param responseStreamObserver: observer for messages from server
     * @return stream observer to send request messages to server
     */
    public StreamObserver<GetEventsRequest> listEvents(StreamObserver<EventWithToken> responseStreamObserver) {
        StreamObserver<EventWithToken> wrappedStreamObserver = new StreamObserver<EventWithToken>() {
            @Override
            public void onNext(EventWithToken eventWithToken) {
                responseStreamObserver.onNext(eventCipher.decrypt(eventWithToken));
            }

            @Override
            public void onError(Throwable throwable) {
                checkConnectionException(throwable);
                responseStreamObserver.onError(GrpcExceptionParser.parse(throwable));
            }

            @Override
            public void onCompleted() {
                responseStreamObserver.onCompleted();

            }
        };
        return eventStoreStub().listEvents(wrappedStreamObserver);
    }

    public CompletableFuture<Confirmation> appendSnapshot(Event snapshot) {

        CompletableFuture<Confirmation> confirmationFuture = new CompletableFuture<>();
        eventStoreStub().appendSnapshot(eventCipher.encrypt(snapshot), new StreamObserver<Confirmation>() {
            @Override
            public void onNext(Confirmation confirmation) {
                confirmationFuture.complete(confirmation);
            }

            @Override
            public void onError(Throwable throwable) {
                checkConnectionException(throwable);
                confirmationFuture.completeExceptionally(GrpcExceptionParser.parse(throwable));
            }

            @Override
            public void onCompleted() {

            }
        });

        return confirmationFuture;
    }

    public AppendEventTransaction createAppendEventConnection() {
        CompletableFuture<Confirmation> futureConfirmation = new CompletableFuture<>();
        return new AppendEventTransaction(eventStoreStub().appendEvent(new StreamObserver<Confirmation>() {
            @Override
            public void onNext(Confirmation confirmation) {
                futureConfirmation.complete(confirmation);
            }

            @Override
            public void onError(Throwable throwable) {
                checkConnectionException(throwable);
                futureConfirmation.completeExceptionally(GrpcExceptionParser.parse(throwable));
            }

            @Override
            public void onCompleted() {
                // no-op: already
            }
        }), futureConfirmation, eventCipher);
    }

    private void checkConnectionException(Throwable ex) {
        if (ex instanceof StatusRuntimeException && ((StatusRuntimeException) ex).getStatus().getCode().equals(Status.UNAVAILABLE.getCode())) {
            stopChannelToEventStore();
        }
    }

}
