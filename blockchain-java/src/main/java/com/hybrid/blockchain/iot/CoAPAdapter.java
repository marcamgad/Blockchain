package com.hybrid.blockchain.iot;

import com.hybrid.blockchain.Blockchain;
import com.hybrid.blockchain.Config;
import com.hybrid.blockchain.Crypto;
import com.hybrid.blockchain.Transaction;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.msgpack.jackson.dataformat.MessagePackFactory;
import org.eclipse.californium.core.CoapResource;
import org.eclipse.californium.core.CoapServer;
import org.eclipse.californium.core.coap.CoAP;
import org.eclipse.californium.core.server.resources.CoapExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * PAPER-IMPL: P1-B — Maftei et al. Sensors 2025
 * CoAP Adapter for IoT telemetry batching.
 */
public class CoAPAdapter extends CoapServer {
    private static final Logger log = LoggerFactory.getLogger(CoAPAdapter.class);
    private final Blockchain blockchain;
    private final byte[] privateKey;
    private final String address;
    
    private static final int BATCH_SIZE = 50;
    private final List<Map<String, Object>> buffer = new ArrayList<>();
    private final Object bufferLock = new Object();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ObjectMapper msgpackMapper = new ObjectMapper(new MessagePackFactory());

    public CoAPAdapter(Blockchain blockchain, byte[] privateKey) {
        super(Config.COAP_PORT);
        this.blockchain = blockchain;
        this.privateKey = privateKey;
        this.address = Crypto.deriveAddress(Crypto.derivePublicKey(new java.math.BigInteger(1, privateKey)));
        
        add(new TelemetryResource());
        
        scheduler.scheduleAtFixedRate(this::flush, 10, 10, TimeUnit.SECONDS);
    }

    private class TelemetryResource extends CoapResource {
        public TelemetryResource() {
            super("telemetry");
        }

        @Override
        public void handlePOST(CoapExchange exchange) {
            try {
                byte[] payload = exchange.getRequestPayload();
                ObjectMapper mapper = new ObjectMapper();
                Map<String, Object> data = mapper.readValue(payload, Map.class);

                boolean full;
                synchronized (bufferLock) {
                    buffer.add(data);
                    full = buffer.size() >= BATCH_SIZE;
                }
                if (full) {
                    flush();
                }

                exchange.respond(CoAP.ResponseCode.CREATED);
            } catch (Exception e) {
                log.warn("[CoAP] Failed to process telemetry: {}", e.getMessage());
                exchange.respond(CoAP.ResponseCode.BAD_REQUEST);
            }
        }
    }

    private synchronized void flush() {
        // Snapshot-and-clear atomically under bufferLock so a reading added between the
        // copy and the clear is never silently dropped.
        List<Map<String, Object>> batch;
        synchronized (bufferLock) {
            if (buffer.isEmpty()) return;
            batch = new ArrayList<>(buffer);
            buffer.clear();
        }

        try {
            byte[] packedData = msgpackMapper.writeValueAsBytes(batch);
            
            Transaction tx = new Transaction.Builder()
                    .type(Transaction.Type.TELEMETRY_BATCH)
                    .from(address)
                    .data(packedData)
                    .fee(10) // Small batch fee
                    .nonce(blockchain.getNonce(address) + 1)
                    .build();
            
            tx.sign(new java.math.BigInteger(1, privateKey));
            blockchain.addTransaction(tx);
            log.info("[CoAP] Flushed batch of {} readings ({} bytes)", batch.size(), packedData.length);
        } catch (Exception e) {
            log.error("[CoAP] Failed to flush batch: {}", e.getMessage());
        }
    }

    /**
     * Flushes any buffered readings, stops the CoAP server, and shuts down the batch
     * scheduler. Overrides {@link CoapServer#stop()}, which previously left the
     * scheduled flush thread running (a resource leak on redeploy/restart).
     */
    @Override
    public void stop() {
        try {
            flush();
        } finally {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            super.stop();
        }
    }
}
