/*
 * Copyright 2013 Basho Technologies Inc.
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
package com.basho.riak.client.core.netty;

import com.basho.riak.client.core.RiakHttpMessage;
import com.basho.riak.client.core.RiakResponseListener;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.timeout.ReadTimeoutException;

import java.util.LinkedList;
import java.util.List;

/**
 *
 * @author Brian Roach <roach at basho dot com>
 * @since 2.0
 */
@Deprecated /** let's forget anything about HTTP! */
public class RiakHttpMessageHandler extends SimpleChannelInboundHandler<Object>
{
    private final RiakResponseListener listener;
    private RiakHttpMessage message;
    private final List<ByteBuf> chunks;
    private int totalContentLength;
    private boolean timedOut = false;
    
    public RiakHttpMessageHandler(RiakResponseListener listener)
    {
        this.listener = listener;
        this.chunks = new LinkedList<ByteBuf>();
    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception 
    {
        if (cause instanceof ReadTimeoutException)
        {
            timedOut = true;
            listener.onException(ctx.channel(), cause);
        }
        else
        {
            if (!timedOut)
            {
                listener.onException(ctx.channel(), cause);
            }
            ctx.channel().pipeline().remove(this);
        }
    }

    @Override protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception
    {
        if (msg instanceof HttpResponse)
        {
            message = new RiakHttpMessage((HttpResponse)msg);
        }
        
        if (msg instanceof HttpContent)
        {
            /** TODO: commented for compilation
             * chunks.add(((HttpContent)msg).data().retain());
             * totalContentLength += ((HttpContent)msg).data().readableBytes();
             */
            
            if (msg instanceof LastHttpContent)
            {
                byte[] bytes = new byte[totalContentLength];
                int index = 0;
                for (ByteBuf buffer : chunks)
                {
                    int readable = buffer.readableBytes();
                    buffer.readBytes(bytes, index, readable);
                    index += readable;
                    buffer.release();
                }
                
                int responseCode = message.getResponse().getStatus().code();
                message.setContent(bytes);

                /**  TODO: commented for compilation
                if ( responseCode < 200 || 
                    (responseCode >= 400 && responseCode != 404 && responseCode != 412 ) )
                {
                    listener.onException(chc.channel(), new RiakResponseException(responseCode, new String(bytes)));
                }
                else
                { 
                    message.setContent(bytes);
                    listener.onSuccess(chc.channel(), message);
                }
                chc.channel().pipeline().remove(this);
                 */
            }
        }
    }

}
