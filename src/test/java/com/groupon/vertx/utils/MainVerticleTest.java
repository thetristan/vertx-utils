/**
 * Copyright 2015 Groupon.com
 *
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
package com.groupon.vertx.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.vertx.core.AsyncResult;
import io.vertx.core.AsyncResultHandler;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.MessageCodec;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/**
 * Test cases for MainVerticle
 *
 * @author Tristan Blease (tblease at groupon dot com)
 * @since 2.0.1
 * @version 2.0.1
 */
public class MainVerticleTest {
    private static final int TEST_TIMEOUT = 500;

    @Mock
    private Vertx vertx;

    @Mock
    private Context context;

    private MainVerticle verticle;
    private CountDownLatch latch;
    private JsonObject config;
    private Future<Void> deployResult = Future.future();
    private Future<Void> startedResult = Future.future();

    @Captor
    private ArgumentCaptor<AsyncResultHandler<String>> handlerCaptor;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        verticle = spy(new MainVerticle());
        verticle.init(vertx, context);

        config = new JsonObject();
        when(context.config()).thenReturn(config);

        doReturn(deployResult).when(verticle).deployVerticles(config);

        latch = new CountDownLatch(1);
    }

    @After
    public void ensureFinish() throws Exception {
        latch.await(TEST_TIMEOUT, TimeUnit.MILLISECONDS);

        if (latch.getCount() != 0) {
            fail("Timed out; test did not finish in " + TEST_TIMEOUT + "ms");
        }
    }

    @Test
    public void testSuccess() {
        startedResult.setHandler(new AsyncResultHandler<Void>() {
            @Override
            public void handle(AsyncResult<Void> result) {
                assertTrue(result.succeeded());
                latch.countDown();
            }
        });

        verticle.start(startedResult);
        deployResult.complete(null);
    }

    @Test
    public void testFailure() {
        verticle.start(startedResult);
        deployResult.fail(new Exception("failure"));
        verify(vertx).close();
        latch.countDown();
    }

    @Test
    public void testFailureWithoutShutdown() {
        config.put("abortOnFailure", false);

        startedResult.setHandler(new AsyncResultHandler<Void>() {
            @Override
            public void handle(AsyncResult<Void> result) {
                assertTrue(result.failed());
                verify(vertx, never()).close();
                latch.countDown();
            }
        });

        verticle.start(startedResult);
        deployResult.fail(new Exception("failure"));
    }

    @Test
    public void testMessageCodecCausingFailure() {
        config.put("messageCodecs", new JsonArray("[\"com.groupon.vertx.utils.MainVerticleTest$NonExistentCodec\"]"));
        verticle.start(startedResult);
        verify(vertx).close();
        latch.countDown();
    }

    @Test
    public void testMessageCodecIgnoringFailure() {
        config.put("abortOnFailure", false);
        config.put("messageCodecs", new JsonArray("[\"com.groupon.vertx.utils.MainVerticleTest$NonExistentCodec\"]"));

        startedResult.setHandler(new AsyncResultHandler<Void>() {
            @Override
            public void handle(AsyncResult<Void> result) {
                assertFalse(result.failed());
                verify(vertx, never()).close();
                latch.countDown();
            }
        });

        verticle.start(startedResult);
        deployResult.complete(null);
    }

    @Test
    public void testRegisterMessageCodecs() throws MainVerticle.CodecRegistrationException {
        config.put("messageCodecs", new JsonArray("[\"com.groupon.vertx.utils.MainVerticleTest$MyMessageCodec\"]"));

        final EventBus eventBus = Mockito.mock(EventBus.class);
        Mockito.doReturn(eventBus).when(vertx).eventBus();

        MainVerticle.registerMessageCodecs(vertx, config, false);

        Mockito.verify(eventBus).registerCodec(Mockito.any(MyMessageCodec.class));
        latch.countDown();
    }

    @Test
    public void testRegisterMessageCodecNotFoundIgnoreFailure() throws MainVerticle.CodecRegistrationException {
        config.put("messageCodecs", new JsonArray("[\"com.groupon.vertx.utils.MainVerticleTest$NonExistentCodec\"]"));

        final EventBus eventBus = Mockito.mock(EventBus.class);
        Mockito.doReturn(eventBus).when(vertx).eventBus();

        MainVerticle.registerMessageCodecs(vertx, config, false);

        Mockito.verify(eventBus, Mockito.never()).registerCodec(Mockito.any(MyMessageCodec.class));
        latch.countDown();
    }

    @Test
    public void testRegisterMessageCodecNotFoundAbortOnFailure() {
        config.put("messageCodecs", new JsonArray("[\"com.groupon.vertx.utils.MainVerticleTest$NonExistentCodec\"]"));

        final EventBus eventBus = Mockito.mock(EventBus.class);
        Mockito.doReturn(eventBus).when(vertx).eventBus();

        try {
            MainVerticle.registerMessageCodecs(vertx, config, true);
            fail("Expected exception not thrown");
        } catch (final MainVerticle.CodecRegistrationException e) {
            // Expected exception
        }

        Mockito.verify(eventBus, Mockito.never()).registerCodec(Mockito.any(MyMessageCodec.class));
        latch.countDown();
    }

    public static final class MyMessageCodec<T> implements MessageCodec<T, T> {

        @Override
        public void encodeToWire(final Buffer buffer, final T t) {
            throw new UnsupportedOperationException("This is not a functional message codec");
        }

        @Override
        public T decodeFromWire(final int pos, final Buffer buffer) {
            throw new UnsupportedOperationException("This is not a functional message codec");
        }

        @Override
        public T transform(final T t) {
            throw new UnsupportedOperationException("This is not a functional message codec");
        }

        @Override
        public String name() {
            return this.getClass().getSimpleName();
        }

        @Override
        public byte systemCodecID() {
            return -1;
        }
    }
}
