package io.loglens.web;

import io.loglens.api.ProcessedRecord;
import io.loglens.api.TelemetryEvent;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Bounded typed SSE fanout with independent log and telemetry watermarks. */
public final class EventHub implements AutoCloseable {
    public final class Subscription implements AutoCloseable {
        private final ArrayBlockingQueue<PublishedEvent> queue;
        private final long watermark, telemetryWatermark;
        private volatile boolean gap, closed;
        private Subscription(long watermark,long telemetryWatermark){this.watermark=watermark;this.telemetryWatermark=telemetryWatermark;queue=new ArrayBlockingQueue<>(capacity);}
        public long watermark(){return watermark;}
        public long telemetryWatermark(){return telemetryWatermark;}
        public boolean gap(){return gap;}
        public boolean closed(){return closed;}
        public PublishedEvent pollEvent(long timeoutMillis)throws InterruptedException{return queue.poll(timeoutMillis,TimeUnit.MILLISECONDS);}
        public ProcessedRecord poll(long timeoutMillis)throws InterruptedException{PublishedEvent event=pollEvent(timeoutMillis);return event!=null&&event.payload() instanceof ProcessedRecord record?record:null;}
        @Override public void close(){synchronized(EventHub.this){closed=true;subscribers.remove(this);queue.clear();}}
    }
    private final int capacity,maxClients;
    private final Set<Subscription> subscribers=new HashSet<>();
    private long watermark,telemetryWatermark;
    private boolean closed;
    public EventHub(int capacity,int maxClients,long initialWatermark){this(capacity,maxClients,initialWatermark,0);}
    public EventHub(int capacity,int maxClients,long initialWatermark,long initialTelemetryWatermark){
        if(capacity<1||maxClients<1||initialWatermark<0||initialTelemetryWatermark<0)throw new IllegalArgumentException("invalid hub limits");
        this.capacity=capacity;this.maxClients=maxClients;watermark=initialWatermark;telemetryWatermark=initialTelemetryWatermark;
    }
    public synchronized Subscription subscribe(){if(closed)throw new IllegalStateException("event hub closed");if(subscribers.size()==maxClients)throw new IllegalStateException("SSE client limit reached");Subscription s=new Subscription(watermark,telemetryWatermark);subscribers.add(s);return s;}
    public synchronized void publish(ProcessedRecord record){if(closed)return;if(record.recordSequence()<=watermark)throw new IllegalArgumentException("record sequence must increase");watermark=record.recordSequence();broadcast(new PublishedEvent("record",watermark,record));}
    public synchronized void publishTelemetry(TelemetryEvent event){if(closed)return;if(event.captureSequence()<=telemetryWatermark)throw new IllegalArgumentException("telemetry sequence must increase");telemetryWatermark=event.captureSequence();broadcast(new PublishedEvent("telemetry",telemetryWatermark,event));}
    private void broadcast(PublishedEvent event){var iterator=subscribers.iterator();while(iterator.hasNext()){Subscription s=iterator.next();if(!s.queue.offer(event)){s.gap=true;s.closed=true;s.queue.clear();iterator.remove();}}}
    public synchronized int clients(){return subscribers.size();}
    @Override public synchronized void close(){closed=true;for(Subscription s:subscribers){s.closed=true;s.queue.clear();}subscribers.clear();}
}
