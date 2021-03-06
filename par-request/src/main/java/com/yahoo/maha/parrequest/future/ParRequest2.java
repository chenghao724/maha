// Copyright 2017, Yahoo Holdings Inc.
// Licensed under the terms of the Apache License 2.0. Please see LICENSE file in project root for terms.
package com.yahoo.maha.parrequest.future;

import com.google.common.util.concurrent.ListenableFuture;
import com.yahoo.maha.parrequest.Either;
import com.yahoo.maha.parrequest.Option;
import com.yahoo.maha.parrequest.ParCallable;
import com.yahoo.maha.parrequest.GeneralError;

import scala.Tuple2;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * This class represents two parallel requests which can be composed synchronously with resultMap, or composed
 * asynchronously with map and fold
 */
public class ParRequest2<T, U> extends CombinableRequest<Tuple2<T, U>> {

    private final ParallelServiceExecutor executor;
    private final CombinedFuture2<T, U> combinedFuture2;

    ParRequest2(String label, ParallelServiceExecutor executor, ParCallable<Either<GeneralError, T>> firstRequest,
                ParCallable<Either<GeneralError, U>> secondRequest) {
        checkNotNull(executor, "Executor is null");
        checkNotNull(firstRequest, "First request is null");
        checkNotNull(secondRequest, "Second request is null");
        this.label = label;
        this.executor = executor;

        //fire requests
        final ListenableFuture<Either<GeneralError, T>> firstFuture = executor.submitParCallable(firstRequest);
        final ListenableFuture<Either<GeneralError, U>> secondFuture = executor.submitParCallable(secondRequest);

        try {
            combinedFuture2 = CombinedFuture2.from(executor, firstFuture, secondFuture);
        } catch (Exception e) {
            //failed to create combiner, cancel futures and re-throw exception
            firstFuture.cancel(false);
            secondFuture.cancel(false);
            throw e;
        }
    }

    ParRequest2(String label, ParallelServiceExecutor executor, CombinableRequest<T> firstRequest,
                CombinableRequest<U> secondRequest) {
        checkNotNull(executor, "Executor is null");
        checkNotNull(firstRequest, "First request is null");
        checkNotNull(secondRequest, "Second request is null");
        this.label = label;
        this.executor = executor;
        combinedFuture2 = CombinedFuture2.from(executor, firstRequest.asFuture(), secondRequest.asFuture());
    }

    public <O> Either<GeneralError, O> resultMap(ParFunction<Tuple2<T, U>, O> fn) {
        Either<GeneralError, Tuple2<T, U>> result = executor.getEitherSafely(label, combinedFuture2);
        return result.map(fn);
    }

    public <O> NoopRequest<O> fold(ParFunction<GeneralError, O> errFn, ParFunction<Tuple2<T, U>, O> fn) {
        return new NoopRequest<O>(executor, new FoldableFuture<>(executor, combinedFuture2, fn, errFn));
    }

    public <O> ParRequest<O> map(String label, ParFunction<Tuple2<T, U>, Either<GeneralError, O>> fn) {
        return new ParRequest<>(label, executor, new ComposableFuture<>(executor, combinedFuture2, fn));
    }

    public <O> ParRequest<O> flatMap(String label, ParFunction<Tuple2<T, U>, CombinableRequest<O>> fn) {
        return new ParRequest<>(label, executor, new ComposableFutureFuture<>(executor, combinedFuture2, fn));
    }

    public Either<GeneralError, Tuple2<T, U>> get() {
        return executor.getEitherSafely(label, combinedFuture2);
    }

    ListenableFuture<Either<GeneralError, Tuple2<T, U>>> asFuture() {
        return combinedFuture2;
    }

    public static class Builder<A, B> {

        private final ParallelServiceExecutor executor;
        private Option<ParCallable<Either<GeneralError, A>>> firstParCallable = Option.none();
        private Option<ParCallable<Either<GeneralError, B>>> secondParCallable = Option.none();
        private boolean built = false;
        private String label = "changethis";


        public Builder(ParallelServiceExecutor executor) {
            this.executor = executor;
        }

        public Builder<A, B> setLabel(String label) {
            this.label = label;
            return this;
        }

        public Builder<A, B> setFirstParCallable(ParCallable<Either<GeneralError, A>> parCallable) {
            checkState(firstParCallable.isEmpty(), "Cannot set the first parCallable twice!");
            firstParCallable = Option.apply(parCallable);
            return this;
        }

        public Builder<A, B> setSecondParCallable(ParCallable<Either<GeneralError, B>> parCallable) {
            checkState(secondParCallable.isEmpty(), "Cannot set the second parCallable twice!");
            secondParCallable = Option.apply(parCallable);
            return this;
        }

        public ParRequest2<A, B> build() {
            checkState(!built, "Cannot build a request twice!");
            checkState(firstParCallable.isDefined(), "First parCallable not defined!");
            checkState(secondParCallable.isDefined(), "Second parCallable not defined!");
            try {
                ParCallable<Either<GeneralError, A>> first = ParCallable.from(firstParCallable.get());
                ParCallable<Either<GeneralError, B>> second = ParCallable.from(secondParCallable.get());
                return new ParRequest2<>(label, executor, first, second);
            } finally {
                built = true;
            }
        }
    }
}
