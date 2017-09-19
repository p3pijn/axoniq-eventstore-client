package io.axoniq.eventstore.client.axon;

import io.axoniq.eventstore.client.util.EventStoreClientException;
import org.axonframework.commandhandling.model.AggregateRolledBackException;
import org.axonframework.commandhandling.model.ConcurrencyException;
import org.axonframework.common.AxonException;
import org.axonframework.eventsourcing.eventstore.EventStoreException;

import java.util.Arrays;
import java.util.concurrent.ExecutionException;


/**
 * Author: marc
 * Converts an EventStoreClientException to the relevant Axon framework exception.
 */
public enum AxonErrorMapping {
    // Generic errors processing client request
    AUTHENTICATION_TOKEN_MISSING("AXONIQ-1000", EventStoreException.class),
    AUTHENTICATION_INVALID_TOKEN("AXONIQ-1001", EventStoreException.class),
    NODE_IS_REPLICA("AXONIQ-1100", EventStoreException.class),

    // Input errors
    INVALID_SEQUENCE("AXONIQ-2000", ConcurrencyException.class),
    PAYLOAD_TOO_LARGE("AXONIQ-2001", EventStoreException.class),
    NO_MASTER_AVAILABLE("AXONIQ-2100", EventStoreException.class),

    // Internal errors
    DATAFILE_READ_ERROR( "AXONIQ-9000", EventStoreException.class),
    INDEX_READ_ERROR( "AXONIQ-9001", EventStoreException.class),
    DATAFILE_WRITE_ERROR( "AXONIQ-9100", EventStoreException.class),
    INDEX_WRITE_ERROR( "AXONIQ-9101", EventStoreException.class),
    DIRECTORY_CREATION_FAILED("AXONIQ-9102", EventStoreException.class),
    VALIDATION_FAILED( "AXONIQ-9200", EventStoreException.class),
    TRANSACTION_ROLLED_BACK( "AXONIQ-9900", AggregateRolledBackException.class),
    OTHER( "AXONIQ-0001", EventStoreException.class),
    ;

    private final String code;
    private final Class exceptionClass;

    AxonErrorMapping(String code, Class<? extends AxonException> exceptionClass) {
        this.code = code;
        this.exceptionClass = exceptionClass;
    }


    public static Class<? extends AxonException> lookupExceptionClass(String code) {
        return Arrays.stream(values()).filter(mapping -> mapping.code.equals(code))
                .map(mapping -> mapping.exceptionClass)
                .findFirst().orElse(OTHER.exceptionClass);
    }

    public static AxonException convert(Throwable t) {
        if( t instanceof EventStoreClientException) {
            Class<? extends AxonException> clazz = lookupExceptionClass(((EventStoreClientException)t).getCode());
            try {
                return clazz.getDeclaredConstructor(String.class, Throwable.class).newInstance(t.getMessage(), t.getCause());
            } catch (Exception ex) {
            }
        }

        return new EventStoreException(t.getMessage(), t);
    }

}