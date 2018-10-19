/*
 * Copyright 2018 The MQTT Bee project
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
 *
 */

package org.mqttbee.rx;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.schedulers.Schedulers;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.ThrowingSupplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.time.Duration;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Silvio Giebl
 */
class FlowableWithSingleTest {

    @Test
    void split_single_extends_flow() {
        assertThrows(IllegalArgumentException.class, () -> FlowableWithSingle.split(
                Flowable.fromArray(new StringBuilder("single"), "next0", "next1", "next2"), CharSequence.class,
                StringBuilder.class));
    }

    @Test
    void split_flow_extends_single() {
        assertThrows(IllegalArgumentException.class, () -> FlowableWithSingle.split(
                Flowable.fromArray(new StringBuilder("single"), "next0", "next1", "next2"), String.class,
                CharSequence.class));
    }

    @Test
    void split_flow_equals_single() {
        assertThrows(IllegalArgumentException.class, () -> FlowableWithSingle.split(
                Flowable.fromArray(new StringBuilder("single"), "next0", "next1", "next2"), CharSequence.class,
                CharSequence.class));
    }

    @Test
    void doOnSingle() {
        final Flowable<? extends CharSequence> flowable =
                Flowable.fromArray(new StringBuilder("single"), "next0", "next1", "next2");
        final FlowableWithSingle<String, StringBuilder> flowableWithSingle =
                FlowableWithSingle.split(flowable, String.class, StringBuilder.class);

        final AtomicInteger count = new AtomicInteger();
        flowableWithSingle.doOnSingle(stringBuilder -> assertEquals("single", stringBuilder.toString()))
                .doOnNext(string -> assertEquals("next" + count.getAndIncrement(), string))
                .blockingSubscribe();
    }

    @Test
    void observeOnBoth() {
        final Flowable<? extends CharSequence> flowable =
                Flowable.fromArray(new StringBuilder("single"), "next0", "next1", "next2");
        final FlowableWithSingle<String, StringBuilder> flowableWithSingle =
                FlowableWithSingle.split(flowable, String.class, StringBuilder.class);

        final ExecutorService executorService =
                Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("test_thread").build());

        final AtomicInteger count = new AtomicInteger();
        flowableWithSingle.observeOnBoth(Schedulers.from(executorService)).doOnSingle(stringBuilder -> {
            assertEquals("single", stringBuilder.toString());
            assertEquals("test_thread", Thread.currentThread().getName());
        }).doOnNext(string -> {
            assertEquals("next" + count.getAndIncrement(), string);
            assertEquals("test_thread", Thread.currentThread().getName());
        }).blockingSubscribe();

        executorService.shutdown();
    }

    @Test
    void observeOnFlowable() {
        final Flowable<? extends CharSequence> flowable =
                Flowable.fromArray(new StringBuilder("single"), "next0", "next1", "next2");
        final FlowableWithSingle<String, StringBuilder> flowableWithSingle =
                FlowableWithSingle.split(flowable, String.class, StringBuilder.class);

        final ExecutorService executorService =
                Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("test_thread").build());

        final AtomicInteger count = new AtomicInteger();
        flowableWithSingle.doOnSingle(stringBuilder -> {
            assertEquals("single", stringBuilder.toString());
            assertNotEquals("test_thread", Thread.currentThread().getName());
        }).observeOn(Schedulers.from(executorService)).doOnNext(string -> {
            assertEquals("next" + count.getAndIncrement(), string);
            assertEquals("test_thread", Thread.currentThread().getName());
        }).blockingSubscribe();

        executorService.shutdown();
    }

    @Test
    void observeOnBoth_delayError() throws InterruptedException {
        final Flowable<? extends CharSequence> flowable = Flowable.fromIterable(() -> new Iterator<CharSequence>() {
            int count = -1;

            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public @NotNull CharSequence next() {
                count++;
                if (count == 4) {
                    throw new IllegalArgumentException("test");
                }
                return (count == 0) ? new StringBuilder("single") : "next" + (count - 1);
            }
        }).hide();
        final FlowableWithSingle<String, StringBuilder> flowableWithSingle =
                FlowableWithSingle.split(flowable, String.class, StringBuilder.class);

        final ExecutorService executorService =
                Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("test_thread").build());

        final AtomicInteger count = new AtomicInteger();
        flowableWithSingle.observeOnBoth(Schedulers.from(executorService), true)
                .doOnSingle(stringBuilder -> {
                    assertEquals("single", stringBuilder.toString());
                    assertEquals("test_thread", Thread.currentThread().getName());
                })
                .doOnNext(string -> {
                    assertEquals("next" + count.getAndIncrement(), string);
                    assertEquals("test_thread", Thread.currentThread().getName());
                })
                .test(1)
                .awaitCount(1)
                .assertValue("next0")
                .requestMore(1)
                .awaitCount(2)
                .assertValues("next0", "next1")
                .requestMore(1)
                .await()
                .assertError(IllegalArgumentException.class)
                .assertErrorMessage("test");

        executorService.shutdown();
    }

    @Test
    void observeOnBoth_delayError_bufferSize() throws InterruptedException {
        final Flowable<? extends CharSequence> flowable = Flowable.fromIterable(() -> new Iterator<CharSequence>() {
            int count = -1;

            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public @NotNull CharSequence next() {
                count++;
                if (count == 1024) {
                    throw new IllegalArgumentException("test");
                }
                return (count == 0) ? new StringBuilder("single") : "next" + (count - 1);
            }
        }).hide();
        final FlowableWithSingle<String, StringBuilder> flowableWithSingle =
                FlowableWithSingle.split(flowable, String.class, StringBuilder.class);

        final ExecutorService executorService =
                Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("test_thread").build());

        final AtomicInteger count = new AtomicInteger();
        flowableWithSingle.observeOnBoth(Schedulers.from(executorService), true, 1024)
                .doOnSingle(stringBuilder -> {
                    assertEquals("single", stringBuilder.toString());
                    assertEquals("test_thread", Thread.currentThread().getName());
                })
                .doOnNext(string -> {
                    assertEquals("next" + count.getAndIncrement(), string);
                    assertEquals("test_thread", Thread.currentThread().getName());
                })
                .test(1)
                .awaitCount(1)
                .assertValue("next0")
                .requestMore(1022)
                .awaitCount(1023)
                .requestMore(1)
                .await()
                .assertError(IllegalArgumentException.class)
                .assertErrorMessage("test");

        executorService.shutdown();
    }

    @Test
    void mapSingle() {
        final Flowable<? extends CharSequence> flowable =
                Flowable.fromArray(new StringBuilder("single"), "next0", "next1", "next2");
        final FlowableWithSingle<String, StringBuilder> flowableWithSingle =
                FlowableWithSingle.split(flowable, String.class, StringBuilder.class);

        final AtomicInteger count = new AtomicInteger();
        flowableWithSingle.mapSingle(stringBuilder -> (double) stringBuilder.toString().length())
                .doOnSingle(aDouble -> assertEquals((Double) 6d, aDouble))
                .doOnNext(string -> assertEquals("next" + count.getAndIncrement(), string))
                .blockingSubscribe();
    }

    @Test
    void mapBoth() {
        final Flowable<? extends CharSequence> flowable =
                Flowable.fromArray(new StringBuilder("single"), "next0", "next1", "next2");
        final FlowableWithSingle<String, StringBuilder> flowableWithSingle =
                FlowableWithSingle.split(flowable, String.class, StringBuilder.class);

        flowableWithSingle.mapBoth(String::length, stringBuilder -> (double) stringBuilder.toString().length())
                .doOnSingle(aDouble -> assertEquals((Double) 6d, aDouble))
                .doOnNext(integer -> assertEquals((Integer) 5, integer))
                .blockingSubscribe();
    }

    @Test
    void mapError() {
        final Flowable<? extends CharSequence> flowable = Flowable.error(new IllegalArgumentException("test"));
        final FlowableWithSingle<String, StringBuilder> flowableWithSingle =
                FlowableWithSingle.split(flowable, String.class, StringBuilder.class);

        final IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> flowableWithSingle.mapError(throwable -> new IllegalStateException(throwable.getMessage()))
                        .blockingSubscribe());
        assertEquals("test", exception.getMessage());
    }

    @Test
    void doOnSingle_multiple() {
        final Flowable<? extends CharSequence> flowable =
                Flowable.fromArray(new StringBuilder("single"), "next0", "next1", "next2");
        final FlowableWithSingle<String, StringBuilder> flowableWithSingle =
                FlowableWithSingle.split(flowable, String.class, StringBuilder.class);

        final ExecutorService executorService =
                Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("test_thread").build());

        final AtomicInteger counter = new AtomicInteger();
        flowableWithSingle //
                .doOnSingle(stringBuilder -> {
                    assertEquals(1, counter.incrementAndGet());
                    assertNotEquals("test_thread", Thread.currentThread().getName());
                })
                .doOnSingle(stringBuilder -> {
                    assertEquals(2, counter.incrementAndGet());
                    assertNotEquals("test_thread", Thread.currentThread().getName());
                })
                .observeOnBoth(Schedulers.from(executorService))
                .doOnSingle(stringBuilder -> {
                    assertEquals(3, counter.incrementAndGet());
                    assertEquals("test_thread", Thread.currentThread().getName());
                })
                .mapBoth(String::length, stringBuilder -> (double) stringBuilder.toString().length())
                .doOnSingle(stringBuilder -> {
                    assertEquals(4, counter.incrementAndGet());
                    assertEquals("test_thread", Thread.currentThread().getName());
                })
                .blockingSubscribe();

        assertEquals(4, counter.get());

        executorService.shutdown();
    }

    @Test
    void subscribeBoth() throws InterruptedException {
        final Flowable<? extends CharSequence> flowable =
                Flowable.fromArray(new StringBuilder("single"), "next0", "next1", "next2");
        final FlowableWithSingle<String, StringBuilder> flowableWithSingle =
                FlowableWithSingle.split(flowable, String.class, StringBuilder.class);

        final CountDownLatch latch = new CountDownLatch(6);
        flowableWithSingle.subscribeBoth(new FlowableWithSingleSubscriber<String, StringBuilder>() {
            @Override
            public void onSubscribe(final @NotNull Subscription s) {
                assertEquals(6, latch.getCount());
                latch.countDown();
                s.request(10);
            }

            @Override
            public void onSingle(final @NotNull StringBuilder stringBuilder) {
                assertEquals("single", stringBuilder.toString());
                assertEquals(5, latch.getCount());
                latch.countDown();
            }

            @Override
            public void onNext(final @NotNull String s) {
                latch.countDown();
            }

            @Override
            public void onComplete() {
                latch.countDown();
            }

            @Override
            public void onError(final @NotNull Throwable t) {
            }
        });
        assertTrue(latch.await(100, TimeUnit.MILLISECONDS));
    }

    @ValueSource(ints = {0, 1, 2, 3, 4})
    @ParameterizedTest
    void subscribeSingleFuture_immediate(final int args) throws ExecutionException, InterruptedException {
        final Flowable<? extends CharSequence> flowable =
                Flowable.fromArray(new StringBuilder("single"), "next0", "next1", "next2");
        final FlowableWithSingle<String, StringBuilder> flowableWithSingle =
                FlowableWithSingle.split(flowable, String.class, StringBuilder.class);

        final AtomicInteger onNextCounter = new AtomicInteger();
        final AtomicInteger onErrorCounter = new AtomicInteger();
        final AtomicInteger onCompleteCounter = new AtomicInteger();
        final CompletableFuture<StringBuilder> future =
                subscribeSingleFuture(args, flowableWithSingle, onNextCounter, onErrorCounter, onCompleteCounter);
        assertTrue(future.isDone());
        assertFalse(future.isCancelled());
        assertFalse(future.isCompletedExceptionally());
        assertEquals("single", future.get().toString());
        switch (args) {
            case 4:
                // fallthrough
            case 3:
                assertEquals(1, onCompleteCounter.get());
                // fallthrough
            case 2:
                assertEquals(0, onErrorCounter.get());
                // fallthrough
            case 1:
                assertEquals(3, onNextCounter.get());
        }
    }

    @ValueSource(ints = {0, 1, 2, 3, 4})
    @ParameterizedTest
    void subscribeSingleFuture_immediate_single_in_between(final int args)
            throws ExecutionException, InterruptedException {

        final Flowable<? extends CharSequence> flowable =
                Flowable.fromArray("next0", "next1", new StringBuilder("single"), "next2");
        final FlowableWithSingle<String, StringBuilder> flowableWithSingle =
                FlowableWithSingle.split(flowable, String.class, StringBuilder.class);

        final AtomicInteger onNextCounter = new AtomicInteger();
        final AtomicInteger onErrorCounter = new AtomicInteger();
        final AtomicInteger onCompleteCounter = new AtomicInteger();
        final CompletableFuture<StringBuilder> future =
                subscribeSingleFuture(args, flowableWithSingle, onNextCounter, onErrorCounter, onCompleteCounter);
        assertTrue(future.isDone());
        assertFalse(future.isCancelled());
        assertFalse(future.isCompletedExceptionally());
        assertEquals("single", future.get().toString());
        switch (args) {
            case 4:
                // fallthrough
            case 3:
                assertEquals(1, onCompleteCounter.get());
                // fallthrough
            case 2:
                assertEquals(0, onErrorCounter.get());
                // fallthrough
            case 1:
                assertEquals(3, onNextCounter.get());
        }
    }

    @ValueSource(ints = {0, 1, 2, 3, 4})
    @ParameterizedTest
    void subscribeSingleFuture_immediate_no_single(final int args) {
        final Flowable<? extends CharSequence> flowable = Flowable.fromArray("next0", "next1", "next2");
        final FlowableWithSingle<String, StringBuilder> flowableWithSingle =
                FlowableWithSingle.split(flowable, String.class, StringBuilder.class);

        final AtomicInteger onNextCounter = new AtomicInteger();
        final AtomicInteger onErrorCounter = new AtomicInteger();
        final AtomicInteger onCompleteCounter = new AtomicInteger();
        final CompletableFuture<StringBuilder> future =
                subscribeSingleFuture(args, flowableWithSingle, onNextCounter, onErrorCounter, onCompleteCounter);
        assertTrue(future.isDone());
        assertFalse(future.isCancelled());
        assertTrue(future.isCompletedExceptionally());
        final ExecutionException executionException = assertThrows(ExecutionException.class, future::get);
        assertTrue(executionException.getCause() instanceof NoSuchElementException);
        switch (args) {
            case 4:
                // fallthrough
            case 3:
                assertEquals(1, onCompleteCounter.get());
                // fallthrough
            case 2:
                assertEquals(0, onErrorCounter.get());
                // fallthrough
            case 1:
                assertEquals(3, onNextCounter.get());
        }
    }

    @ValueSource(ints = {0, 1, 2, 3, 4})
    @ParameterizedTest
    void subscribeSingleFuture_immediate_error(final int args) {
        final Flowable<? extends CharSequence> flowable = Flowable.error(new Exception("test"));
        final FlowableWithSingle<String, StringBuilder> flowableWithSingle =
                FlowableWithSingle.split(flowable, String.class, StringBuilder.class);

        final AtomicInteger onNextCounter = new AtomicInteger();
        final AtomicInteger onErrorCounter = new AtomicInteger();
        final AtomicInteger onCompleteCounter = new AtomicInteger();
        final CompletableFuture<StringBuilder> future =
                subscribeSingleFuture(args, flowableWithSingle, onNextCounter, onErrorCounter, onCompleteCounter);
        assertTrue(future.isDone());
        assertFalse(future.isCancelled());
        assertTrue(future.isCompletedExceptionally());
        final ExecutionException executionException = assertThrows(ExecutionException.class, future::get);
        assertEquals("test", executionException.getCause().getMessage());
        switch (args) {
            case 4:
                // fallthrough
            case 3:
                assertEquals(0, onCompleteCounter.get());
                // fallthrough
            case 2:
                assertEquals(1, onErrorCounter.get());
                // fallthrough
            case 1:
                assertEquals(0, onNextCounter.get());
        }
    }

    @ValueSource(ints = {0, 1, 2, 3, 4})
    @ParameterizedTest
    void subscribeSingleFuture(final int args) throws InterruptedException {
        final Flowable<? extends CharSequence> flowable =
                Flowable.fromArray(new StringBuilder("single"), "next0", "next1", "next2")
                        .zipWith(Flowable.interval(10, 10, TimeUnit.MILLISECONDS), (o, aLong) -> o);
        final FlowableWithSingle<String, StringBuilder> flowableWithSingle =
                FlowableWithSingle.split(flowable, String.class, StringBuilder.class);

        final AtomicInteger onNextCounter = new AtomicInteger();
        final AtomicInteger onErrorCounter = new AtomicInteger();
        final AtomicInteger onCompleteCounter = new AtomicInteger();
        final CountDownLatch latch = new CountDownLatch(3 + ((args >= 3) ? 1 : 0));
        final CompletableFuture<StringBuilder> future =
                subscribeSingleFuture(args, flowableWithSingle, onNextCounter, onErrorCounter, onCompleteCounter,
                        latch);
        assertTimeout(Duration.ofMillis(100), () -> assertEquals("single", future.get().toString()));
        if (args == 0) {
            future.cancel(false);
        } else {
            assertTrue(latch.await(100, TimeUnit.MILLISECONDS));
            switch (args) {
                case 4:
                    // fallthrough
                case 3:
                    assertEquals(1, onCompleteCounter.get());
                    // fallthrough
                case 2:
                    assertEquals(0, onErrorCounter.get());
                    // fallthrough
                case 1:
                    assertEquals(3, onNextCounter.get());
            }
        }
    }

    @ValueSource(ints = {0, 1, 2, 3, 4})
    @ParameterizedTest
    void subscribeSingleFuture_single_in_between(final int args) throws InterruptedException {
        final Flowable<? extends CharSequence> flowable =
                Flowable.fromArray("next0", "next1", new StringBuilder("single"), "next2")
                        .zipWith(Flowable.interval(10, 10, TimeUnit.MILLISECONDS), (o, aLong) -> o);
        final FlowableWithSingle<String, StringBuilder> flowableWithSingle =
                FlowableWithSingle.split(flowable, String.class, StringBuilder.class);

        final AtomicInteger onNextCounter = new AtomicInteger();
        final AtomicInteger onErrorCounter = new AtomicInteger();
        final AtomicInteger onCompleteCounter = new AtomicInteger();
        final CountDownLatch latch = new CountDownLatch(3 + ((args >= 3) ? 1 : 0));
        final CompletableFuture<StringBuilder> future =
                subscribeSingleFuture(args, flowableWithSingle, onNextCounter, onErrorCounter, onCompleteCounter,
                        latch);
        assertTimeout(Duration.ofMillis(100), () -> assertEquals("single", future.get().toString()));
        if (args == 0) {
            future.cancel(false);
        } else {
            assertTrue(latch.await(100, TimeUnit.MILLISECONDS));
            switch (args) {
                case 4:
                    // fallthrough
                case 3:
                    assertEquals(1, onCompleteCounter.get());
                    // fallthrough
                case 2:
                    assertEquals(0, onErrorCounter.get());
                    // fallthrough
                case 1:
                    assertEquals(3, onNextCounter.get());
            }
        }
    }

    @ValueSource(ints = {0, 1, 2, 3, 4})
    @ParameterizedTest
    void subscribeSingleFuture_no_single(final int args) throws InterruptedException {
        final Flowable<? extends CharSequence> flowable = Flowable.fromArray("next0", "next1", "next2")
                .zipWith(Flowable.interval(10, 10, TimeUnit.MILLISECONDS), (o, aLong) -> o);
        final FlowableWithSingle<String, StringBuilder> flowableWithSingle =
                FlowableWithSingle.split(flowable, String.class, StringBuilder.class);

        final AtomicInteger onNextCounter = new AtomicInteger();
        final AtomicInteger onErrorCounter = new AtomicInteger();
        final AtomicInteger onCompleteCounter = new AtomicInteger();
        final CountDownLatch latch = new CountDownLatch(3 + ((args >= 3) ? 1 : 0));
        final CompletableFuture<StringBuilder> future =
                subscribeSingleFuture(args, flowableWithSingle, onNextCounter, onErrorCounter, onCompleteCounter,
                        latch);
        final ExecutionException executionException = assertThrows(ExecutionException.class,
                () -> assertTimeout(Duration.ofMillis(100), (ThrowingSupplier<StringBuilder>) future::get));
        assertTrue(executionException.getCause() instanceof NoSuchElementException);
        if (args == 0) {
            future.cancel(false);
        } else {
            assertTrue(latch.await(100, TimeUnit.MILLISECONDS));
            switch (args) {
                case 4:
                    // fallthrough
                case 3:
                    assertEquals(1, onCompleteCounter.get());
                    // fallthrough
                case 2:
                    assertEquals(0, onErrorCounter.get());
                    // fallthrough
                case 1:
                    assertEquals(3, onNextCounter.get());
            }
        }
    }

    @ValueSource(ints = {0, 1, 2, 3, 4})
    @ParameterizedTest
    void subscribeSingleFuture_error(final int args) throws InterruptedException {
        final Flowable<? extends CharSequence> flowable =
                Flowable.<CharSequence>error(new Exception("test")).delay(10, TimeUnit.MILLISECONDS);
        final FlowableWithSingle<String, StringBuilder> flowableWithSingle =
                FlowableWithSingle.split(flowable, String.class, StringBuilder.class);

        final AtomicInteger onNextCounter = new AtomicInteger();
        final AtomicInteger onErrorCounter = new AtomicInteger();
        final AtomicInteger onCompleteCounter = new AtomicInteger();
        final CountDownLatch latch = new CountDownLatch((args >= 2) ? 1 : 0);
        final CompletableFuture<StringBuilder> future =
                subscribeSingleFuture(args, flowableWithSingle, onNextCounter, onErrorCounter, onCompleteCounter,
                        latch);
        final ExecutionException executionException = assertThrows(ExecutionException.class,
                () -> assertTimeout(Duration.ofMillis(100), (ThrowingSupplier<StringBuilder>) future::get));
        assertEquals("test", executionException.getCause().getMessage());
        if (args == 0) {
            future.cancel(false);
        } else {
            assertTrue(latch.await(100, TimeUnit.MILLISECONDS));
            switch (args) {
                case 4:
                    // fallthrough
                case 3:
                    assertEquals(0, onCompleteCounter.get());
                    // fallthrough
                case 2:
                    assertEquals(1, onErrorCounter.get());
                    // fallthrough
                case 1:
                    assertEquals(0, onNextCounter.get());
            }
        }
    }

    @Test
    void subscribeSingleFuture_cancel() throws InterruptedException {
        final CountDownLatch subscribeLatch = new CountDownLatch(1);
        final CountDownLatch cancelLatch = new CountDownLatch(1);
        final CountDownLatch completeLatch = new CountDownLatch(1);
        final Flowable<? extends CharSequence> flowable = Flowable.<CharSequence>create(emitter -> {
            subscribeLatch.countDown();
            try {
                assertTrue(cancelLatch.await(100, TimeUnit.MILLISECONDS));
            } catch (final InterruptedException e) {
                // ignore
            }
            assertTrue(emitter.isCancelled());
            completeLatch.countDown();
        }, BackpressureStrategy.MISSING).subscribeOn(Schedulers.single());

        final FlowableWithSingle<String, StringBuilder> flowableWithSingle =
                FlowableWithSingle.split(flowable, String.class, StringBuilder.class);

        final CompletableFuture<StringBuilder> future = flowableWithSingle.subscribeSingleFuture();
        assertTrue(subscribeLatch.await(100, TimeUnit.MILLISECONDS));
        future.cancel(false);
        cancelLatch.countDown();
        assertTrue(completeLatch.await(100, TimeUnit.MILLISECONDS));
        assertTrue(future.isDone());
        assertTrue(future.isCancelled());
        assertTrue(future.isCompletedExceptionally());
    }

    @Test
    void subscribeSingleFuture_cancel_after_single() throws InterruptedException {
        final CountDownLatch cancelLatch = new CountDownLatch(1);
        final CountDownLatch completeLatch = new CountDownLatch(1);
        final Flowable<? extends CharSequence> flowable = Flowable.<CharSequence>create(emitter -> {
            emitter.onNext(new StringBuilder("single"));
            try {
                assertTrue(cancelLatch.await(100, TimeUnit.MILLISECONDS));
            } catch (final InterruptedException e) {
                // ignore
            }
            assertTrue(emitter.isCancelled());
            completeLatch.countDown();
        }, BackpressureStrategy.MISSING).subscribeOn(Schedulers.single());

        final FlowableWithSingle<String, StringBuilder> flowableWithSingle =
                FlowableWithSingle.split(flowable, String.class, StringBuilder.class);

        final CompletableFuture<StringBuilder> future = flowableWithSingle.subscribeSingleFuture();
        assertTimeout(Duration.ofMillis(100), () -> assertEquals("single", future.get().toString()));
        future.cancel(false);
        cancelLatch.countDown();
        assertTrue(completeLatch.await(100, TimeUnit.MILLISECONDS));
        assertTrue(future.isDone());
        assertFalse(future.isCancelled());
        assertFalse(future.isCompletedExceptionally());
    }

    @Test
    void subscribeSingleFuture_cancel_after_next() throws InterruptedException {
        final CountDownLatch cancelLatch = new CountDownLatch(1);
        final CountDownLatch completeLatch = new CountDownLatch(1);
        final Flowable<? extends CharSequence> flowable = Flowable.<CharSequence>create(emitter -> {
            emitter.onNext(new StringBuilder("single"));
            emitter.onNext("next0");
            try {
                assertTrue(cancelLatch.await(100, TimeUnit.MILLISECONDS));
            } catch (final InterruptedException e) {
                // ignore
            }
            assertTrue(emitter.isCancelled());
            completeLatch.countDown();
        }, BackpressureStrategy.MISSING).subscribeOn(Schedulers.single());

        final FlowableWithSingle<String, StringBuilder> flowableWithSingle =
                FlowableWithSingle.split(flowable, String.class, StringBuilder.class);

        final CountDownLatch onNextLatch = new CountDownLatch(1);
        final CompletableFuture<StringBuilder> future =
                flowableWithSingle.subscribeSingleFuture(s -> onNextLatch.countDown());
        assertTimeout(Duration.ofMillis(100), () -> assertEquals("single", future.get().toString()));
        assertTrue(onNextLatch.await(100, TimeUnit.MILLISECONDS));
        future.cancel(false);
        cancelLatch.countDown();
        assertTrue(completeLatch.await(100, TimeUnit.MILLISECONDS));
        assertTrue(future.isDone());
        assertFalse(future.isCancelled());
        assertFalse(future.isCompletedExceptionally());
    }

    @Test
    void subscribeSingleFuture_cancel_before_onSubscribe() throws InterruptedException {
        final CountDownLatch cancelLatch = new CountDownLatch(1);
        final CountDownLatch completeLatch = new CountDownLatch(1);
        final Flowable<? extends CharSequence> flowable = new Flowable<CharSequence>() {
            @Override
            protected void subscribeActual(final @NotNull Subscriber<? super CharSequence> s) {
                final Thread thread = new Thread(() -> {
                    try {
                        assertTrue(cancelLatch.await(100, TimeUnit.MILLISECONDS));
                    } catch (final InterruptedException e) {
                        fail(e);
                    }
                    final TestSubscription subscription = new TestSubscription();
                    s.onSubscribe(subscription);
                    assertTrue(subscription.cancelled);
                    completeLatch.countDown();
                });
                thread.setUncaughtExceptionHandler((t, e) -> e.printStackTrace());
                thread.start();
            }
        };

        final FlowableWithSingle<String, StringBuilder> flowableWithSingle =
                FlowableWithSingle.split(flowable, String.class, StringBuilder.class);

        final CompletableFuture<StringBuilder> future = flowableWithSingle.subscribeSingleFuture();
        future.cancel(false);
        cancelLatch.countDown();
        assertTrue(completeLatch.await(100, TimeUnit.MILLISECONDS));
        assertTrue(future.isDone());
        assertTrue(future.isCancelled());
        assertTrue(future.isCompletedExceptionally());
    }

    @Test
    void subscribeSingleFuture_cancel_subscription() throws InterruptedException {
        final CountDownLatch cancelLatch = new CountDownLatch(1);
        final Flowable<? extends CharSequence> flowable = new Flowable<CharSequence>() {
            @Override
            protected void subscribeActual(final @NotNull Subscriber<? super CharSequence> s) {
                final Thread thread = new Thread(() -> {
                    final TestSubscription subscription = new TestSubscription();
                    s.onSubscribe(subscription);
                    assertTrue(subscription.cancelled);
                    cancelLatch.countDown();
                });
                thread.setUncaughtExceptionHandler((t, e) -> e.printStackTrace());
                thread.start();
            }
        };

        final FlowableWithSingle<String, StringBuilder> flowableWithSingle =
                FlowableWithSingle.split(flowable, String.class, StringBuilder.class);

        final CompletableFuture<StringBuilder> future =
                flowableWithSingle.subscribeSingleFuture(new Subscriber<String>() {
                    @Override
                    public void onSubscribe(final @NotNull Subscription s) {
                        s.cancel();
                    }

                    @Override
                    public void onNext(final @NotNull String s) {}

                    @Override
                    public void onComplete() {}

                    @Override
                    public void onError(final @NotNull Throwable t) {}
                });
        assertTrue(cancelLatch.await(100, TimeUnit.MILLISECONDS));
        assertTrue(future.isDone());
        assertTrue(future.isCancelled());
        assertTrue(future.isCompletedExceptionally());
        assertThrows(CancellationException.class, future::get);
    }

    private @NotNull CompletableFuture<StringBuilder> subscribeSingleFuture(
            final int args, final @NotNull FlowableWithSingle<String, StringBuilder> flowableWithSingle,
            final @NotNull AtomicInteger onNextCounter, final @NotNull AtomicInteger onErrorCounter,
            final @NotNull AtomicInteger onCompleteCounter, final @NotNull CountDownLatch latch) {

        switch (args) {
            case 0:
                return flowableWithSingle.subscribeSingleFuture();
            case 1:
                return flowableWithSingle.subscribeSingleFuture(s -> {
                    assertEquals("next" + onNextCounter.get(), s);
                    onNextCounter.incrementAndGet();
                    latch.countDown();
                });
            case 2:
                return flowableWithSingle.subscribeSingleFuture(s -> {
                    assertEquals("next" + onNextCounter.get(), s);
                    onNextCounter.incrementAndGet();
                    latch.countDown();
                }, throwable -> {
                    onErrorCounter.incrementAndGet();
                    latch.countDown();
                });
            case 3:
                return flowableWithSingle.subscribeSingleFuture(s -> {
                    assertEquals("next" + onNextCounter.get(), s);
                    onNextCounter.incrementAndGet();
                    latch.countDown();
                }, throwable -> {
                    onErrorCounter.incrementAndGet();
                    latch.countDown();
                }, () -> {
                    onCompleteCounter.incrementAndGet();
                    latch.countDown();
                });
            default:
                return flowableWithSingle.subscribeSingleFuture(new Subscriber<String>() {
                    @Override
                    public void onSubscribe(final @NotNull Subscription s) {
                        s.request(10);
                    }

                    @Override
                    public void onNext(final @NotNull String s) {
                        assertEquals("next" + onNextCounter.get(), s);
                        onNextCounter.incrementAndGet();
                        latch.countDown();
                    }

                    @Override
                    public void onComplete() {
                        onCompleteCounter.incrementAndGet();
                        latch.countDown();
                    }

                    @Override
                    public void onError(final @NotNull Throwable t) {
                        onErrorCounter.incrementAndGet();
                        latch.countDown();
                    }
                });
        }
    }

    private @NotNull CompletableFuture<StringBuilder> subscribeSingleFuture(
            final int args, final @NotNull FlowableWithSingle<String, StringBuilder> flowableWithSingle,
            final @NotNull AtomicInteger onNextCounter, final @NotNull AtomicInteger onErrorCounter,
            final @NotNull AtomicInteger onCompleteCounter) {

        return subscribeSingleFuture(
                args, flowableWithSingle, onNextCounter, onErrorCounter, onCompleteCounter, new CountDownLatch(0));
    }

    @Test
    void conditional_fusion() {
        final Flowable<? extends CharSequence> flowable =
                Flowable.fromArray(new StringBuilder("single"), "next0", "next1", "next2");
        final FlowableWithSingle<String, StringBuilder> flowableWithSingle =
                FlowableWithSingle.split(flowable, String.class, StringBuilder.class);

        final AtomicInteger count = new AtomicInteger(1);
        flowableWithSingle.doOnSingle(stringBuilder -> assertEquals("single", stringBuilder.toString()))
                .filter(string -> !string.equals("next0"))
                .doOnNext(string -> assertEquals("next" + count.getAndIncrement(), string))
                .blockingSubscribe();
    }

    @Test
    void no_conditional_fusion_source() {
        final Flowable<? extends CharSequence> flowable =
                Flowable.fromArray(new StringBuilder("single"), "next0", "next1", "next2").hide();
        final FlowableWithSingle<String, StringBuilder> flowableWithSingle =
                FlowableWithSingle.split(flowable, String.class, StringBuilder.class);

        final AtomicInteger count = new AtomicInteger(0);
        flowableWithSingle.doOnSingle(stringBuilder -> assertEquals("single", stringBuilder.toString()))
                .doOnNext(string -> assertEquals("next" + count.getAndIncrement(), string))
                .blockingSubscribe();
    }

    private static class TestSubscription implements Subscription {

        volatile boolean cancelled;
        final @NotNull AtomicLong requested = new AtomicLong();

        @Override
        public void request(final long n) {
            requested.addAndGet(n);
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

}