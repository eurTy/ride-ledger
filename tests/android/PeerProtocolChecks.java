package com.rideledger;

import android.Manifest;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Tests production stream protocol and routing. It does not impersonate physical Bluetooth hardware. */
final class PeerProtocolChecks {
    interface Case { void run() throws Exception; }
    interface Sink { void check(String name, Case action); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }

    static void run(Context base, JSONObject fixture, Sink sink) throws Exception {
        String invite = fixture.getString("inviteA"), trip = fixture.getJSONObject("trip").getString("id");
        JSONArray original = fixture.getJSONArray("events");
        sink.check("peer v2: fragmented streams merge two Android SQLite replicas with durable receipts", () -> {
            try (Env a = new Env(base, invite, "a"); Env b = new Env(base, invite, "b")) {
                for (int i = 0; i < original.length(); i++) (i % 2 == 0 ? a : b).store.merge(trip, new JSONArray().put(original.getJSONObject(i)));
                Pair pair = pair(a, b, true, "", null);
                require(pair.ok() && pair.a.durable && pair.b.durable, "durable session failed: " + pair.errors);
                require(a.store.events(trip).size() == original.length() && b.store.events(trip).size() == original.length(), "records did not converge");
                require(a.store.getTrip(trip).localPerson.equals("a") && b.store.getTrip(trip).localPerson.equals("b"), "identity changed");
                Pair repeated = pair(b, a, true, "", null);
                require(repeated.ok() && repeated.a.sent == 0 && repeated.b.sent == 0, "matching roots retransmitted records");
                require(repeated.a.bytesWritten < pair.a.bytesWritten + pair.b.bytesWritten, "root-only cycle did not shrink");
            }
        });
        sink.check("peer v2: only a new record is sent after initial synchronization", () -> {
            try (Env a = new Env(base, invite, "a"); Env b = new Env(base, invite, "b")) {
                a.store.merge(trip, original); require(pair(a,b,true,"",null).ok(), "initial session failed");
                add(a, trip, "a", "new delta");
                Pair next = pair(a,b,true,"",null);
                require(next.ok() && next.a.sent == 1 && next.b.sent == 0, "a full ledger was resent instead of one record");
            }
        });
        sink.check("peer v2: interrupted second batch keeps committed data and resumes missing records", () -> {
            try (Env a = new Env(base, invite, "a"); Env b = new Env(base, invite, "b")) {
                String note = repeat("虚构分段记录", 350);
                for (int i = 0; i < 80; i++) add(a, trip, "a", note + i);
                add(b, trip, "b", "other direction");
                Pair broken = pair(a,b,true,"drop-second-delta",null);
                int persisted = b.store.events(trip).size();
                require(!broken.ok() && persisted > 1 && persisted < 81, "partial batch test did not interrupt after a commit");
                b.reopen();
                Pair resumed = pair(a,b,true,"",null);
                require(resumed.ok() && resumed.a.sent == 81 - persisted, "resume did not send only missing operations");
                require(a.store.events(trip).size() == 81 && b.store.events(trip).size() == 81, "restart lost or duplicated money");
            }
        });
        sink.check("peer v2: lost final confirmation retries without duplicate money", () -> {
            try (Env a = new Env(base, invite, "a"); Env b = new Env(base, invite, "b")) {
                add(a,trip,"a","lost confirmation A"); add(b,trip,"b","lost confirmation B");
                Pair broken = pair(a,b,true,"drop-confirm",null);
                require(!broken.ok() && !broken.b.complete, "receiver claimed an unseen confirmation");
                require(a.store.events(trip).size() == 2 && b.store.events(trip).size() == 2, "committed records were lost");
                Pair retry = pair(a,b,true,"",null);
                require(retry.ok() && retry.a.sent == 0 && retry.b.sent == 0, "retry duplicated or resent committed records");
            }
        });
        sink.check("peer v2: corrupted ciphertext cannot be acknowledged or change local cash", () -> {
            try (Env a = new Env(base, invite, "a"); Env b = new Env(base, invite, "b")) {
                add(a,trip,"a","corrupted radio frame");
                Pair broken = pair(a,b,true,"corrupt-delta",null);
                require(!broken.a.complete && b.store.events(trip).isEmpty(), "corrupt encrypted data was accepted");
                require(pair(a,b,true,"",null).ok() && b.store.events(trip).size() == 1, "valid retry did not recover");
            }
        });
        sink.check("peer v2: forged save receipt does not mark the sender synchronized", () -> {
            try (Env a = new Env(base, invite, "a"); Env b = new Env(base, invite, "b")) {
                add(a,trip,"a","forged receipt");
                Pair broken = pair(a,b,true,"forge-receipt",null);
                require(!broken.a.complete, "forged acknowledgement was trusted");
                require(pair(a,b,true,"",null).ok() && b.store.events(trip).size() == 1, "receipt retry did not recover");
            }
        });
        sink.check("peer v2: new local record during exchange is sent by the next session", () -> {
            try (Env a = new Env(base, invite, "a"); Env b = new Env(base, invite, "b")) {
                add(a,trip,"a","before exchange"); AtomicBoolean once = new AtomicBoolean();
                Pair first = pair(a,b,true,"",() -> { if (once.compareAndSet(false,true)) add(b,trip,"b","during exchange"); });
                require(first.ok() && b.store.events(trip).size() == 2 && a.store.events(trip).size() == 1, "snapshot race was not exercised");
                Pair next = pair(a,b,true,"",null);
                require(next.ok() && next.b.sent == 1 && a.store.events(trip).size() == 2, "new concurrent record was skipped");
            }
        });
        sink.check("peer v2: deletion, clock skew, cross-channel replay and reopening preserve one ledger", () -> {
            try (Env a = new Env(base, invite, "a"); Env b = new Env(base, invite, "b")) {
                a.store.merge(trip, original); require(pair(a,b,true,"",null).ok(), "initial failed");
                LedgerStore.Event target = a.store.add(trip, "expense", expense("a","later deleted"));
                require(pair(a,b,true,"",null).ok(), "new entry failed");
                b.store.add(trip,"delete",new JSONObject().put("target",target.id));
                require(pair(b,a,true,"",null).ok(), "reverse route failed");
                a.reopen(); b.reopen(); require(pair(a,b,true,"",null).ok(), "reopen failed");
                require(a.store.events(trip).size() == original.length()+2 && b.store.events(trip).size()==original.length()+2,
                        "replay duplicated records");
                require(a.store.summary(trip).visible.size()==b.store.summary(trip).visible.size() &&
                        a.store.summary(trip).visible.size()==original.length()-2,
                        "deleted records returned");
            }
        });
        sink.check("peer v2: wrong invitation key cannot authenticate a nearby device", () -> {
            try (Env a = new Env(base, invite, "a"); Env b = new Env(base, fixture.getString("wrongKeyInvite"), "b")) {
                add(a,trip,"a","private"); Pair pair = pair(a,b,true,"",null);
                require(!pair.a.authenticated && !pair.b.authenticated && b.store.events(trip).isEmpty(), "wrong-key peer authenticated");
            }
        });
        sink.check("strict peer merge: conflicting IDs roll back the whole batch", () -> {
            try (Env a = new Env(base, invite, "a")) {
                LedgerStore.Event entry = a.store.add(trip,"expense",expense("a","original"));
                JSONObject conflict = entry.json(); conflict.getJSONObject("data").put("amount",999);
                JSONObject fresh = new LedgerStore.Event(UUID.randomUUID().toString(),trip,"expense",0,expense("b","should roll back")).json();
                boolean rejected=false;
                try { a.store.mergeStrict(trip,new JSONArray().put(fresh).put(conflict)); } catch(Exception expected){rejected=true;}
                require(rejected && a.store.events(trip).size()==1 && a.store.summary(trip).spent.get("CNY")==100L, "conflict partially committed");
            }
        });
        sink.check("peer v1 negotiation upgrades an unknown manual endpoint without changing records", () -> {
            try (Env a = new Env(base, invite, "a"); Env b = new Env(base, invite, "b")) {
                add(a,trip,"a","legacy negotiation A"); add(b,trip,"b","legacy negotiation B");
                Pair first = pair(a,b,false,"",null);
                require(first.ok() && first.a.modern && first.a.durable && first.b.durable, "v1 capability negotiation failed: "+first.errors);
                require(a.store.events(trip).size()==2 && b.store.events(trip).size()==2,"legacy data changed");
                Pair second = pair(a,b,true,"",null); require(second.ok() && second.a.sent==0 && second.b.sent==0,"upgrade resent history");
            }
        });
        sink.check("routes: Wi-Fi is preferred, failures switch to Bluetooth, and recovered Wi-Fi wins", () -> {
            AtomicLong clock = new AtomicLong(1000); PeerRoutePolicy policy = new PeerRoutePolicy(clock::get);
            String peer=UUID.randomUUID().toString();
            PeerRoutePolicy.Endpoint wifi=endpoint("wifi:1",PeerRoutePolicy.Channel.WIFI,peer), bt=endpoint("bluetooth:1",PeerRoutePolicy.Channel.BLUETOOTH,peer);
            policy.offer(wifi); policy.offer(bt);
            require(policy.ready(true).get(0).channel==PeerRoutePolicy.Channel.WIFI,"Wi-Fi not preferred");
            require(policy.ready(true).isEmpty(),"same peer got a second outgoing session");
            policy.failure(wifi.key);
            require(policy.ready(true).get(0).channel==PeerRoutePolicy.Channel.BLUETOOTH,"no automatic fallback");
            policy.success(bt.key); require(policy.ready(false).isEmpty(),"idle radio was polled continuously");
            clock.addAndGet(6000);
            require(policy.ready(true).get(0).channel==PeerRoutePolicy.Channel.WIFI,"Wi-Fi did not recover");
            policy.success(wifi.key);
        });
        sink.check("routes: failure backoff survives repeated discovery and a changed hotspot address retries", () -> {
            AtomicLong clock=new AtomicLong(1000); PeerRoutePolicy policy=new PeerRoutePolicy(clock::get);
            String peer=UUID.randomUUID().toString(); PeerRoutePolicy.Endpoint wifi=endpoint("wifi:old",PeerRoutePolicy.Channel.WIFI,peer);
            policy.offer(wifi); policy.ready(true); policy.failure(wifi.key); policy.offer(wifi);
            require(policy.ready(true).isEmpty(),"discovery reset failure backoff");
            policy.offer(endpoint("wifi:new",PeerRoutePolicy.Channel.WIFI,peer));
            require(policy.ready(true).get(0).key.equals("wifi:new"),"new hotspot address was not tried");
        });
        sink.check("coordinator: failed hotspot automatically uses Bluetooth and a new hotspot takes over", () -> {
            // Inject ordered streams for both radios; scheduling, protocol and SQLite are production code.
            // This is intentionally not a claim about physical Bluetooth radio behavior.
            try (Env a = new Env(base, invite, "a"); Env b = new Env(base, invite, "b");
                 ServerSocket server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))) {
                ExecutorService receiver = Executors.newSingleThreadExecutor();
                List<Socket> sockets = new CopyOnWriteArrayList<>();
                AtomicBoolean listening = new AtomicBoolean(true);
                List<String> statuses = new CopyOnWriteArrayList<>();
                AtomicInteger wifiDials = new AtomicInteger(), bluetoothDials = new AtomicInteger();
                PeerSync manager = new PeerSync(a.context, a.store, added -> {}, false);
                Future<?> receiving = receiver.submit(() -> {
                    while (listening.get()) try (Socket socket = server.accept()) {
                        sockets.add(socket); socket.setSoTimeout(5000);
                        new PeerProtocol(b.store, b.id, new PeerProtocol.Listener() {
                            public void authenticated(String id) {}
                            public void stored(int count) {}
                        }).accept(new PeerWire(socket.getInputStream(), socket.getOutputStream(), () -> {}));
                    } catch (Exception error) { if (listening.get()) statuses.add("receiver: " + error.getClass().getSimpleName()); }
                });
                try {
                    add(a, trip, "a", "offline before either radio connected");
                    manager.start();
                    manager.offer(new PeerRoutePolicy.Endpoint("wifi:unreachable", PeerRoutePolicy.Channel.WIFI, b.id, true,
                            () -> { wifiDials.incrementAndGet(); throw new IOException("hotspot lost"); }));
                    manager.offer(new PeerRoutePolicy.Endpoint("bluetooth:FA:CE:00:00:00:01", PeerRoutePolicy.Channel.BLUETOOTH, b.id, true,
                            () -> { bluetoothDials.incrementAndGet(); return dial(server.getLocalPort()); }));
                    await(() -> b.store.events(trip).size() == 1 && manager.status().startsWith("蓝牙已同步"), 12000,
                            "coordinator did not switch after hotspot failure: " + statuses);
                    require(wifiDials.get() > 0 && bluetoothDials.get() > 0, "fallback did not exercise both channel adapters");
                    add(a, trip, "a", "written before hotspot recovery"); manager.syncNow();
                    manager.offer(new PeerRoutePolicy.Endpoint("wifi:recovered-address", PeerRoutePolicy.Channel.WIFI, b.id, true,
                            () -> { wifiDials.incrementAndGet(); return dial(server.getLocalPort()); }));
                    await(() -> b.store.events(trip).size() == 2 && manager.status().startsWith("热点已同步"), 12000,
                            "recovered hotspot did not take over: " + statuses);
                    require(a.store.events(trip).size() == 2 && b.store.events(trip).size() == 2,
                            "cross-channel handover changed money");
                } finally {
                    manager.stop(); listening.set(false); server.close();
                    for (Socket socket : sockets) try { socket.close(); } catch (Exception ignored) {}
                    receiving.get(5, TimeUnit.SECONDS); receiver.shutdownNow();
                }
            }
        });
        sink.check("watchdog: stalled reads and writes close the channel and release workers", () -> {
            for (boolean writing : new boolean[]{false, true}) {
                CountDownLatch closed = new CountDownLatch(1);
                AtomicInteger closes = new AtomicInteger();
                InputStream blockedRead = new InputStream() {
                    public int read() throws IOException { blockUntilClosed(closed); return -1; }
                };
                OutputStream blockedWrite = new OutputStream() {
                    public void write(int value) throws IOException { blockUntilClosed(closed); }
                    public void write(byte[] bytes, int offset, int count) throws IOException { blockUntilClosed(closed); }
                };
                PeerConnection connection = new PeerConnection(blockedRead, blockedWrite, () -> {
                    closes.incrementAndGet(); closed.countDown();
                }, null);
                ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
                ExecutorService worker = Executors.newSingleThreadExecutor();
                try {
                    connection.watch(watchdog, 100, 3000);
                    Future<?> waiting = worker.submit(() -> {
                        try {
                            if (writing) connection.wire().write(new JSONObject().put("test", true));
                            else connection.wire().read();
                        } catch (Exception expected) { return; }
                        throw new AssertionError("stalled stream unexpectedly completed");
                    });
                    waiting.get(4, TimeUnit.SECONDS); connection.close();
                    require(closes.get() == 1, "watchdog did not close blocked transport exactly once");
                } finally { connection.close(); worker.shutdownNow(); watchdog.shutdownNow(); }
            }
        });
        sink.check("hotspot discovery: UDP request/reply discovers both peers without an echo loop", () -> {
            String aId = UUID.randomUUID().toString(), bId = UUID.randomUUID().toString();
            AtomicInteger aFound = new AtomicInteger(), bFound = new AtomicInteger();
            List<String> peers = new CopyOnWriteArrayList<>();
            HotspotDiscovery a = new HotspotDiscovery(null, aId, 12345, 0, true, (address, id) -> {
                peers.add(id + ":" + address.getPort()); aFound.incrementAndGet();
            });
            HotspotDiscovery b = new HotspotDiscovery(null, bId, 23456, 0, true, (address, id) -> {
                peers.add(id + ":" + address.getPort()); bFound.incrementAndGet();
            });
            try {
                a.start(); b.start(); require(a.port() > 0 && b.port() > 0, "UDP bind failed");
                a.probe(new java.net.InetSocketAddress("127.0.0.1", b.port()));
                await(() -> aFound.get() == 1 && bFound.get() == 1, 3000, "UDP discovery did not reply");
                require(peers.contains(aId + ":12345") && peers.contains(bId + ":23456"), "wrong TCP endpoint advertised");
                try (java.net.DatagramSocket invalid = new java.net.DatagramSocket()) {
                    byte[] bytes = "{\"app\":\"other-application\",\"version\":2}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    invalid.send(new java.net.DatagramPacket(bytes, bytes.length, java.net.InetAddress.getByName("127.0.0.1"), a.port()));
                }
                Thread.sleep(200);
                require(aFound.get() == 1 && bFound.get() == 1, "invalid UDP data or replies created more discoveries");
                a.stop(); a.start(); require(a.port() > 0, "UDP channel did not reopen");
            } finally { a.stop(); b.stop(); }
        });
        sink.check("Bluetooth permissions: Android 10/11 and Android 12+ select supported permission paths", () -> {
            require(BluetoothSync.usePermissions(29).length==0 && BluetoothSync.usePermissions(30).length==0,"legacy normal permission changed");
            require(Arrays.asList(BluetoothSync.scanPermissions(29)).contains(Manifest.permission.ACCESS_FINE_LOCATION),"Android 10 scan lacks location permission");
            require(Arrays.asList(BluetoothSync.usePermissions(31)).contains(Manifest.permission.BLUETOOTH_CONNECT),"new connect permission missing");
            require(Arrays.asList(BluetoothSync.scanPermissions(35)).contains(Manifest.permission.BLUETOOTH_SCAN),"new scan permission missing");
            require(Arrays.asList(BluetoothSync.discoverablePermissions(35)).contains(Manifest.permission.BLUETOOTH_ADVERTISE),"advertise permission missing");
        });
        sink.check("peer identity survives reopening and reuses existing cloud identity", () -> {
            Ctx context = new Ctx(base); try {
                String first=PeerIdentity.deviceId(context);
                require(first.equals(PeerIdentity.deviceId(context)),"device ID changed");
                require(first.equals(context.getSharedPreferences("cloud",0).getString("device_id","")),"cloud ID diverged");
            } finally { context.cleanup(); }
        });
    }

    private static PeerRoutePolicy.Endpoint endpoint(String key, PeerRoutePolicy.Channel channel, String peer) {
        return new PeerRoutePolicy.Endpoint(key,channel,peer,true,()->{throw new IOException("not used by policy test");});
    }
    private interface Condition { boolean ready() throws Exception; }
    private static void await(Condition condition, long timeout, String message) throws Exception {
        long until = android.os.SystemClock.elapsedRealtime() + timeout;
        while (!condition.ready()) {
            if (android.os.SystemClock.elapsedRealtime() >= until) throw new AssertionError(message);
            Thread.sleep(50);
        }
    }
    private static void blockUntilClosed(CountDownLatch latch) throws IOException {
        try { latch.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        throw new IOException("transport closed");
    }
    private static PeerConnection dial(int port) throws Exception {
        Socket socket = new Socket("127.0.0.1", port); socket.setSoTimeout(5000);
        return new PeerConnection(socket.getInputStream(), socket.getOutputStream(), socket, null);
    }
    private static JSONObject expense(String payer,String note) throws Exception {
        return new JSONObject().put("payer",payer).put("currency","CNY").put("amount",100).put("shareA",5000).put("mode","shared").put("note",note);
    }
    private static void add(Env env,String trip,String payer,String note) throws Exception {env.store.add(trip,"expense",expense(payer,note));}
    private static String repeat(String value,int count){StringBuilder result=new StringBuilder();for(int i=0;i<count;i++)result.append(value);return result.toString();}
    private static final class Pair {
        PeerProtocol.Result a,b; final List<String> errors=new CopyOnWriteArrayList<>();
        boolean ok(){return errors.isEmpty()&&a!=null&&b!=null&&a.complete&&b.complete;}
    }
    private static Pair pair(Env a,Env b,boolean modern,String fault,Case afterServerStore) throws Exception {
        Pair pair=new Pair(); ExecutorService pool=Executors.newFixedThreadPool(2);
        List<Socket> sockets=new CopyOnWriteArrayList<>();
        try(ServerSocket server=new ServerSocket(0,2,InetAddress.getByName("127.0.0.1"))){
            server.setSoTimeout(5000);
            Future<?> receive=pool.submit(()->{
                PeerProtocol protocol=null;
                try(Socket socket=server.accept()){
                    sockets.add(socket);socket.setSoTimeout(5000);
                    protocol=new PeerProtocol(b.store,b.id,new PeerProtocol.Listener(){
                        public void authenticated(String id){}
                        public void stored(int count){if(afterServerStore!=null)try{afterServerStore.run();}catch(Exception error){throw new RuntimeException(error);}}
                    });
                    OutputStream out=new FaultOutput(socket,"forge-receipt".equals(fault)?fault:"");
                    pair.b=protocol.accept(new PeerWire(fragment(socket.getInputStream()),out,()->{}));
                }catch(Exception error){pair.errors.add("receiver: "+error.getClass().getSimpleName());}
                finally{if(pair.b==null&&protocol!=null)pair.b=protocol.progress();}
            });
            Future<?> send=pool.submit(()->{
                PeerProtocol protocol=null;
                try(Socket socket=new Socket("127.0.0.1",server.getLocalPort())){
                    sockets.add(socket);socket.setSoTimeout(5000);
                    protocol=new PeerProtocol(a.store,a.id,new PeerProtocol.Listener(){public void authenticated(String id){}public void stored(int count){}});
                    pair.a=protocol.exchange(new PeerWire(fragment(socket.getInputStream()),new FaultOutput(socket,"forge-receipt".equals(fault)?"":fault),()->{}),modern);
                }catch(Exception error){pair.errors.add("sender: "+error.getClass().getSimpleName());}
                finally{if(pair.a==null&&protocol!=null)pair.a=protocol.progress();}
            });
            send.get(25,TimeUnit.SECONDS);receive.get(25,TimeUnit.SECONDS);
            return pair;
        }finally{for(Socket socket:sockets)try{socket.close();}catch(Exception ignored){}pool.shutdownNow();}
    }
    private static InputStream fragment(InputStream stream){return new FilterInputStream(stream){
        @Override public int read(byte[] data,int offset,int count)throws IOException{return super.read(data,offset,Math.min(37,count));}
    };}
    private static final class FaultOutput extends OutputStream {
        private final Socket socket; private final OutputStream out; private final String mode;
        private final ByteArrayOutputStream buffered=new ByteArrayOutputStream(); private int deltas;
        FaultOutput(Socket socket,String mode)throws Exception{this.socket=socket;out=socket.getOutputStream();this.mode=mode;}
        @Override public void write(int value)throws IOException{write(new byte[]{(byte)value});}
        @Override public void write(byte[] data,int offset,int length)throws IOException{
            buffered.write(data,offset,length);
            byte[] bytes=buffered.toByteArray();if(bytes.length<4)return;
            int size=((bytes[0]&255)<<24)|((bytes[1]&255)<<16)|((bytes[2]&255)<<8)|(bytes[3]&255);
            if(bytes.length<4+size)return;
            try{
                JSONObject value=new JSONObject(new String(bytes,4,size,java.nio.charset.StandardCharsets.UTF_8));
                String kind=value.optString("kind");if("delta".equals(kind))deltas++;
                if((mode.equals("drop-confirm")&&kind.equals("confirm"))||(mode.equals("drop-second-delta")&&kind.equals("delta")&&deltas==2)){
                    if(kind.equals("delta"))out.write(bytes,0,bytes.length/2);
                    socket.close();throw new IOException("simulated interrupted stream");
                }
                if(mode.equals("corrupt-delta")&&kind.equals("delta")&&deltas==1){
                    JSONObject bundle=value.getJSONObject("bundle");byte[] encrypted=android.util.Base64.decode(bundle.getString("data"),android.util.Base64.URL_SAFE|android.util.Base64.NO_WRAP);
                    encrypted[encrypted.length-1]^=1;bundle.put("data",android.util.Base64.encodeToString(encrypted,android.util.Base64.URL_SAFE|android.util.Base64.NO_WRAP|android.util.Base64.NO_PADDING));
                }
                if(mode.equals("forge-receipt")&&kind.equals("end")&&value.optJSONArray("receipts")!=null&&value.getJSONArray("receipts").length()>0)
                    value.getJSONArray("receipts").getJSONObject(0).put("token","forged");
                byte[] payload=value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                java.io.DataOutputStream target=new java.io.DataOutputStream(out);target.writeInt(payload.length);target.write(payload);target.flush();
                buffered.reset();
            }catch(IOException error){throw error;}catch(Exception error){throw new IOException(error);}
        }
    }
    private static final class Env implements AutoCloseable {
        final Ctx context; LedgerStore store; final String id;
        Env(Context base,String invite,String role)throws Exception{context=new Ctx(base);store=new LedgerStore(context);store.joinTrip(invite,role);id=PeerIdentity.deviceId(context);}
        void reopen(){store.close();store=new LedgerStore(context);}
        @Override public void close(){store.close();context.cleanup();}
    }
    private static final class Ctx extends ContextWrapper {
        final String prefix="peer_"+UUID.randomUUID()+"_";
        Ctx(Context base){super(base);}
        @Override public Context getApplicationContext(){return this;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences(prefix+name,mode);}
        @Override public File getDatabasePath(String name){return super.getDatabasePath(prefix+name);}
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory){return super.openOrCreateDatabase(prefix+name,mode,factory);}
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler handler){return super.openOrCreateDatabase(prefix+name,mode,factory,handler);}
        void cleanup(){super.deleteDatabase(prefix+"ride_ledger.db");for(String name:new String[]{"cloud","nearby"})super.getSharedPreferences(prefix+name,0).edit().clear().commit();}
    }
}
