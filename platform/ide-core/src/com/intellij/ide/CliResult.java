// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide;

import com.intellij.openapi.util.NlsContexts;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

// used by SocketLock - do not use any platform classes or heavy JDK classes
public final class CliResult {
  public static final CliResult OK = new CliResult(0, null);

  public final int exitCode;
  public final @Nullable @NlsContexts.DialogMessage String message;

  public CliResult(int exitCode, @Nullable @NlsContexts.DialogMessage String message) {
    this.exitCode = exitCode;
    this.message = message;
  }

  @Override
  public String toString() {
    return message != null ? exitCode + ": " + message : String.valueOf(exitCode);
  }

  public static @NotNull CompletableFuture<CliResult> error(int exitCode, @Nullable @NlsContexts.DialogMessage String message) {
    return CompletableFuture.completedFuture(new CliResult(exitCode, message));
  }

  public static @NotNull CliResult unmap(@NotNull Future<CliResult> future, int errorCode) {
    try {
      return future.get();
    }
    catch (InterruptedException | ExecutionException e) {
      return new CliResult(errorCode, e.getMessage());
    }
  }
}
