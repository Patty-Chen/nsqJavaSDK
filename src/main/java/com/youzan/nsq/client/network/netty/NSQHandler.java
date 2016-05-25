package com.youzan.nsq.client.network.netty;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.youzan.nsq.client.core.Client;
import com.youzan.nsq.client.core.NSQConnection;
import com.youzan.nsq.client.network.frame.NSQFrame;
import com.youzan.util.IOUtil;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleStateEvent;

public class NSQHandler extends SimpleChannelInboundHandler<NSQFrame> {

    private static final Logger logger = LoggerFactory.getLogger(NSQHandler.class);

    /**
     * 
     * @param ctx
     * @throws Exception
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        destory(ctx.channel());
    }

    /**
     * 
     * @param ctx
     * @param cause
     * @throws Exception
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        super.exceptionCaught(ctx, cause);
        destory(ctx.channel());
    }

    /**
     * 
     * @param ctx
     * @param msg
     */
    @Override
    public void channelRead0(ChannelHandlerContext ctx, NSQFrame msg) {
        final NSQConnection conn = ctx.channel().attr(NSQConnection.STATE).get();
        final Client worker = ctx.channel().attr(Client.STATE).get();
        if (null != conn && null != worker) {
            ctx.channel().eventLoop().execute(() -> {
                try {
                    worker.incoming(msg, conn);
                } catch (Exception e) {
                    logger.error("Exception", e);
                }
            });
        } else {
            if (null == conn) {
                logger.error("No connection set for {}", ctx.channel());
            }
            if (null == worker) {
                logger.error("No worker set for {}", ctx.channel());
            }
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent) {
            destory(ctx.channel());
        }
    }

    /**
     * Do it very very quietly!
     * 
     * @param channel
     */
    private void destory(final Channel channel) {
        if (null == channel) {
            return;
        }
        final NSQConnection conn = channel.attr(NSQConnection.STATE).get();
        if (null != conn) {
            IOUtil.closeQuietly(conn);
        } else {
            try {
                channel.close();
            } catch (Exception e) {
                logger.error("Exception", e);
            }
            logger.error("No connection set for {}", channel);
        }
    }

}
