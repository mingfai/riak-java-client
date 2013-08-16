/*
 * Copyright 2013 Basho Technologies, Inc.
 *
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
 */
package com.basho.riak.client.core;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Brian Roach <roach at basho dot com>
 * @since 2.0
 */
public class RiakNode implements ChannelFutureListener, RiakResponseListener, PoolStateListener
{
    public enum State
    {
        CREATED, RUNNING, HEALTH_CHECKING, SHUTTING_DOWN, SHUTDOWN;
    }
    
    private final Logger logger = LoggerFactory.getLogger(RiakNode.class);
    private final EnumMap<Protocol, ConnectionPool> connectionPoolMap =
        new EnumMap<Protocol, ConnectionPool>(Protocol.class);
    private final String remoteAddress;
    private final List<NodeStateListener> stateListeners = 
        Collections.synchronizedList(new LinkedList<NodeStateListener>());
    
    private volatile ScheduledExecutorService executor; 
    private volatile boolean ownsExecutor;
    private volatile Bootstrap bootstrap;
    private volatile boolean ownsBootstrap;
    private volatile State state;
    private Map<Integer, InProgressOperation> inProgressMap = 
        new ConcurrentHashMap<Integer, InProgressOperation>();
    private volatile int readTimeoutInMillis;
    
    // TODO: Harden to prevent operation from being executed > 1 times?
    // TODO: how many channels on one event loop? 
    private RiakNode(Builder builder) throws UnknownHostException
    {
        this.remoteAddress = builder.remoteAddress;
        this.readTimeoutInMillis = builder.readTimeout;
        this.executor = builder.executor;
        
        if (builder.bootstrap != null)
        {
            this.bootstrap = builder.bootstrap.clone();
        }
        
        for (Map.Entry<Protocol, ConnectionPool.Builder> e : builder.protocolMap.entrySet())
        {
            ConnectionPool cp = e.getValue()
                                .withRemoteAddress(remoteAddress)
                                .build();
            connectionPoolMap.put(e.getKey(), cp);
        }
        this.state = State.CREATED;
    }
        
    private void stateCheck(State... allowedStates)
    {
        if (Arrays.binarySearch(allowedStates, state) < 0)
        {
            logger.debug("IllegalStateException; remote: {} required: {} current: {} ",
                         remoteAddress, Arrays.toString(allowedStates), state);
            throw new IllegalStateException("required: " 
                + Arrays.toString(allowedStates) 
                + " current: " + state );
        }
    }
    
    public synchronized RiakNode start()
    {
        stateCheck(State.CREATED);
        
        if (executor == null)
        {
            executor = Executors.newSingleThreadScheduledExecutor();
            ownsExecutor = true;
        }
        
        if (bootstrap == null)
        {
            bootstrap = new Bootstrap()
                .group(new NioEventLoopGroup())
                .channel(NioSocketChannel.class);
            ownsBootstrap = true;
        }
        
        for (Map.Entry<Protocol, ConnectionPool> e : connectionPoolMap.entrySet())
        {
            e.getValue().addStateListener(this);
            e.getValue().setBootstrap(bootstrap);
            e.getValue().setExecutor(executor);
            e.getValue().start();
        }
        state = State.RUNNING;
        notifyStateListeners();
        return this;
    }
    
    public synchronized void shutdown()
    {
        stateCheck(State.RUNNING, State.HEALTH_CHECKING);
        logger.info("Riak node shutting down; {}", remoteAddress);
        for (Map.Entry<Protocol, ConnectionPool> e : connectionPoolMap.entrySet())
        {
            e.getValue().shutdown();
        }
        // Notifications from the pools change our state. 
    }
    
    /**
     * Sets the Netty {@link Bootstrap} for this Node's connection pool(s).
     * {@link Bootstrap#clone()} is called to clone the bootstrap.
     * 
     * @param bootstrap
     * @throws IllegalArgumentException if it was already set via the builder.
     * @throws IllegalStateException if the node has already been started.
     * @see Builder#withBootstrap(io.netty.bootstrap.Bootstrap) 
     */
    public void setBootstrap(Bootstrap bootstrap)
    {
        stateCheck(State.CREATED);
        if (this.bootstrap != null)
        {
            throw new IllegalArgumentException("Bootstrap already set");
        }
        
        this.bootstrap = bootstrap.clone();
    }
    
    /**
     * Sets the {@link ScheduledExecutorService} for this Node and its pool(s).
     * 
     * @param executor
     * @throws IllegalArgumentException if it was already set via the builder.
     * @throws IllegalStateException if the node has already been started.
     * @see Builder#withExecutor(java.util.concurrent.ScheduledExecutorService) 
     */
    public void setExecutor(ScheduledExecutorService executor)
    {
        stateCheck(State.CREATED);
        if (this.executor != null)
        {
            throw new IllegalArgumentException("Executor already set");
        }
        this.executor = executor;
    }
    
    public void addStateListener(NodeStateListener listener)
    {
        stateListeners.add(listener);
    }
    
    public boolean removeStateListener(NodeStateListener listener)
    {
        return stateListeners.remove(listener);
    }
    
    private void notifyStateListeners()
    {
        synchronized(stateListeners)
        {
            for (Iterator<NodeStateListener> it = stateListeners.iterator(); it.hasNext();)
            {
                NodeStateListener listener = it.next();
                listener.nodeStateChanged(this, state);
            }
        }
    }
    
    // TODO: Streaming also - idea: BlockingDeque extension with listener
    
    /**
     * Submits the operation to be executed on this node.  
     * @param operation The operation to perform
     * @return {@code true} if this operation was accepted, {@code false} if there
     * were no available connections. 
     * @throws IllegalStateException if this node is not in the {@code RUNNING} or {@code HEALTH_CHECKING} state
     * @throws IllegalArgumentException if the protocol required for the operation is not supported by this node
     */
    public boolean execute(FutureOperation operation) 
    {
        stateCheck(State.RUNNING, State.HEALTH_CHECKING);
        
        Protocol protoToUse = chooseProtocol(operation);
        
        if (null == protoToUse)
        {
            throw new IllegalArgumentException("Node does not support required protocol");
        }
        
        operation.setLastNode(this);
        Channel channel = connectionPoolMap.get(protoToUse).getConnection();
        if (channel != null)
        {
            // add callback handler to end of pipeline which will callback to here
            // These remove themselves once they notify the listener
            if (readTimeoutInMillis > 0)
            {
                channel.pipeline()
                    .addLast("readTimeoutHandler", 
                             new ReadTimeoutHandler(readTimeoutInMillis, TimeUnit.MILLISECONDS));
            }
            channel.pipeline().addLast("riakResponseHandler", protoToUse.responseHandler(this));
            inProgressMap.put(channel., new InProgressOperation(protoToUse, operation));
            ChannelFuture writeFuture = channel.write(operation); 
            writeFuture.addListener(this);
            logger.debug("Operation executed on node {} {}", remoteAddress, channel.remoteAddress());
            return true;
        }
        else
        {
            return false;
        }
    }
    
    private Protocol chooseProtocol(FutureOperation operation)
    {
        // I seriously have no idea why this is unchecked
        @SuppressWarnings("unchecked") 
        List<Protocol> prefList = operation.getProtocolPreflist();
        Protocol protoToUse = null;
        for (Protocol p : prefList)
        {
            if (connectionPoolMap.keySet().contains(p))
            {
                protoToUse = p;
                break;
            }
        }
        
        return protoToUse;
    }
    
    @Override
    public synchronized void poolStateChanged(ConnectionPool pool, ConnectionPool.State newState)
    {
        switch (newState)
        {
            case HEALTH_CHECKING:
                this.state = State.HEALTH_CHECKING;
                notifyStateListeners();
                logger.info("RiakNode offline, health checking; {}", remoteAddress);
                break;
            case RUNNING:
                this.state = State.RUNNING;
                notifyStateListeners();
                logger.info("RiakNode running; {}", remoteAddress);
                break;
            case SHUTTING_DOWN:
                if (this.state == State.RUNNING ||  this.state == State.HEALTH_CHECKING)
                {
                    this.state = State.SHUTTING_DOWN;
                    notifyStateListeners();
                    logger.info("RiakNode shutting down due to pool shutdown; {}", remoteAddress);
                }
                break;
            case SHUTDOWN:
                connectionPoolMap.remove(pool.getProtocol());
                if (connectionPoolMap.isEmpty())
                {
                    this.state = State.SHUTDOWN;
                    notifyStateListeners();
                    if (ownsExecutor)
                    {
                        executor.shutdown();
                    }
                    if (ownsBootstrap)
                    {
                        bootstrap.shutdown();
                    }
                    logger.info("RiakNode shut down due to pool shutdown; {}", remoteAddress);
                }
                break;
            default:
                break;
        }
    }

    @Override
    public void onSuccess(Channel channel, RiakResponse response)
    {
        logger.debug("Operation onSuccess() channel: {}", channel.remoteAddress());
        if (readTimeoutInMillis > 0)
        {
            channel.pipeline().remove("readTimeoutHandler");
        }
        InProgressOperation inProgress = inProgressMap.remove(channel.id());
        connectionPoolMap.get(inProgress.getProtocol()).returnConnection(channel);
        inProgress.getOperation().setResponse(response);
    }

    @Override
    public void onException(Channel channel, Throwable t)
    {
        logger.debug("Operation onException() channel: {} {}", channel.remoteAddress(), t);
        if (readTimeoutInMillis > 0)
        {
            channel.pipeline().remove("readTimeoutHandler");
        }
        InProgressOperation inProgress = inProgressMap.remove(channel.id());
        // There is an edge case where a write could fail after the message encoding
        // occured in the pipeline. In that case we'll get an exception from the 
        // handler due to it thinking there was a request in flight 
        // but will not have an entry in inProgress
        if (inProgress != null)
        {
            connectionPoolMap.get(inProgress.getProtocol()).returnConnection(channel);
            inProgress.getOperation().setException(t);
        }
    }
    
    // The only Netty future we are listening to is the WriteFuture
    @Override
    public void operationComplete(ChannelFuture future) throws Exception
    {
        // See how the write worked out ...
        if (!future.isSuccess())
        {
            InProgressOperation inProgress = inProgressMap.remove(future.channel().id());
            logger.info("Write failed on node {} {}", remoteAddress, inProgress.getProtocol());
            connectionPoolMap.get(inProgress.getProtocol()).returnConnection(future.channel());
            inProgress.getOperation().setException(future.cause());
        }
    }
    
    private class InProgressOperation
    {
        private final Protocol p;
        private final FutureOperation operation;
        
        public InProgressOperation(Protocol p, FutureOperation operation)
        {
            this.p = p;
            this.operation = operation;
        }
        
        public Protocol getProtocol()
        {
            return p;
        }
        
        public FutureOperation getOperation()
        {
            return operation;
        }
    }
    
    /**
     * Returns the {@code remoteAddress} for this RiakNode
     * @return The IP address or FQDN as a {@code String}
     */
    public String getRemoteAddress()
    {
        return this.remoteAddress;
    }
    
    /**
     * Returns the current state of this node.
     * @return The state
     */
    public State getNodeState()
    {
        return this.state;
    }
    
    /**
     * Returns the read timeout in milliseconds for connections in this pool
     * @return the readTimeout
     * @see Builder#withReadTimeout(int) 
     */
    public int getReadTimeout()
    {
        stateCheck(State.CREATED, State.RUNNING, State.HEALTH_CHECKING);
        return readTimeoutInMillis;
    }

    /**
     * Sets the read timeout for connections in this pool
     * @param readTimeoutInMillis the readTimeout to set
     * @see Builder#withReadTimeout(int) 
     */
    public void setReadTimeout(int readTimeoutInMillis)
    {
        stateCheck(State.CREATED, State.RUNNING, State.HEALTH_CHECKING);
        this.readTimeoutInMillis = readTimeoutInMillis;
    }
    
    /**
     * Builder used to construct a RiakNode.
     * 
     * <p>If a protocol is not specified protocol buffers will be used on the default 
     * port.
     * </p>
     * <p>
     * Note that many of these options revolve around constructing the underlying
     * {@link ConnectionPool}s used by this node. 
     * </p>
     * 
     * 
     */
    public static class Builder
    {
        /**
         * The default remote address to be used if not specified: {@value #DEFAULT_REMOTE_ADDRESS}
         * @see #withRemoteAddress(java.lang.String) 
         */
        public final static String DEFAULT_REMOTE_ADDRESS = "127.0.0.1";
        /**
         * The default read timeout in milliseconds if not specified: {@value #DEFAULT_READ_TIMEOUT}
         * A value of {@code 0} means to wait indefinitely 
         * @see #withReadTimeout(int) 
         */
        public final static int DEFAULT_READ_TIMEOUT = 0;
        
        private String remoteAddress = DEFAULT_REMOTE_ADDRESS;
        private ScheduledExecutorService executor;
        private boolean ownsExecutor;
        private Bootstrap bootstrap;
        private boolean ownsBootstrap;
        private int readTimeout = DEFAULT_READ_TIMEOUT;
        private final EnumMap<Protocol, ConnectionPool.Builder> protocolMap = 
            new EnumMap<Protocol, ConnectionPool.Builder>(Protocol.class);
        
        
        public Builder(Protocol p)
        {
            getPoolBuilder(p);
        }
        
        // Used by the from() method
        private Builder() {}
        
        /**
         * Sets the remote address for this RiakNode. 
         * @param remoteAddress Can either be a FQDN or IP address
         * @return this
         */
        public Builder withRemoteAddress(String remoteAddress)
        {
            this.remoteAddress = remoteAddress;
            return this;
        }
        
        /**
         * Specifies a protocol this node will support using the default port
         * 
         * @param p - the protocol
         * @return this
         * @see Protocol
         */
        public Builder addProtocol(Protocol p)
        {
            getPoolBuilder(p);
            return this;
        }
        
        /**
         * Specifies a protocol this node will support using the supplied port
         * @param p - the protocol
         * @param port - the port
         * @return this
         * @see Protocol
         */
        public Builder withPort(Protocol p, int port)
        {
            ConnectionPool.Builder builder = getPoolBuilder(p);
            builder.withPort(port);
            return this;
        }
        
        /**
         * Specifies the minimum number for connections to be maintained for the specific protocol
         * @param p 
         * @param minConnections
         * @return this
         * @see ConnectionPool.Builder#withMinConnections(int) 
         */
        public Builder withMinConnections(Protocol p, int minConnections)
        {
            ConnectionPool.Builder builder = getPoolBuilder(p);
            builder.withMinConnections(minConnections);
            return this;
        }
        
        /**
         * Specifies the maximum number of connections allowed for the specific protocol
         * @param p
         * @param maxConnections
         * @return this
         * @see ConnectionPool.Builder#withMaxConnections(int) 
         */
        public Builder withMaxConnections(Protocol p, int maxConnections)
        {
            ConnectionPool.Builder builder = getPoolBuilder(p);
            builder.withMaxConnections(maxConnections);
            return this;
        }
        
        /**
         * Specifies the idle timeout for the specific protocol
         * @param p
         * @param idleTimeoutInMillis
         * @return this
         * @see ConnectionPool.Builder#withIdleTimeout(int) 
         */
        public Builder withIdleTimeout(Protocol p, int idleTimeoutInMillis)
        {
            ConnectionPool.Builder builder = getPoolBuilder(p);
            builder.withIdleTimeout(idleTimeoutInMillis);
            return this;
        }
        
        /**
         * Specifies the connection timeout for the specific protocol
         * @param p
         * @param connectionTimeoutInMillis
         * @return this
         * @see ConnectionPool.Builder#withConnectionTimeout(int) 
         */
        public Builder withConnectionTimeout(Protocol p, int connectionTimeoutInMillis)
        {
            ConnectionPool.Builder builder = getPoolBuilder(p);
            builder.withConnectionTimeout(connectionTimeoutInMillis);
            return this;
        }
        
        /**
         * Specifies the read timeout when waiting for a reply from Riak
         * @param readTimeoutInMillis
         * @return this
         * @see #DEFAULT_READ_TIMEOUT
         */
        public Builder withReadTimeout(int readTimeoutInMillis)
        {
            this.readTimeout = readTimeoutInMillis;
            return this;
        }
        
        /**
         * Provides an executor for this node to use for internal maintenance tasks.
         * This same executor will be used for this node's connection pool(s)
         * @param executor
         * @return this
         * @see ConnectionPool.Builder#withExecutor(java.util.concurrent.ScheduledExecutorService) 
         */
        public Builder withExecutor(ScheduledExecutorService executor)
        {
            this.executor = executor;
            return this;
        }
        
        /**
         * Provides a Netty Bootstrap for this node to use. 
         * This same Bootstrap will be used for this node's underlying connection pool(s)
         * @param bootstrap
         * @return this
         * @see ConnectionPool.Builder#withBootstrap(io.netty.bootstrap.Bootstrap) 
         */
        public Builder withBootstrap(Bootstrap bootstrap)
        {
            this.bootstrap = bootstrap;
            return this;
        }
        
        private ConnectionPool.Builder getPoolBuilder(Protocol p)
        {
            ConnectionPool.Builder builder = protocolMap.get(p);
            if (builder == null)
            {
                builder = new ConnectionPool.Builder(p);
                protocolMap.put(p, builder);
            }
            return builder;
        }
        
        public static Builder from(Builder b)
        {
            Builder builder = new Builder();
            builder.bootstrap = b.bootstrap;
            builder.executor = b.executor;
            builder.ownsBootstrap = b.ownsBootstrap;
            builder.ownsExecutor = b.ownsExecutor;
            builder.protocolMap.putAll(b.protocolMap);
            builder.remoteAddress = b.remoteAddress;
            return builder;
        }
        
        /**
         * Builds a RiakNode.
         * If a Netty {@code Bootstrap} and/or a {@code ScheduledExecutorService} has not been provided they
         * will be created. 
         * @return a new Riaknode
         * @throws UnknownHostException if the DNS lookup fails for the supplied hostname
         */
        public RiakNode build() throws UnknownHostException 
        {
            return new RiakNode(this);
        }
        
        
        public static List<RiakNode> buildNodes(Builder builder, List<String> remoteAddresses) 
            throws UnknownHostException
        {
            List<RiakNode> nodes = new ArrayList<RiakNode>(remoteAddresses.size());
            for (String remoteAddress : remoteAddresses)
            {
                builder.withRemoteAddress(remoteAddress);
                nodes.add(builder.build());
            }
            return nodes;
        }
        
        public static List<RiakNode.Builder> createBuilderList(Builder builder, List<String> remoteAddresses)
        {
            List<RiakNode.Builder> builders = new ArrayList<RiakNode.Builder>(remoteAddresses.size());
            for (String remoteAddress : remoteAddresses)
            {
                Builder b = Builder.from(builder);
                b.withRemoteAddress(remoteAddress);
                builders.add(b);
            }
            return builders;
        }
        
    }
    
}
