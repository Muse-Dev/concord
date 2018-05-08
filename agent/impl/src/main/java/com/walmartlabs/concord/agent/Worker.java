package com.walmartlabs.concord.agent;

/*-
 * *****
 * Concord
 * -----
 * Copyright (C) 2017 Wal-Mart Store, Inc.
 * -----
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
 * =====
 */

import com.walmartlabs.concord.common.IOUtils;
import com.walmartlabs.concord.project.InternalConstants;
import com.walmartlabs.concord.rpc.JobEntry;
import com.walmartlabs.concord.server.ApiException;
import com.walmartlabs.concord.server.ApiResponse;
import com.walmartlabs.concord.server.api.process.ProcessStatus;
import com.walmartlabs.concord.server.client.ClientUtils;
import com.walmartlabs.concord.server.client.ProcessQueueApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class Worker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(Worker.class);

    private static final long ERROR_DELAY = 5000;

    private final ProcessQueueApi queueClient;
    private final ProcessApiClient processApiClient;
    private final ExecutionManager executionManager;
    private final long logSteamMaxDelay;
    private final long pollInterval;

    private volatile boolean maintenanceMode = false;

    public Worker(ProcessQueueApi queueClient, ProcessApiClient processApiClient,
                  ExecutionManager executionManager,
                  long logSteamMaxDelay, long pollInterval) {
        this.queueClient = queueClient;
        this.processApiClient = processApiClient;
        this.executionManager = executionManager;
        this.logSteamMaxDelay = logSteamMaxDelay;
        this.pollInterval = pollInterval;
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted() && !maintenanceMode) {
            JobEntry job = null;
            try {
                job = take();

                if (job != null) {
                    execute(job.getInstanceId(), job.getPayload());
                } else {
                    sleep(pollInterval);
                }
            } catch (Exception e) {
                log.error("run -> job process error: {}", e.getMessage(), e);
                sleep(ERROR_DELAY);
            } finally {
                if (job != null) {
                    cleanup(job);
                }
            }
        }
    }

    public void setMaintenanceMode() {
        maintenanceMode = true;
    }

    private JobEntry take() throws ApiException {
        ApiResponse<File> resp = queueClient.takeWithHttpInfo();
        if (resp.getData() == null) {
            return null;
        }
        String instanceId = ClientUtils.getHeader(InternalConstants.Headers.PROCESS_INSTANCE_ID, resp);
        return new JobEntry(UUID.fromString(instanceId), resp.getData().toPath());
    }

    private void cleanup(JobEntry job) {
        try {
            Files.deleteIfExists(job.getPayload());
        } catch (IOException e) {
            log.warn("cleanup ['{}'] -> error while removing the payload {}: {}",
                    job.getInstanceId(), job.getPayload(), e.getMessage());
        }
    }

    private void cleanup(JobInstance i) {
        Path p = i.workDir();
        if (p == null || Files.notExists(p)) {
            return;
        }

        try {
            IOUtils.deleteRecursively(p);
        } catch (IOException e) {
            log(i.instanceId(), "Unable to delete the working directory: " + e.getMessage());
        }
    }

    private void log(UUID instanceId, String s) {
        try {
            processApiClient.appendLog(instanceId, s.getBytes());
        } catch (ApiException e) {
            log.warn("log ['{}'] -> unable to append a log entry ({}): {}", instanceId, e.getMessage(), s);
        }
    }

    private void execute(UUID instanceId, Path payload) {
        log.info("execute ['{}', '{}'] -> starting", instanceId, payload);

        JobInstance i;
        try {
            i = executionManager.start(instanceId,"n/a", payload);
        } catch (ExecutionException e) {
            log.error("execute ['{}', '{}'] -> start error", instanceId, payload, e);
            return;
        }

        Supplier<Boolean> isRunning = () -> executionManager.getStatus(instanceId) == ProcessStatus.RUNNING;

        // check the status to avoid races
        if (isRunning.get()) {
            try {
                processApiClient.updateStatus(instanceId, ProcessStatus.RUNNING);
            } catch (ApiException e) {
                log.error("execute ['{}', '{}'] -> status update error", instanceId, payload, e);
            }
        }

        Consumer<Chunk> sink = chunk -> {
            byte[] ab = new byte[chunk.len];
            System.arraycopy(chunk.ab, 0, ab, 0, chunk.len);

            try {
                processApiClient.appendLog(instanceId, ab);
            } catch (ApiException e) {
                handleError(instanceId, e);
            }
        };

        CompletableFuture<?> f = i.future();

        try {
            streamLog(i.logFile(), isRunning, sink);
        } catch (IOException e) {
            handleError(instanceId, e);
        }

        try {
            f.join();
            handleSuccess(instanceId);
        } catch (CancellationException | CompletionException e) {
            handleError(instanceId, e);
        } finally {
            cleanup(i);
        }

        log.info("execute ['{}', '{}'] -> done", instanceId, payload);
    }

    private void handleSuccess(UUID instanceId) {
        try {
            processApiClient.updateStatus(instanceId, ProcessStatus.FINISHED);
        } catch (ApiException e) {
            log.warn("handleSuccess ['{}'] -> error while updating status of a job: {}", instanceId, e.getMessage());
        }
        log.info("handleSuccess ['{}'] -> done", instanceId);
    }

    private void handleError(UUID instanceId, Throwable error) {
        ProcessStatus status = ProcessStatus.FAILED;

        if (error instanceof CancellationException) {
            log.info("handleError ['{}'] -> job cancelled", instanceId);
            status = ProcessStatus.CANCELLED;
        } else {
            log.error("handleError ['{}'] -> job failed", instanceId, error);
        }

        try {
            processApiClient.updateStatus(instanceId, status);
        } catch (ApiException e) {
            log.warn("handleError ['{}'] -> error while updating status of a job: {}", instanceId, e.getMessage());
        }

        log.info("handleError ['{}'] -> done", instanceId);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void streamLog(Path p, Supplier<Boolean> isRunning, Consumer<Chunk> sink) throws IOException {
        byte[] ab = new byte[8192];

        try (InputStream in = Files.newInputStream(p, StandardOpenOption.READ)) {
            while (true) {
                int read = in.read(ab, 0, ab.length);
                if (read > 0) {
                    sink.accept(new Chunk(ab, read));
                }

                if (read < ab.length) {
                    if (!isRunning.get()) {
                        // the log and the job are finished
                        break;
                    }

                    // job is still running, wait for more data
                    try {
                        Thread.sleep(logSteamMaxDelay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    private static final class Chunk {

        private final byte[] ab;
        private final int len;

        private Chunk(byte[] ab, int len) { //NOSONAR
            this.ab = ab;
            this.len = len;
        }
    }
}
