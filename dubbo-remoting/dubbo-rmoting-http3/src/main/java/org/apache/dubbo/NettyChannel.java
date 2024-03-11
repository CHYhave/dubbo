package org.apache.dubbo;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.transport.AbstractChannel;

import java.net.InetSocketAddress;

/**
 * Created By Have 2024/3/11 22:32
 */
public class NettyChannel extends AbstractChannel {

    public NettyChannel(URL url, ChannelHandler handler) {
        super(url, handler);
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return null;
    }

    @Override
    public boolean isConnected() {
        return false;
    }

    @Override
    public boolean hasAttribute(String key) {
        return false;
    }

    @Override
    public Object getAttribute(String key) {
        return null;
    }

    @Override
    public void setAttribute(String key, Object value) {

    }

    @Override
    public void removeAttribute(String key) {

    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return null;
    }
}
