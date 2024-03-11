package org.apache.dubbo;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;

import io.netty.channel.socket.DatagramChannel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.incubator.codec.http3.Http3;
import io.netty.incubator.codec.http3.Http3ClientConnectionHandler;
import io.netty.incubator.codec.quic.Quic;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicChannelBootstrap;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;

import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.Future;

import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.GlobalEventExecutor;

import io.netty.util.concurrent.Promise;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.logger.ErrorTypeAwareLogger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.api.connection.AbstractConnectionClient;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.TRANSPORT_CLIENT_CONNECT_TIMEOUT;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.TRANSPORT_FAILED_CLOSE;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.TRANSPORT_FAILED_CONNECT_PROVIDER;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.TRANSPORT_FAILED_RECONNECT;
import static org.apache.dubbo.remoting.Constants.EVENT_LOOP_BOSS_POOL_NAME;

/**
 * Created By Have 2024/3/11 22:34
 */
public class NettyHttp3Client extends AbstractConnectionClient {
    private static final ErrorTypeAwareLogger logger = LoggerFactory.getErrorTypeAwareLogger(NettyHttp3Client.class);

    private EventLoopGroup eventLoopGroup;
    private io.netty.channel.Channel channel;
    private Bootstrap bootstrap;
    private QuicChannelBootstrap quicChannelBootstrap;
    private final int clientShutdownTimeoutMills;
    private ConnectionListener connectionListener;

    private AtomicReference<Promise<Object>> connectingPromise;

    protected NettyHttp3Client(URL url, ChannelHandler handler) throws RemotingException {
        super(url, handler);
        clientShutdownTimeoutMills = ConfigurationUtils.getClientShutdownTimeout(getUrl().getOrDefaultModuleModel());
    }

    @Override
    protected void initConnectionClient() {

    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public void createConnectingPromise() {
        connectingPromise.compareAndSet(null, new DefaultPromise<>(GlobalEventExecutor.INSTANCE));
    }

    @Override
    public void addCloseListener(Runnable func) {

    }

    @Override
    public void onConnected(Object channel) {

    }

    @Override
    public void onGoaway(Object channel) {

    }

    @Override
    public void destroy() {

    }

    @Override
    public Object getChannel(Boolean generalizable) {
        return null;
    }

    @Override
    protected void doOpen() throws Throwable {
        this.connectingPromise = new AtomicReference<>();
        initBootstrap();
    }

    private void initBootstrap() {
        QuicSslContext sslContext = QuicSslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .applicationProtocols(Http3.supportedApplicationProtocols())
                .build();
        io.netty.channel.ChannelHandler codec = Http3.newQuicClientCodecBuilder()
                .sslContext(sslContext)
                .build();
        eventLoopGroup = NettyEventLoopFactory.eventLoopGroup(1, EVENT_LOOP_BOSS_POOL_NAME);
        bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup)
                .channel(NettyEventLoopFactory.datagramChannelClass())
                .handler(codec)
                .bind(0);

        try {
            ChannelFuture channelFuture = bootstrap.bind(0);
            channelFuture.syncUninterruptibly();
            channel = channelFuture.channel();
        } catch (Throwable t) {
            closeBootstrap();
            throw t;
        }

        quicChannelBootstrap = QuicChannel.newBootstrap(channel)
                .handler(new ChannelInitializer<QuicChannel>() {
                    @Override
                    protected void initChannel(QuicChannel channel) throws Exception {
                        ChannelPipeline pipeline = channel.pipeline();
                        pipeline.addLast(new Http3ClientConnectionHandler());
                    }
                }).remoteAddress(getConnectAddress());
    }

    private void closeBootstrap() {
        try {
            if (bootstrap != null) {
                long timeout = ConfigurationUtils.reCalShutdownTime(clientShutdownTimeoutMills);
                long quietPeriod = Math.min(2000L, timeout);
                Future<?> bossGroupShutdownFuture = eventLoopGroup.shutdownGracefully(quietPeriod, timeout, MILLISECONDS);
                bossGroupShutdownFuture.syncUninterruptibly();
            }
        } catch (Throwable e) {
            logger.warn(TRANSPORT_FAILED_CLOSE, "", "", e.getMessage(), e);
        }
    }

    @Override
    protected void doClose() throws Throwable {
        // AbstractPeer close can set closed true.
        if (isClosed()) {
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("Connection:%s freed ", this));
            }
            if (channel != null) {
                channel.close();
            }
        }
    }

    @Override
    protected void doConnect() throws RemotingException {
        if (isClosed()) {
            if (logger.isDebugEnabled()) {
                logger.debug(
                        String.format("%s aborted to reconnect cause connection closed. ", NettyHttp3Client.this));
            }
        }
        init.compareAndSet(false, true);
        long start = System.currentTimeMillis();
        createConnectingPromise();
        final Future<QuicChannel> promise = quicChannelBootstrap.connect();
        promise.addListener(connectionListener);
        boolean ret = connectingPromise.get().awaitUninterruptibly(getConnectTimeout(), TimeUnit.MILLISECONDS);
        // destroy connectingPromise after used
        synchronized (this) {
            connectingPromise.set(null);
        }
        if (promise.cause() != null) {
            Throwable cause = promise.cause();

            // 6-1 Failed to connect to provider server by other reason.
            RemotingException remotingException = new RemotingException(
                    this,
                    "client(url: " + getUrl() + ") failed to connect to server " + getConnectAddress()
                            + ", error message is:" + cause.getMessage(),
                    cause);

            logger.error(
                    TRANSPORT_FAILED_CONNECT_PROVIDER,
                    "network disconnected",
                    "",
                    "Failed to connect to provider server by other reason.",
                    cause);

            throw remotingException;
        } else if (!ret || !promise.isSuccess()) {
            // 6-2 Client-side timeout
            RemotingException remotingException = new RemotingException(
                    this,
                    "client(url: " + getUrl() + ") failed to connect to server "
                            + getConnectAddress() + " client-side timeout "
                            + getConnectTimeout() + "ms (elapsed: " + (System.currentTimeMillis() - start)
                            + "ms) from netty client "
                            + NetUtils.getLocalHost() + " using dubbo version " + Version.getVersion());

            logger.error(
                    TRANSPORT_CLIENT_CONNECT_TIMEOUT, "provider crash", "", "Client-side timeout.", remotingException);

            throw remotingException;
        }
    }

    @Override
    protected void doDisConnect() throws Throwable {

    }

    @Override
    protected Channel getChannel() {
        return null;
    }

    class ConnectionListener implements GenericFutureListener<Future<QuicChannel>> {

        @Override
        public void operationComplete(Future<QuicChannel> future) {
            if (future.isSuccess()) {
                return;
            }
            final NettyHttp3Client connectionClient = NettyHttp3Client.this;
            if (connectionClient.isClosed() || connectionClient.getCounter() == 0) {
                if (logger.isDebugEnabled()) {
                    logger.debug(String.format(
                            "%s aborted to reconnect. %s",
                            connectionClient, future.cause().getMessage()));
                }
                return;
            }
            if (logger.isDebugEnabled()) {
                logger.debug(String.format(
                        "%s is reconnecting, attempt=%d cause=%s",
                        connectionClient, 0, future.cause().getMessage()));
            }
            final EventLoop loop = channel.eventLoop();
            loop.schedule(
                    () -> {
                        try {
                            connectionClient.doConnect();
                        } catch (RemotingException e) {
                            logger.error(
                                    TRANSPORT_FAILED_RECONNECT,
                                    "",
                                    "",
                                    "Failed to connect to server: " + getConnectAddress());
                        }
                    },
                    1L,
                    TimeUnit.SECONDS);
        }
    }
}
