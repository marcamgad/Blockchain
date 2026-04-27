package com.hybrid.blockchain.api;

import com.hybrid.blockchain.Blockchain;
import com.hybrid.blockchain.Config;
import com.hybrid.blockchain.Transaction;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.californium.core.CoapResource;
import org.eclipse.californium.core.CoapServer;
import org.eclipse.californium.core.config.CoapConfig;
import org.eclipse.californium.core.server.resources.CoapExchange;
import org.eclipse.californium.elements.config.Configuration;
import org.eclipse.californium.elements.config.SystemConfig;
import org.eclipse.californium.elements.config.UdpConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * CoAP API Adapter for lightweight IoT device communication.
 */
public class CoAPAdapter {
    private static final Logger log = LoggerFactory.getLogger(CoAPAdapter.class);
    private final CoapServer server;
    private final Blockchain blockchain;
    private final ObjectMapper mapper = new ObjectMapper();

    static {
        CoapConfig.register();
        UdpConfig.register();
        SystemConfig.register();
    }

    public CoAPAdapter(Blockchain blockchain) {
        this(blockchain, Config.COAP_PORT);
    }

    public CoAPAdapter(Blockchain blockchain, int port) {
        this.blockchain = blockchain;
        Configuration config = Configuration.getStandard();
        this.server = new CoapServer(config, port);
        
        server.add(new HealthResource());
        server.add(new BalanceResource());
        server.add(new TransactionResource());
        server.add(new TelemetryResource());
    }

    public void start() {
        start(false);
    }

    public void start(boolean force) {
        if (force || Config.NODE_ROLE == Config.NodeRole.GATEWAY || Config.NODE_ROLE == Config.NodeRole.VALIDATOR) {
            log.info("Starting CoAP Interface on port {}", server.getEndpoints().get(0).getAddress().getPort());
            server.start();
        } else {
            log.info("Node role {} does not require CoAP Interface.", Config.NODE_ROLE);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop();
            server.destroy();
        }
    }

    private class HealthResource extends CoapResource {
        public HealthResource() {
            super("health");
        }
        @Override
        public void handleGET(CoapExchange exchange) {
            exchange.respond("OK: Height " + blockchain.getHeight());
        }
    }

    private class BalanceResource extends CoapResource {
        public BalanceResource() {
            super("balance");
        }
        @Override
        public void handleGET(CoapExchange exchange) {
            String address = exchange.getQueryParameter("address");
            if (address == null || address.isEmpty()) {
                exchange.respond(org.eclipse.californium.core.coap.CoAP.ResponseCode.BAD_REQUEST, "Missing address");
                return;
            }
            try {
                long balance = blockchain.getBalance(address);
                exchange.respond(String.valueOf(balance));
            } catch (Exception e) {
                exchange.respond(org.eclipse.californium.core.coap.CoAP.ResponseCode.INTERNAL_SERVER_ERROR, e.getMessage());
            }
        }
    }

    private class TransactionResource extends CoapResource {
        public TransactionResource() {
            super("tx");
        }
        @Override
        public void handlePOST(CoapExchange exchange) {
            try {
                byte[] payload = exchange.getRequestPayload();
                if (payload == null) {
                    exchange.respond(org.eclipse.californium.core.coap.CoAP.ResponseCode.BAD_REQUEST, "Empty payload");
                    return;
                }
                Transaction tx = blockchain.deserializeTransaction(payload);
                blockchain.addTransaction(tx);
                exchange.respond(org.eclipse.californium.core.coap.CoAP.ResponseCode.CREATED, tx.getTxid());
            } catch (Exception e) {
                exchange.respond(org.eclipse.californium.core.coap.CoAP.ResponseCode.BAD_REQUEST, e.getMessage());
            }
        }
    }

    private class TelemetryResource extends CoapResource {
        public TelemetryResource() {
            super("telemetry");
        }

        /**
         * Accepts a minimal JSON body: {@code {"deviceId":"<id>","value":<number>}}.
         * Wraps the raw payload as a signed TELEMETRY transaction using the node key
         * and pushes it to the blockchain mempool.
         *
         * <p>Response codes:
         * <ul>
         *   <li>CREATED (2.01) + txid on success</li>
         *   <li>BAD_REQUEST (4.00) on missing/malformed payload</li>
         *   <li>INTERNAL_SERVER_ERROR (5.00) on blockchain rejection</li>
         * </ul>
         */
        @Override
        public void handlePOST(CoapExchange exchange) {
            byte[] payload = exchange.getRequestPayload();
            if (payload == null || payload.length == 0) {
                exchange.respond(org.eclipse.californium.core.coap.CoAP.ResponseCode.BAD_REQUEST, "Empty payload");
                return;
            }
            try {
                // Validate the incoming JSON has the required fields
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> body = mapper.readValue(payload, java.util.Map.class);
                if (!body.containsKey("deviceId") || !body.containsKey("value")) {
                    exchange.respond(org.eclipse.californium.core.coap.CoAP.ResponseCode.BAD_REQUEST,
                            "Missing required fields: deviceId, value");
                    return;
                }

                String deviceId = String.valueOf(body.get("deviceId"));
                long   nonce    = System.currentTimeMillis(); // monotonic enough for gateway use
                String nodeAddr = com.hybrid.blockchain.Config.NODE_ID;

                com.hybrid.blockchain.Transaction tx;
                try {
                    java.math.BigInteger privKey = com.hybrid.blockchain.Config.getNodePrivateKey();
                    byte[] pubKey = com.hybrid.blockchain.Crypto.derivePublicKey(privKey);
                    tx = new com.hybrid.blockchain.Transaction.Builder()
                            .type(com.hybrid.blockchain.Transaction.Type.TELEMETRY)
                            .from(nodeAddr)
                            .to(deviceId)
                            .amount(0)
                            .data(payload)
                            .nonce(nonce)
                            .sign(privKey, pubKey)
                            .build();
                } catch (RuntimeException noKey) {
                    // Unsigned gateway mode (no node key configured — debug/test only)
                    tx = new com.hybrid.blockchain.Transaction.Builder()
                            .type(com.hybrid.blockchain.Transaction.Type.TELEMETRY)
                            .from(nodeAddr)
                            .to(deviceId)
                            .amount(0)
                            .data(payload)
                            .nonce(nonce)
                            .build();
                }

                blockchain.addTransaction(tx);
                exchange.respond(org.eclipse.californium.core.coap.CoAP.ResponseCode.CREATED, tx.getTxid());
                log.info("[CoAP] Telemetry from device {} submitted as tx {}", deviceId, tx.getTxid());
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                exchange.respond(org.eclipse.californium.core.coap.CoAP.ResponseCode.BAD_REQUEST,
                        "Invalid JSON: " + e.getOriginalMessage());
            } catch (Exception e) {
                log.warn("[CoAP] Telemetry submission failed: {}", e.getMessage());
                exchange.respond(org.eclipse.californium.core.coap.CoAP.ResponseCode.INTERNAL_SERVER_ERROR,
                        e.getMessage());
            }
        }
    }
}
