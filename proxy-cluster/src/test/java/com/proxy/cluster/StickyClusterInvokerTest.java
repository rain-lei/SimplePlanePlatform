package com.proxy.cluster;

import com.proxy.common.filter.Invocation;
import com.proxy.common.filter.Invoker;
import com.proxy.common.filter.Response;
import com.proxy.common.model.ProxyMessage.MessageType;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class StickyClusterInvokerTest {
    private static final class Connection implements Invoker {
        int calls;
        boolean available = true;
        CompletableFuture<Response> connect = CompletableFuture.completedFuture(Response.ok());
        public CompletableFuture<Response> invoke(Invocation invocation) {
            calls++;
            return invocation.getType() == MessageType.CONNECT ? connect
                    : CompletableFuture.completedFuture(Response.ok());
        }
        public boolean isAvailable() { return available; }
        public int getActiveCount() { return 0; }
    }

    private static Invocation message(long id, MessageType type) {
        Invocation invocation = new Invocation("target", 443, null, type);
        invocation.setAttachment("streamId", id);
        return invocation;
    }

    @Test void dataAndDisconnectRetainOriginalConnectionAfterSelectionChanges() {
        StickyClusterInvoker cluster = new StickyClusterInvoker();
        Connection a = new Connection(), b = new Connection();
        cluster.setInvokers(Arrays.asList(a, b));
        assertTrue(cluster.invoke(message(1, MessageType.CONNECT)).join().isSuccess());
        cluster.setInvokers(Collections.singletonList(b));
        cluster.invoke(message(1, MessageType.DATA)).join();
        cluster.invoke(message(1, MessageType.DISCONNECT)).join();
        assertEquals(3, a.calls);
        assertEquals(0, b.calls);
        assertEquals(0, cluster.getBindingCount());
    }

    @Test void unavailableBindingNeverSendsDataToAnotherConnection() {
        StickyClusterInvoker cluster = new StickyClusterInvoker();
        Connection a = new Connection(), b = new Connection();
        cluster.setInvokers(Arrays.asList(a, b));
        cluster.invoke(message(1, MessageType.CONNECT)).join();
        a.available = false;
        cluster.invoke(message(1, MessageType.DATA)).join();
        assertEquals(2, a.calls);
        assertEquals(0, b.calls);
        assertFalse(cluster.invoke(message(2, MessageType.DATA)).join().isSuccess());
    }

    @Test void cancelledConnectCannotBeActivatedByLateSuccess() {
        StickyClusterInvoker cluster = new StickyClusterInvoker();
        Connection a = new Connection();
        a.connect = new CompletableFuture<>();
        cluster.setInvokers(Collections.singletonList(a));
        CompletableFuture<Response> connecting = cluster.invoke(message(1, MessageType.CONNECT));
        assertFalse(cluster.invoke(message(1, MessageType.DATA)).join().isSuccess());
        cluster.unbind(1);
        a.connect.complete(Response.ok());
        assertFalse(connecting.join().isSuccess());
        assertEquals(0, cluster.getBindingCount());
    }

    @Test void failedAndDuplicateConnectDoNotLeakBindings() {
        StickyClusterInvoker cluster = new StickyClusterInvoker();
        Connection a = new Connection();
        a.connect = new CompletableFuture<>();
        cluster.setInvokers(Collections.singletonList(a));
        CompletableFuture<Response> connecting = cluster.invoke(message(1, MessageType.CONNECT));
        assertFalse(cluster.invoke(message(1, MessageType.CONNECT)).join().isSuccess());
        a.connect.complete(Response.error("Target refused"));
        assertFalse(connecting.join().isSuccess());
        assertEquals(0, cluster.getBindingCount());
        assertEquals(1, a.calls);
    }

    @Test void retiringBindingsCleansThroughOldConnectionAndInvalidatesLateConnect() {
        StickyClusterInvoker cluster = new StickyClusterInvoker();
        Connection a = new Connection(), b = new Connection();
        a.connect = new CompletableFuture<>();
        cluster.setInvokers(Collections.singletonList(a));
        CompletableFuture<Response> connecting = cluster.invoke(message(1, MessageType.CONNECT));
        cluster.setInvokers(Collections.singletonList(b));
        cluster.retireBindings();
        assertEquals(2, a.calls);
        assertEquals(0, b.calls);
        assertEquals(0, cluster.getBindingCount());
        a.connect.complete(Response.ok());
        assertFalse(connecting.join().isSuccess());
        cluster.retireBindings();
        assertEquals(2, a.calls);
    }
}
