package com.hybrid.blockchain;

import org.junit.jupiter.api.Test;
import java.security.*;
import java.math.BigInteger;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import static org.junit.jupiter.api.Assertions.*;

public class SimpleTransactionTest {

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @Test
    public void testTransactionBuilderBasic() {
        Transaction tx = new Transaction.Builder()
                .type(Transaction.Type.ACCOUNT)
                .from("hb5d41402abc4b2a76b9719d911017c5921c1433")
                .to("hbf8e5c1d6b0a7a3b4c5d6e7f8a9b0c1d2e3f4")
                .amount(100)
                .fee(1)
                .nonce(1)
                .networkId(Config.NETWORK_ID)
                .build();

        assertEquals(Transaction.Type.ACCOUNT, tx.getType());
        assertEquals("hb5d41402abc4b2a76b9719d911017c5921c1433", tx.getFrom());
        assertEquals("hbf8e5c1d6b0a7a3b4c5d6e7f8a9b0c1d2e3f4", tx.getTo());
        assertEquals(100, tx.getAmount());
        assertEquals(1, tx.getFee());
    }

    @Test
    public void testTransactionSigningReal() {
        X9ECParameters ecParams = CustomNamedCurves.getByName("secp256k1");
        ECDomainParameters domainParams = new ECDomainParameters(ecParams.getCurve(), ecParams.getG(), ecParams.getN(),
                ecParams.getH());
        ECKeyPairGenerator generator = new ECKeyPairGenerator();
        generator.init(new ECKeyGenerationParameters(domainParams, new SecureRandom()));

        AsymmetricCipherKeyPair kp = generator.generateKeyPair();
        ECPublicKeyParameters pub = (ECPublicKeyParameters) kp.getPublic();
        ECPrivateKeyParameters priv = (ECPrivateKeyParameters) kp.getPrivate();

        byte[] pubKeyBytes = pub.getQ().getEncoded(true);
        String expectedAddr = Crypto.deriveAddress(pubKeyBytes);

        // Build and sign transaction
        Transaction tx = new Transaction.Builder()
                .type(Transaction.Type.ACCOUNT)
                .to("hb5d41402abc4b2a76b9719d911017c5921c1433")
                .amount(1000)
                .nonce(1)
                .networkId(Config.NETWORK_ID)
                .sign(priv.getD(), pubKeyBytes);

        assertTrue(tx.verify(), "Transaction signature verification failed");
        assertEquals(expectedAddr, tx.getFrom());
        assertNotNull(tx.getTxid());
    }

    @Test
    public void testTransactionUTXOinputs() {
        UTXOInput input1 = new UTXOInput("txid1", 0);
        UTXOInput input2 = new UTXOInput("txid2", 1);

        Transaction tx = new Transaction.Builder()
                .type(Transaction.Type.UTXO)
                .inputs(java.util.Arrays.asList(input1, input2))
                .build();

        assertNotNull(tx.getInputs());
        assertEquals(2, tx.getInputs().size());
    }

    @Test
    public void testTransactionDigest() {
        Transaction tx = new Transaction.Builder()
                .type(Transaction.Type.ACCOUNT)
                .to("hb5d41402abc4b2a76b9719d911017c5921c1433")
                .amount(100)
                .build();

        String digest = tx.digest();
        assertNotNull(digest);
        assertEquals(64, digest.length()); // SHA-256 hex
    }
}
