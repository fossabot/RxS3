package pl.codewise.amazon.client.http;

import java.io.IOException;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.pool.ChannelPool;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpUtil;
import pl.codewise.amazon.client.SubscriptionCompletionHandler;

class HttpClientHandler {

    private final ChannelPool channelPool;
    private final SubscriptionCompletionHandler completionHandler;

    private boolean channelReleased;

    HttpClientHandler(ChannelPool channelPool, SubscriptionCompletionHandler completionHandler) {
        this.channelPool = channelPool;
        this.completionHandler = completionHandler;
    }

    void channelRead(ChannelHandlerContext ctx, FullHttpResponse msg) {
        if (!HttpHeaders.isKeepAlive(msg)) {
            ctx.close();
        }

        channelReleased = true;
        channelPool.release(ctx.channel());

        completionHandler.onSuccess(msg);
    }

    void exceptionCaught(Channel channel, Throwable cause) {
        channel.close();

        if (!channelReleased) {
            channelReleased = true;
            channelPool.release(channel);
        }

        completionHandler.onError(cause);
    }

    void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (!channelReleased) {
            channelReleased = true;
            channelPool.release(ctx.channel());
        }

        completionHandler.onError(new IOException("Channel become inactive"));
    }
}
