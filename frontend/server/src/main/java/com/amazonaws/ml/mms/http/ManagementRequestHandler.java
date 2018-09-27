/*
 * Copyright 2018 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except in compliance
 * with the License. A copy of the License is located at
 *
 * http://aws.amazon.com/apache2.0/
 *
 * or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package com.amazonaws.ml.mms.http;

import com.amazonaws.ml.mms.archive.InvalidModelException;
import com.amazonaws.ml.mms.archive.Manifest;
import com.amazonaws.ml.mms.archive.ModelArchive;
import com.amazonaws.ml.mms.common.ErrorCodes;
import com.amazonaws.ml.mms.openapi.OpenApiUtils;
import com.amazonaws.ml.mms.util.NettyUtils;
import com.amazonaws.ml.mms.wlm.Model;
import com.amazonaws.ml.mms.wlm.ModelManager;
import com.amazonaws.ml.mms.wlm.WorkerThread;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A class handling inbound HTTP requests to the management API.
 *
 * <p>This class
 */
public class ManagementRequestHandler extends HttpRequestHandler {

    private static final Logger logger = LoggerFactory.getLogger(ManagementRequestHandler.class);

    /** Creates a new {@code HttpRequestHandler} instance. */
    public ManagementRequestHandler() {}

    protected boolean handleRequest(
            ChannelHandlerContext ctx,
            FullHttpRequest req,
            QueryStringDecoder decoder,
            String[] segments) {

        if ("/".equals(decoder.path())) {
            handleListModels(ctx, req);
            return true;
        }

        switch (segments[1]) {
            case "models":
                handleModelsApi(ctx, req, segments, decoder);
                return true;
            default:
                return false;
        }
    }

    protected void handleApiDescription(ChannelHandlerContext ctx) {
        NettyUtils.sendJsonResponse(ctx, OpenApiUtils.listManagementApis());
    }

    private void handleListModels(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (HttpMethod.OPTIONS.equals(req.method())) {
            handleApiDescription(ctx);
            return;
        }
        NettyUtils.sendError(
                ctx, HttpResponseStatus.NOT_FOUND, ErrorCodes.LIST_MODELS_INVALID_REQUEST_HEADER);
    }

    private void handleListModels(ChannelHandlerContext ctx, QueryStringDecoder decoder) {
        int limit = NettyUtils.getIntParameter(decoder, "limit", 100);
        int pageToken = NettyUtils.getIntParameter(decoder, "nextPageToken", 0);
        if (limit > 100 || limit < 0) {
            limit = 100;
        }
        if (pageToken < 0) {
            pageToken = 0;
        }

        ModelManager modelManager = ModelManager.getInstance();
        Map<String, Model> models = modelManager.getModels();

        List<String> keys = new ArrayList<>(models.keySet());
        Collections.sort(keys);
        ListModelsResponse list = new ListModelsResponse();

        int last = pageToken + limit;
        if (last > keys.size()) {
            last = keys.size();
        } else {
            list.setNextPageToken(String.valueOf(last));
        }

        for (int i = pageToken; i < last; ++i) {
            String modelName = keys.get(i);
            Model model = models.get(modelName);
            list.addModel(modelName, model.getModelUrl());
        }

        NettyUtils.sendJsonResponse(ctx, list);
    }

    protected void handleModelsApi(
            ChannelHandlerContext ctx,
            FullHttpRequest req,
            String[] segments,
            QueryStringDecoder decoder) {
        HttpMethod method = req.method();
        if (segments.length < 3) {
            if (HttpMethod.GET.equals(method)) {
                handleListModels(ctx, decoder);
                return;
            } else if (HttpMethod.POST.equals(method)) {
                handleRegisterModel(ctx, decoder);
                return;
            }
            NettyUtils.sendError(
                    ctx,
                    HttpResponseStatus.BAD_REQUEST,
                    ErrorCodes.MODELS_API_INVALID_MODELS_REQUEST);
        }

        if (HttpMethod.GET.equals(method)) {
            handleDescribeModel(ctx, segments[2]);
        } else if (HttpMethod.PUT.equals(method)) {
            handleScaleModel(ctx, decoder, segments[2]);
        } else if (HttpMethod.DELETE.equals(method)) {
            handleUnregisterModel(ctx, segments[2]);
        } else {
            NettyUtils.sendError(
                    ctx,
                    HttpResponseStatus.BAD_REQUEST,
                    ErrorCodes.MODELS_API_INVALID_MODELS_REQUEST);
        }
    }

    protected void handleDescribeModel(ChannelHandlerContext ctx, String modelName) {
        ModelManager modelManager = ModelManager.getInstance();
        Model model = modelManager.getModels().get(modelName);
        if (model == null) {
            NettyUtils.sendError(
                    ctx, HttpResponseStatus.NOT_FOUND, ErrorCodes.MODELS_API_MODEL_NOT_FOUND);
            return;
        }

        DescribeModelResponse resp = new DescribeModelResponse();
        resp.setModelName(modelName);
        resp.setModelUrl(model.getModelUrl());
        resp.setBatchSize(model.getBatchSize());
        resp.setMaxBatchDelay(model.getMaxBatchDelay());
        resp.setMaxWorkers(model.getMaxWorkers());
        resp.setMinWorkers(model.getMinWorkers());
        Manifest manifest = model.getModelArchive().getManifest();
        Manifest.Engine engine = manifest.getEngine();
        if (engine != null) {
            resp.setEngine(engine.getEngineName().getValue());
        }
        resp.setModelVersion(manifest.getModel().getModelVersion());
        resp.setRuntime(manifest.getRuntime().getValue());

        List<WorkerThread> workers = modelManager.getWorkers(modelName);
        for (WorkerThread worker : workers) {
            String workerId = worker.getWorkerId();
            long startTime = worker.getStartTime();
            boolean isRunning = worker.isRunning();
            int gpuId = worker.getGpuId();
            long memory = worker.getMemory();
            resp.addWorker(workerId, startTime, isRunning, gpuId, memory);
        }

        NettyUtils.sendJsonResponse(ctx, resp);
    }

    private void handleRegisterModel(ChannelHandlerContext ctx, QueryStringDecoder decoder) {
        String modelUrl = NettyUtils.getParameter(decoder, "url", null);
        if (modelUrl == null) {
            NettyUtils.sendError(
                    ctx, HttpResponseStatus.BAD_REQUEST, ErrorCodes.MODELS_POST_INVALID_REQUEST);
            return;
        }

        String modelName = NettyUtils.getParameter(decoder, "model_name", null);
        String runtime = NettyUtils.getParameter(decoder, "runtime", null);
        String handler = NettyUtils.getParameter(decoder, "handler", null);
        int batchSize = NettyUtils.getIntParameter(decoder, "batch_size", 1);
        int maxBatchDelay = NettyUtils.getIntParameter(decoder, "max_batch_delay", 100);
        int initialWorkers = NettyUtils.getIntParameter(decoder, "initial_workers", 0);
        boolean synchronous =
                Boolean.parseBoolean(NettyUtils.getParameter(decoder, "synchronous", null));
        Manifest.RuntimeType runtimeType = null;
        if (runtime != null) {
            try {
                runtimeType = Manifest.RuntimeType.fromValue(runtime);
            } catch (IllegalArgumentException e) {
                String msg = e.getMessage();
                NettyUtils.sendError(
                        ctx,
                        HttpResponseStatus.BAD_REQUEST,
                        ErrorCodes.MODELS_POST_MODEL_MANIFEST_RUNTIME_INVALID
                                + " Invalid model runtime given. "
                                + msg);
                return;
            }
        }

        ModelManager modelManager = ModelManager.getInstance();
        final ModelArchive archive;
        try {
            archive =
                    modelManager.registerModel(
                            modelUrl, modelName, runtimeType, handler, batchSize, maxBatchDelay);
        } catch (IOException e) {
            logger.warn("Failed to download model", e);
            NettyUtils.sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR);
            return;
        } catch (InvalidModelException e) {
            logger.warn("Failed to load model", e);
            NettyUtils.sendError(ctx, HttpResponseStatus.BAD_REQUEST, e.getMessage());
            return;
        }

        modelName = archive.getModelName();

        final String msg = "Model \"" + modelName + "\" registered";
        if (initialWorkers <= 0) {
            NettyUtils.sendJsonResponse(ctx, new StatusResponse(msg));
            return;
        }

        updateModelWorkers(
                ctx,
                modelName,
                initialWorkers,
                initialWorkers,
                synchronous,
                f -> {
                    modelManager.unregisterModel(archive.getModelName());
                    archive.clean();
                    return null;
                });
    }

    private void handleUnregisterModel(ChannelHandlerContext ctx, String modelName) {
        ModelManager modelManager = ModelManager.getInstance();
        if (!modelManager.unregisterModel(modelName)) {
            NettyUtils.sendError(ctx, HttpResponseStatus.BAD_REQUEST, "Model not found");
        }
        String msg = "Model \"" + modelName + "\" unregistered";
        NettyUtils.sendJsonResponse(ctx, new StatusResponse(msg));
    }

    private void handleScaleModel(
            ChannelHandlerContext ctx, QueryStringDecoder decoder, String modelName) {
        int minWorkers = NettyUtils.getIntParameter(decoder, "min_worker", 1);
        int maxWorkers = NettyUtils.getIntParameter(decoder, "max_worker", 1);
        boolean synchronous =
                Boolean.parseBoolean(NettyUtils.getParameter(decoder, "synchronous", null));

        ModelManager modelManager = ModelManager.getInstance();
        if (!modelManager.getModels().containsKey(modelName)) {
            NettyUtils.sendError(
                    ctx, HttpResponseStatus.NOT_FOUND, ErrorCodes.MODELS_API_MODEL_NOT_FOUND);
            return;
        }
        updateModelWorkers(ctx, modelName, minWorkers, maxWorkers, synchronous, null);
    }

    private void updateModelWorkers(
            final ChannelHandlerContext ctx,
            String modelName,
            int minWorkers,
            int maxWorkers,
            boolean synchronous,
            final Function<Void, Void> onError) {
        ModelManager modelManager = ModelManager.getInstance();
        CompletableFuture<Boolean> future =
                modelManager.updateModel(modelName, minWorkers, maxWorkers);
        if (!synchronous) {
            NettyUtils.sendJsonResponse(
                    ctx, new StatusResponse("Worker updated"), HttpResponseStatus.ACCEPTED);
            return;
        }
        future.thenApply(
                        v -> {
                            if (!v) {
                                if (onError != null) {
                                    onError.apply(null);
                                }
                                NettyUtils.sendError(
                                        ctx,
                                        HttpResponseStatus.BAD_REQUEST,
                                        ErrorCodes.MODELS_API_MODEL_NOT_FOUND);
                            } else {
                                NettyUtils.sendJsonResponse(
                                        ctx,
                                        new StatusResponse("Worker scaled"),
                                        HttpResponseStatus.OK);
                            }
                            return v;
                        })
                .exceptionally(
                        (e) -> {
                            if (onError != null) {
                                onError.apply(null);
                            }
                            NettyUtils.sendError(
                                    ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, e.getMessage());
                            return null;
                        });
    }
}