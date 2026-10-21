package com.proxy.remote.outbound;
import io.loglens.proxyremote.ProxyRemotePremainFixture.Channel;
import io.loglens.proxyremote.ProxyRemotePremainFixture.Context;
public class OutboundSession {
    public enum State { CONNECTING, ACTIVE, CLOSED }
    private final String key; private final Object inboundCtx; private final String host; private final int port; private final long stream;
    private Object outbound; private State state=State.CONNECTING;
    public OutboundSession(String key,Object inboundCtx,String host,int port,long stream){this.key=key;this.inboundCtx=inboundCtx;this.host=host;this.port=port;this.stream=stream;}
    public void setOutboundChannel(Object channel){outbound=channel;state=State.ACTIVE;}
    public void forward(byte[] data){if(state==State.ACTIVE&&((Channel)outbound).isActive()){} }
    public void writeBack(byte[] data){if(state!=State.CLOSED&&((Context)inboundCtx).channel().isActive()){} }
    public void close(){state=State.CLOSED;}
    public State getState(){return state;} public Object getOutboundChannel(){return outbound;} public Object getInboundCtx(){return inboundCtx;}
    public String getSessionKey(){return key;} public long getRawStreamId(){return stream;} public String getTargetHost(){return host;} public int getTargetPort(){return port;}
}