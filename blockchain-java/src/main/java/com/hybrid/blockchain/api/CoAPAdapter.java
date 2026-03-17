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
        this.blockchain = blockchain;
        Configuration config = Configuration.getStandard();
        this.server = new CoapServer(config, Config.COAP_PORT);
        
        server.add(new HealthResource());
        server.add(new BalanceResource());
        server.add(new TransactionResource());
        server.add(new TelemetryResource());
    }

    public void start() {
        if (Config.NODE_ROLE == Config.NodeRole.GATEWAY || Config.NODE_ROLE == Config.NodeRole.VALIDATOR) {
            log.info("Starting CoAP Interface on port {}", Config.COAP_PORT);
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
        @Override
        public void handlePOST(CoapExchange exchange) {
            // Usually wrapped in a Transaction of type TELEMETRY
            // For raw telemetry, we expect a JSON or CBOR payload that the gateway wraps
            exchange.respond(org.eclipse.californium.core.coap.CoAP.ResponseCode.NOT_IMPLEMENTED, "Submit telemetry via POST /tx as TELEMETRY transaction");
        }
    }
}
