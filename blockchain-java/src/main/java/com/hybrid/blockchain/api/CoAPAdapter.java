package com.hybrid.blockchain.api;

import com.hybrid.blockchain.Blockchain;
import com.hybrid.blockchain.Config;
import com.hybrid.blockchain.Transaction;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.californium.core.CoapResource;
import org.eclipse.californium.core.CoapServer;
import org.eclipse.californium.core.config.CoapConfig;
import org.eclipse.californium.core.coap.CoAP;
import org.eclipse.californium.core.server.resources.CoapExchange;
import org.eclipse.californium.elements.config.Configuration;
import org.eclipse.californium.elements.config.SystemConfig;
import org.eclipse.californium.elements.config.UdpConfig;

import java.util.Map;

/**
 * CoAP API Adapter for lightweight IoT device communication. Read endpoints
 * (health/balance) are served directly; write endpoints (tx/telemetry) flow through
 * the shared {@link AbstractIoTMessageAdapter} ingress path (rate-limit, validate,
 * sign, submit).
 */
public class CoAPAdapter extends AbstractIoTMessageAdapter {
    private final CoapServer server;
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
        super(blockchain);
        Configuration config = Configuration.getStandard();
        this.server = new CoapServer(config, port);

        server.add(new HealthResource());
        server.add(new BalanceResource());
        server.add(new TransactionResource());
        server.add(new TelemetryResource());
    }

    @Override
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

    @Override
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
                exchange.respond(CoAP.ResponseCode.BAD_REQUEST, "Missing address");
                return;
            }
            try {
                long balance = blockchain.getBalance(address);
                exchange.respond(String.valueOf(balance));
            } catch (Exception e) {
                exchange.respond(CoAP.ResponseCode.INTERNAL_SERVER_ERROR, e.getMessage());
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
                    exchange.respond(CoAP.ResponseCode.BAD_REQUEST, "Empty payload");
                    return;
                }
                // Rate-limit raw tx ingress (keyed per-endpoint; a real deployment would
                // key by remote peer identity).
                if (!allowIngress("coap-tx")) {
                    exchange.respond(CoAP.ResponseCode.SERVICE_UNAVAILABLE, "Rate limit exceeded");
                    return;
                }
                Transaction tx = blockchain.deserializeTransaction(payload);
                blockchain.addTransaction(tx);
                exchange.respond(CoAP.ResponseCode.CREATED, tx.getTxid());
            } catch (Exception e) {
                exchange.respond(CoAP.ResponseCode.BAD_REQUEST, e.getMessage());
            }
        }
    }

    private class TelemetryResource extends CoapResource {
        public TelemetryResource() {
            super("telemetry");
        }

        /**
         * Accepts a minimal JSON body: {@code {"deviceId":"<id>","value":<number>}} and
         * submits it as a signed TELEMETRY transaction via the shared ingress path.
         *
         * <p>Response codes: CREATED (2.01)+txid on success; BAD_REQUEST (4.00) on
         * missing/malformed payload; SERVICE_UNAVAILABLE (5.03) when rate-limited;
         * INTERNAL_SERVER_ERROR (5.00) on blockchain rejection.
         */
        @Override
        public void handlePOST(CoapExchange exchange) {
            byte[] payload = exchange.getRequestPayload();
            if (payload == null || payload.length == 0) {
                exchange.respond(CoAP.ResponseCode.BAD_REQUEST, "Empty payload");
                return;
            }
            String deviceId;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = mapper.readValue(payload, Map.class);
                if (!body.containsKey("deviceId") || !body.containsKey("value")) {
                    exchange.respond(CoAP.ResponseCode.BAD_REQUEST,
                            "Missing required fields: deviceId, value");
                    return;
                }
                deviceId = String.valueOf(body.get("deviceId"));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                exchange.respond(CoAP.ResponseCode.BAD_REQUEST, "Invalid JSON: " + e.getOriginalMessage());
                return;
            } catch (Exception e) {
                exchange.respond(CoAP.ResponseCode.BAD_REQUEST, "Invalid payload");
                return;
            }

            try {
                String txid = submitDeviceTx(Transaction.Type.TELEMETRY, deviceId, payload);
                exchange.respond(CoAP.ResponseCode.CREATED, txid);
                log.info("[CoAP] Telemetry from device {} submitted as tx {}", deviceId, txid);
            } catch (RateLimitedException rle) {
                exchange.respond(CoAP.ResponseCode.SERVICE_UNAVAILABLE, "Rate limit exceeded");
            } catch (IllegalArgumentException iae) {
                exchange.respond(CoAP.ResponseCode.BAD_REQUEST, iae.getMessage());
            } catch (Exception e) {
                log.warn("[CoAP] Telemetry submission failed: {}", e.getMessage());
                exchange.respond(CoAP.ResponseCode.INTERNAL_SERVER_ERROR, e.getMessage());
            }
        }
    }
}
