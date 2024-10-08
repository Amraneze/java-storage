/*
 * Copyright 2024 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.cloud.storage.transfermanager;

import com.google.api.core.ApiFuture;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.BlobWriteSession;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.Storage.BlobWriteOption;
import com.google.cloud.storage.StorageException;
import com.google.common.io.ByteStreams;

import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class ParallelCompositeUploadCallable<T> implements Callable<UploadResult> {
  private final Storage storage;

  private final BlobInfo originalBlob;

  private final T source;

  private final ParallelUploadConfig parallelUploadConfig;

  private final Storage.BlobWriteOption[] opts;

  public ParallelCompositeUploadCallable(
      Storage storage,
      BlobInfo originalBlob,
      T source,
      ParallelUploadConfig parallelUploadConfig,
      BlobWriteOption[] opts) {
    this.storage = storage;
    this.originalBlob = originalBlob;
    this.source = source;
    this.parallelUploadConfig = parallelUploadConfig;
    this.opts = opts;
  }

  public UploadResult call() {
    return uploadPCU();
  }

  private UploadResult uploadPCU() {
    BlobWriteSession session = storage.blobWriteSession(originalBlob, opts);
    try (WritableByteChannel writableByteChannel = session.open()) {
      if (source instanceof Path) {
        try (FileChannel fc = FileChannel.open((Path) source, StandardOpenOption.READ)) {
          ByteStreams.copy(fc, writableByteChannel);
        }
      } else if (source instanceof InputStream) {
        InputStream inputStream = (InputStream) source;
        ByteStreams.copy(inputStream, Channels.newOutputStream(writableByteChannel));
      } else {
        throw new IllegalArgumentException(String.format("Unsupported source type %s", source.getClass().getName()));
      }
    } catch (StorageException e) {
      if (parallelUploadConfig.isSkipIfExists() && e.getCode() == 412) {
        return UploadResult.newBuilder(originalBlob, TransferStatus.SKIPPED)
            .setException(e)
            .build();
      } else {
        return UploadResult.newBuilder(originalBlob, TransferStatus.FAILED_TO_FINISH)
            .setException(e)
            .build();
      }
    } catch (Exception e) {
      return UploadResult.newBuilder(originalBlob, TransferStatus.FAILED_TO_FINISH)
          .setException(e)
          .build();
    }
    try {
      ApiFuture<BlobInfo> result = session.getResult();
      BlobInfo newBlob = result.get(10, TimeUnit.SECONDS);
      return UploadResult.newBuilder(originalBlob, TransferStatus.SUCCESS)
          .setUploadedBlob(newBlob)
          .build();
    } catch (InterruptedException | ExecutionException | TimeoutException | IllegalArgumentException e) {
      return UploadResult.newBuilder(originalBlob, TransferStatus.FAILED_TO_FINISH)
          .setException(e)
          .build();
    }
  }
}
