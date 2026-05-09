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
    
    private final List<Map<String, Object>> buffer = new CopyOnWriteArrayList<>();
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
                
                buffer.add(data);
                if (buffer.size() >= 50) {
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
        if (buffer.isEmpty()) return;
        
        List<Map<String, Object>> batch = new ArrayList<>(buffer);
        buffer.clear();
        
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
}
