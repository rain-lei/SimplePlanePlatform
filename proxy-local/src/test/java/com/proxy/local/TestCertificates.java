package com.proxy.local;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import java.io.IOException;
import java.io.Writer;
import java.math.BigInteger;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;

final class TestCertificates {
        private final Path directory;
        private final java.io.File certificate;
        private final java.io.File privateKey;
        private final java.io.File ca;

        private TestCertificates(Path directory, java.io.File certificate,
                                 java.io.File privateKey, java.io.File ca) {
            this.directory = directory;
            this.certificate = certificate;
            this.privateKey = privateKey;
            this.ca = ca;
        }

        static TestCertificates create() throws Exception {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(new BouncyCastleProvider());
            }
            Path directory = Files.createTempDirectory("http3-test-tls");
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair caKey = generator.generateKeyPair();
            KeyPair serverKey = generator.generateKeyPair();
            Date notBefore = new Date(System.currentTimeMillis() - 60_000);
            Date notAfter = new Date(System.currentTimeMillis() + 86_400_000);
            X500Name caName = new X500Name("CN=SimplePlane Test CA");
            X500Name serverName = new X500Name("CN=localhost");
            X509Certificate caCert = certificate(caName, caName, caKey, caKey, notBefore, notAfter, true);
            X509Certificate serverCert = certificate(caName, serverName, caKey, serverKey,
                    notBefore, notAfter, false);

            java.io.File certFile = directory.resolve("server-chain.pem").toFile();
            java.io.File keyFile = directory.resolve("server-key.pem").toFile();
            java.io.File caFile = directory.resolve("ca.pem").toFile();
            writePem(certFile, serverCert, caCert);
            writePem(keyFile, serverKey.getPrivate());
            writePem(caFile, caCert);
            return new TestCertificates(directory, certFile, keyFile, caFile);
        }

        private static X509Certificate certificate(X500Name issuer, X500Name subject,
                                                   KeyPair issuerKey, KeyPair subjectKey,
                                                   Date notBefore, Date notAfter, boolean ca) throws Exception {
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    issuer, BigInteger.valueOf(System.nanoTime()), notBefore, notAfter,
                    subject, subjectKey.getPublic());
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
            builder.addExtension(Extension.keyUsage, false,
                    new KeyUsage(ca ? KeyUsage.keyCertSign | KeyUsage.cRLSign
                            : KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
            if (!ca) {
                builder.addExtension(Extension.subjectAlternativeName, false,
                        new GeneralNames(new GeneralName(GeneralName.dNSName, "localhost")));
            }
            ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME).build(issuerKey.getPrivate());
            return new JcaX509CertificateConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .getCertificate(builder.build(signer));
        }

        private static void writePem(java.io.File file, Object... objects) throws IOException {
            try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.US_ASCII);
                 JcaPEMWriter pem = new JcaPEMWriter(writer)) {
                for (Object object : objects) {
                    pem.writeObject(object);
                }
            }
        }

        java.io.File certificate() { return certificate; }
        java.io.File privateKey() { return privateKey; }
        java.io.File ca() { return ca; }

        void delete() throws IOException {
            Files.deleteIfExists(certificate.toPath());
            Files.deleteIfExists(privateKey.toPath());
            Files.deleteIfExists(ca.toPath());
            Files.deleteIfExists(directory);
        }
    }
