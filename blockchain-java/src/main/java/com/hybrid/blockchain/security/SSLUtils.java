package com.hybrid.blockchain.security;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.net.ssl.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;

public class SSLUtils {

    public static SSLContext createSSLContext(KeyPair keyPair, String identity, List<X509Certificate> trustedCerts) throws Exception {
        X509Certificate cert = generateSelfSignedCertificate(keyPair, identity);

        // Create KeyStore for our own certificate and private key
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("main", keyPair.getPrivate(), "password".toCharArray(), new java.security.cert.Certificate[]{cert});

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, "password".toCharArray());

        // Create TrustStore for trusted peer certificates
        KeyStore trustStore = KeyStore.getInstance("JKS");
        trustStore.load(null, null);
        
        // Add all provided trusted certificates
        if (trustedCerts != null) {
            for (int i = 0; i < trustedCerts.size(); i++) {
                trustStore.setCertificateEntry("peer-" + i, trustedCerts.get(i));
            }
        }
        
        // Always trust our own certificate
        trustStore.setCertificateEntry("self", cert);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        SSLContext sslContext = SSLContext.getInstance("TLSv1.3");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());

        return sslContext;
    }

    public static X509Certificate generateSelfSignedCertificate(KeyPair keyPair, String identity) throws Exception {
        long now = System.currentTimeMillis();
        Date startDate = new Date(now);
        X500Name dnName = new X500Name("CN=" + identity);
        BigInteger certSerialNumber = new BigInteger(Long.toString(now));
        Date endDate = new Date(now + 365L * 24L * 60L * 60L * 1000L); // 1 year

        ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256WithECDSA").build(keyPair.getPrivate());
        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(dnName, certSerialNumber, startDate, endDate, dnName, keyPair.getPublic());
        X509CertificateHolder certHolder = certBuilder.build(contentSigner);

        return new JcaX509CertificateConverter().setProvider("BC").getCertificate(certHolder);
    }
}
