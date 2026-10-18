package com.proxy.remote.outbound;
import java.util.concurrent.ConcurrentHashMap;
public class SessionManager {
    private final ConcurrentHashMap<String, OutboundSession> sessions=new ConcurrentHashMap<>();
    public void register(String id, OutboundSession session) { sessions.put(id,session); }
    public OutboundSession remove(String id) { OutboundSession value=sessions.remove(id); if(value!=null)value.close(); return value; }
}