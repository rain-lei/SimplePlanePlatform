package io.loglens.proxyremote;
import com.proxy.remote.outbound.OutboundSession;
import com.proxy.remote.outbound.SessionManager;
import com.proxy.transport.netty.NettyServer$Fixture;
import io.netty.util.concurrent.GenericFutureListener;
import java.util.List;
public final class ProxyRemotePremainFixture {
    public static final class ChannelId { private final String id; public ChannelId(String id){this.id=id;} public String asLongText(){return id;} }
    public static final class Pipeline { public List<String> names(){return List.of("frameCodec","multiplexHandler");} }
    public static final class CloseFuture {
        private GenericFutureListener<Object> listener;
        public CloseFuture addListener(GenericFutureListener<Object> listener){this.listener=listener;return this;}
        public void fire() throws Exception {if(listener!=null)listener.operationComplete(this);}
    }
    public static final class Channel {
        private final String id; private final Channel parent; private final boolean active; private final CloseFuture close=new CloseFuture();
        public Channel(String id,Channel parent,boolean active){this.id=id;this.parent=parent;this.active=active;}
        public ChannelId id(){return new ChannelId(id);} public String remoteAddress(){return "/127.0.0.1:5312";}
        public Pipeline pipeline(){return new Pipeline();} public Channel parent(){return parent;} public CloseFuture closeFuture(){return close;} public boolean isActive(){return active;}
    }
    public static final class Context { private final Channel channel; public Context(Channel channel){this.channel=channel;} public Channel channel(){return channel;} }
    public static void main(String[] args) throws Exception {
        Channel physical=new Channel("parent-1",null,true); new NettyServer$Fixture().initChannel(physical);
        Channel stream=new Channel("stream-7",physical,true), outbound=new Channel("outbound",null,true);
        OutboundSession session=new OutboundSession("parent-1:7",new Context(stream),"example.test",443,7);
        SessionManager sessions=new SessionManager(); sessions.register("parent-1:7",session); session.setOutboundChannel(outbound);
        session.forward(new byte[12]); session.writeBack(new byte[7]); Thread.sleep(150);
        sessions.remove("parent-1:7"); physical.closeFuture().fire(); Thread.sleep(300);
    }
}