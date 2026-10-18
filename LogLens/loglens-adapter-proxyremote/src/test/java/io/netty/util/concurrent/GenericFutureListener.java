package io.netty.util.concurrent;
public interface GenericFutureListener<F> { void operationComplete(F future) throws Exception; }