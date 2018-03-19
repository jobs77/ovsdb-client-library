/*
 * Copyright (c) 2018 VMware, Inc. All Rights Reserved.
 *
 * This product is licensed to you under the BSD-2 license (the "License").
 * You may not use this product except in compliance with the BSD-2 License.
 *
 * This product may include a number of subcomponents with separate copyright
 * notices and license terms. Your use of these subcomponents is subject to the
 * terms and conditions of the subcomponent's license, as noted in the LICENSE
 * file.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.vmware.ovsdb.netty;

import com.fasterxml.jackson.databind.JsonNode;
import com.vmware.ovsdb.callback.ConnectionCallback;
import com.vmware.ovsdb.callback.LockCallback;
import com.vmware.ovsdb.callback.MonitorCallback;
import com.vmware.ovsdb.exception.OvsdbClientException;
import com.vmware.ovsdb.jsonrpc.v1.annotation.JsonRpcServiceMethod;
import com.vmware.ovsdb.jsonrpc.v1.exception.JsonRpcException;
import com.vmware.ovsdb.jsonrpc.v1.exception.JsonRpcTransportException;
import com.vmware.ovsdb.jsonrpc.v1.service.JsonRpcV1Client;
import com.vmware.ovsdb.jsonrpc.v1.service.JsonRpcV1Server;
import com.vmware.ovsdb.jsonrpc.v1.service.impl.JsonRpcV1ClientImpl;
import com.vmware.ovsdb.jsonrpc.v1.service.impl.JsonRpcV1ServerImpl;
import com.vmware.ovsdb.jsonrpc.v1.spi.JsonRpcTransporter;
import com.vmware.ovsdb.jsonrpc.v1.util.JsonRpcConstant;
import com.vmware.ovsdb.jsonrpc.v1.util.JsonUtil;
import com.vmware.ovsdb.protocol.methods.LockResult;
import com.vmware.ovsdb.protocol.methods.MonitorRequests;
import com.vmware.ovsdb.protocol.methods.TableUpdates;
import com.vmware.ovsdb.protocol.operation.Operation;
import com.vmware.ovsdb.protocol.operation.result.OperationResult;
import com.vmware.ovsdb.protocol.schema.DatabaseSchema;
import com.vmware.ovsdb.protocol.util.OvsdbConstant;
import com.vmware.ovsdb.service.OvsdbClient;
import com.vmware.ovsdb.service.OvsdbConnectionInfo;
import com.vmware.ovsdb.util.PropertyManager;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.ssl.SslHandler;
import java.lang.invoke.MethodHandles;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.cert.Certificate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLPeerUnverifiedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// TODO: Move the OVSDB logic out of this handler if possible
class OvsdbClientHandler extends ChannelInboundHandlerAdapter {

    private static final Logger LOGGER = LoggerFactory
        .getLogger(MethodHandles.lookup().lookupClass());

    private static final String KEY_RPC_TIMEOUT_SEC = "rpc.timeout.sec";

    private static final long DEFAULT_RPC_TIMEOUT_SEC = 60;

    private static final AtomicLong callId = new AtomicLong(0);

    private final ConnectionCallback connectionCallback;

    private final ScheduledExecutorService executorService;

    private final ConcurrentMap<String, MonitorCallback> monitorCallbacks = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, LockCallback> lockCallbacks = new ConcurrentHashMap<>();

    private JsonRpcV1Client jsonRpcClient;

    private JsonRpcV1Server jsonRpcServer;

    private OvsdbClient ovsdbClient;

    OvsdbClientHandler(
        ConnectionCallback connectionCallback, ScheduledExecutorService executorService
    ) {
        this.connectionCallback = connectionCallback;
        this.executorService = executorService;
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();
        LOGGER.info("Channel {} is now active", channel);

        SslHandler sslHandler = ctx.pipeline().get(SslHandler.class);
        // SSL is enabled, notify connection callback only after the handshake is done
        if (sslHandler != null) {
            sslHandler.handshakeFuture().addListener(future -> notifyConnection(channel));
        } else {
            notifyConnection(channel);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        LOGGER.info("Channel {} is now inactive", ctx.channel());
        executorService.submit(() -> connectionCallback.disconnected(ovsdbClient));
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        JsonNode jsonNode = (JsonNode) msg;
        if (isRequestOrNotification(jsonNode)) {
            executorService.submit(() -> {
                try {
                    jsonRpcServer.handleRequest(jsonNode);
                } catch (JsonRpcException e) {
                    LOGGER.error("Failed to handle request " + jsonNode, e);
                }
            });
        } else if (isResponse(jsonNode)) {
            executorService.submit(() -> {
                try {
                    jsonRpcClient.handleResponse(jsonNode);
                } catch (JsonRpcException e) {
                    LOGGER.error("Failed to handle response " + jsonNode, e);
                }
            });
        } else {
            // Ignore non-JSON_RPC messages
            LOGGER.warn("Received invalid message {}", jsonNode);
        }
    }

    private boolean isRequestOrNotification(JsonNode jsonNode) {
        // Make sure "id", "method" and "params" fields exist
        // This dose NOT guarantee that the values are not null
        return jsonNode.get(JsonRpcConstant.ID) != null
            && jsonNode.get(JsonRpcConstant.METHOD) != null
            && jsonNode.get(JsonRpcConstant.PARAMS) != null;
    }

    private boolean isResponse(JsonNode jsonNode) {
        // Make sure "id", "result" and "error" fields exist
        // This dose NOT guarantee that the values are not null
        return jsonNode.get(JsonRpcConstant.ID) != null
            && jsonNode.get(JsonRpcConstant.RESULT) != null
            && jsonNode.get(JsonRpcConstant.ERROR) != null;
    }

    private void notifyConnection(Channel channel) {
        JsonRpcTransporter transporter = new JsonRpcTransporter() {
            @Override
            public void send(JsonNode data) throws JsonRpcTransportException {
                try {
                    channel.writeAndFlush(JsonUtil.serialize(data));
                } catch (Throwable e) {
                    throw new JsonRpcTransportException(e);
                }
            }

            @Override
            public void close() {
                channel.close();
            }
        };
        long maxTimeoutSec = PropertyManager
            .getLongProperty(KEY_RPC_TIMEOUT_SEC, DEFAULT_RPC_TIMEOUT_SEC);

        jsonRpcClient = new JsonRpcV1ClientImpl(transporter, executorService, maxTimeoutSec,
            TimeUnit.SECONDS);

        jsonRpcServer = new JsonRpcV1ServerImpl(transporter, new OvsdbRequestHandler());

        ovsdbClient = new OvsdbClientImpl(getConnectionInfo(channel));

        executorService.submit(() -> connectionCallback.connected(ovsdbClient));
    }

    private OvsdbConnectionInfo getConnectionInfo(Channel channel) {
        InetSocketAddress remoteSocketAddress
            = (InetSocketAddress) channel.remoteAddress();
        InetAddress remoteAddress = remoteSocketAddress.getAddress();
        int remotePort = remoteSocketAddress.getPort();
        InetSocketAddress localSocketAddress
            = (InetSocketAddress) channel.localAddress();
        InetAddress localAddress = localSocketAddress.getAddress();
        int localPort = localSocketAddress.getPort();

        SslHandler sslHandler = channel.pipeline().get(SslHandler.class);
        Certificate remoteCertificate = null;
        if (sslHandler != null) {
            try {
                remoteCertificate = sslHandler.engine().getSession().getPeerCertificates()[0];
            } catch (SSLPeerUnverifiedException e) {
                LOGGER.error("Failed to get peer certificate of channel " + channel, e);
            }
        }
        return new OvsdbConnectionInfo(
            localAddress, localPort, remoteAddress, remotePort, remoteCertificate
        );
    }

    private String getNextId() {
        return String.valueOf(callId.getAndIncrement());
    }

    private class OvsdbClientImpl implements OvsdbClient {

        private final OvsdbConnectionInfo connectionInfo;

        private AtomicBoolean isActive = new AtomicBoolean(true);

        OvsdbClientImpl(OvsdbConnectionInfo connectionInfo) {
            this.connectionInfo = connectionInfo;
        }

        @Override
        public CompletableFuture<String[]> listDatabases() throws OvsdbClientException {
            return callMethod(OvsdbConstant.LIST_DBS, String[].class);
        }

        @Override
        public CompletableFuture<DatabaseSchema> getSchema(String dbName)
            throws OvsdbClientException {
            return callMethod(OvsdbConstant.GET_SCHEMA, DatabaseSchema.class, dbName);
        }

        @Override
        public CompletableFuture<OperationResult[]> transact(
            String dbName, List<Operation> operations
        ) throws OvsdbClientException {
            Object[] params = new Object[operations.size() + 1];
            params[0] = dbName;
            for (int i = 0; i < operations.size(); i++) {
                params[i + 1] = operations.get(i);
            }
            return callMethod(OvsdbConstant.TRANSACT, OperationResult[].class, params);
        }

        @Override
        public CompletableFuture<TableUpdates> monitor(
            String dbName, String monitorId, MonitorRequests monitorRequests,
            MonitorCallback monitorCallback
        ) throws OvsdbClientException {
            CompletableFuture<TableUpdates> f = callMethod(
                OvsdbConstant.MONITOR, TableUpdates.class, dbName, monitorId, monitorRequests
            );
            // If this monitor request succeeds, save the callback
            return f.thenApply(tableUpdates -> {
                monitorCallbacks.put(monitorId, monitorCallback);
                return tableUpdates;
            });
        }

        @Override
        public CompletableFuture<Void> cancelMonitor(String monitorId) throws OvsdbClientException {
            CompletableFuture<Void> f = callMethod(
                OvsdbConstant.MONITOR_CANCEL, Void.class, monitorId);
            return f.thenApply(result -> {
                monitorCallbacks.remove(monitorId);
                return result;
            });
        }

        // TODO: Write tests for the following 3 operations.
        @Override
        public CompletableFuture<LockResult> lock(String lockId, LockCallback lockCallback)
            throws OvsdbClientException {
            CompletableFuture<LockResult> f = callMethod(
                OvsdbConstant.LOCK, LockResult.class, lockId);
            return f.thenApply(lockResult -> {
                lockCallbacks.put(lockId, lockCallback);
                return lockResult;
            });
        }

        @Override
        public CompletableFuture<LockResult> steal(String lockId) throws OvsdbClientException {
            return callMethod(OvsdbConstant.LOCK, LockResult.class, lockId);
        }

        @Override
        public CompletableFuture<Void> unlock(String lockId) throws OvsdbClientException {
            CompletableFuture<Void> f = callMethod(OvsdbConstant.UNLOCK, Void.class, lockId);
            return f.thenApply(result -> {
                lockCallbacks.remove(lockId);
                return result;
            });
        }

        @Override
        public OvsdbConnectionInfo getConnectionInfo() {
            return connectionInfo;
        }

        @Override
        public void shutdown() {
            if (isActive.getAndSet(false)) {
                jsonRpcClient.shutdown();
                jsonRpcServer.shutdown();

                monitorCallbacks.clear();
                lockCallbacks.clear();
            }
        }

        private <T> CompletableFuture<T> callMethod(
            String method, Class<T> returnType, Object... params
        ) throws OvsdbClientException {
            exceptionIfNotActive();
            try {
                return jsonRpcClient.call(getNextId(), method, returnType, params);
            } catch (JsonRpcException e) {
                throw new OvsdbClientException(e);
            }
        }

        private void exceptionIfNotActive() throws OvsdbClientException {
            if (!isActive.get()) {
                throw new OvsdbClientException("This OVSDB client is not active");
            }
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + " ["
                + "connectionInfo=" + connectionInfo
                + ", isActive=" + isActive
                + "]";
        }
    }

    public class OvsdbRequestHandler {

        @JsonRpcServiceMethod(value = OvsdbConstant.ECHO)
        public Object[] handleEcho(Object... params) {
            return params;
        }

        @JsonRpcServiceMethod(value = OvsdbConstant.UPDATE)
        public void handleUpdate(String monitorId, TableUpdates tableUpdates) {
            MonitorCallback monitorCallback = monitorCallbacks.get(monitorId);
            if (monitorCallback != null) {
                monitorCallback.update(tableUpdates);
            }
        }

        @JsonRpcServiceMethod(value = OvsdbConstant.LOCKED)
        public void handleLocked(String lockId) {
            LockCallback lockCallback = lockCallbacks.get(lockId);
            if (lockCallback != null) {
                lockCallback.locked();
            }
        }

        @JsonRpcServiceMethod(value = OvsdbConstant.STOLEN)
        public void handleStolen(String lockId) {
            LockCallback lockCallback = lockCallbacks.get(lockId);
            if (lockCallback != null) {
                lockCallback.stolen();
            }
        }
    }
}