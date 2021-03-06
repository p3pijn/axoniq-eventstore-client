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

package io.axoniq.eventstore.client.util;

import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 */
public class FlowControllingStreamObserver<Request, Response> {
    private final StartCommand<Request, Response> startCommand;
    private final OnNext<Request, Response> onNext;
    private final OnError onError;
    private Logger logger = LoggerFactory.getLogger(FlowControllingStreamObserver.class);
    private StreamObserver<Request> requestStream;
    private Request nextRequest;
    private AtomicInteger permitsLeft;
    private int next;
    private int threshold;
    private boolean flowControl;
    private boolean stopped;

    public FlowControllingStreamObserver(StartCommand<Request, Response> startCommand, OnNext<Request, Response> onNext, OnError onError) {
        this.startCommand = startCommand;
        this.onNext = onNext;
        this.onError = onError;
    }

    public void stop() {
        logger.info("Observer stopped");
        stopped = true;
        try {
            requestStream.onCompleted();
        } catch (Exception ex) {

        }
    }

    public void markConsumed(int i) {
        if (flowControl) {
            int currentCount = permitsLeft.addAndGet(-i);
            if (currentCount <= threshold) {
                requestStream.onNext(nextRequest);
                permitsLeft.addAndGet(next);
            }
        }
    }

    public void start(Request initialRequest, Request nextRequest, int initial, int next, int threshold) {
        this.nextRequest = nextRequest;
        this.permitsLeft = new AtomicInteger(initial);
        this.next = next;
        this.threshold = threshold;
        this.flowControl = (nextRequest != null && initial > 0);

        requestStream = startCommand.call(new FlowControlledResponseStream());
        requestStream.onNext(initialRequest);
    }

    private void handleError(Throwable throwable) {
        if (!stopped) {
            onError.error(throwable);
        }
    }

    public interface StartCommand<Request, Response> {
        StreamObserver<Request> call(StreamObserver<Response> responseObserver);
    }

    public interface OnNext<Request, Response> {
        void next(Response response, StreamObserver<Request> requestStreamObserver);
    }


    public interface OnError {
        void error(Throwable throwable);
    }

    private class FlowControlledResponseStream implements StreamObserver<Response> {

        @Override
        public void onNext(Response response) {
            onNext.next(response, requestStream);
        }

        @Override
        public void onError(Throwable throwable) {
            logger.error("Received error: {}", throwable.getMessage());
            handleError(throwable);
        }

        @Override
        public void onCompleted() {
            logger.debug("OnCompleted");
        }
    }

}
