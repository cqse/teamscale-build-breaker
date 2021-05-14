package com.teamscale.buildbreaker;

import com.teamscale.buildbreaker.exceptions.BuildBreakerInternalException;
import com.teamscale.buildbreaker.exceptions.KeystoreException;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.ResponseBody;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

/**
 * Utilities for creating an {@link OkHttpClient}
 */
public class OkHttpClientUtils {

    /**
     * Creates the {@link OkHttpClient} based on the given connection settings.
     *
     * @param trustStorePath     May be null if no trust store should be used.
     * @param trustStorePassword May be null if no trust store should be used.
     */
    public static OkHttpClient createClient(boolean disableSslValidation, String trustStorePath,
                                            String trustStorePassword) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder();

        setSensibleTimeouts(builder);
        builder.followRedirects(false).followSslRedirects(false);

        if (trustStorePath != null) {
            configureTrustStore(builder, trustStorePath, trustStorePassword);
        }
        if (disableSslValidation) {
            disableSslValidation(builder);
        }

        return builder.build();
    }

    /**
     * Reads the keystore at the given path and configures the builder so the {@link OkHttpClient} will accept the
     * certificates stored in the keystore.
     */
    private static void configureTrustStore(OkHttpClient.Builder builder, String trustStorePath,
                                            String trustStorePassword) {
        KeyStore keyStore = readKeyStore(trustStorePath, trustStorePassword);
        try {
            SSLContext sslContext = SSLContext.getInstance("SSL");
            TrustManagerFactory trustManagerFactory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(keyStore);
            sslContext.init(null, trustManagerFactory.getTrustManagers(), new SecureRandom());

            if (trustManagerFactory.getTrustManagers().length == 0) {
                throw new BuildBreakerInternalException(
                        "No trust managers found. This is a bug. Please report it to CQSE.");
            }

            applySslContextAndTrustManager(builder, sslContext, trustManagerFactory);
        } catch (NoSuchAlgorithmException e) {
            throw new BuildBreakerInternalException(
                    "Failed to instantiate an SSLContext or TrustManagerFactory: " + e.getMessage() +
                            "\nThis is a bug. Please report it to CQSE.", e);
        } catch (KeyStoreException e) {
            throw new BuildBreakerInternalException(
                    "Failed to initialize the TrustManagerFactory with the keystore: " + e.getMessage() +
                            "\nThis is a bug. Please report it to CQSE.", e);
        } catch (KeyManagementException e) {
            throw new BuildBreakerInternalException(
                    "Failed to initialize the SSLContext with the trust managers: " + e.getMessage() +
                            "\nThis is a bug. Please report it to CQSE.", e);
        }
    }

    private static void applySslContextAndTrustManager(OkHttpClient.Builder builder, SSLContext sslContext,
                                                       TrustManagerFactory trustManagerFactory) {
        try {
            X509TrustManager x509TrustManager = (X509TrustManager) trustManagerFactory.getTrustManagers()[0];
            builder.sslSocketFactory(sslContext.getSocketFactory(), x509TrustManager);
        } catch (ClassCastException e) {
            throw new BuildBreakerInternalException("Trust manager is not of X509 format: " + e.getMessage() +
                    "\nThis is a bug. Please report it to CQSE.", e);
        }
    }

    private static KeyStore readKeyStore(String keystorePath, String keystorePassword) {
        try (FileInputStream stream = new FileInputStream(keystorePath)) {
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(stream, keystorePassword.toCharArray());
            return keyStore;
        } catch (IOException e) {
            throw new KeystoreException("Failed to read keystore file " + keystorePath + ": " + e.getMessage() +
                    "\nPlease make sure that file exists and is readable and that you provided the correct password." +
                    " Please also make sure that the keystore file is a valid Java keystore." +
                    " You can use the program `keytool` from your JVM installation to check this:" +
                    "\nkeytool -list -keystore " + keystorePath);
        } catch (CertificateException e) {
            throw new KeystoreException(
                    "Failed to load one of the certificates in the keystore file " + keystorePath + ": " +
                            e.getMessage() +
                            "\nPlease make sure that the certificate is stored correctly and the certificate version and encoding are supported.");
        } catch (NoSuchAlgorithmException e) {
            throw new KeystoreException("Failed to verify the integrity of the keystore file " + keystorePath +
                    " because it uses an unsupported hashing algorithm: " + e.getMessage() +
                    "\nPlease change the keystore so it uses a supported algorithm (e.g. the default used by `keytool` is supported).");
        } catch (KeyStoreException e) {
            throw new BuildBreakerInternalException("Failed to instantiate an in-memory keystore: " + e.getMessage() +
                    "\nThis is a bug. Please report it to CQSE.", e);
        }
    }

    private static void disableSslValidation(OkHttpClient.Builder builder) {
        SSLSocketFactory sslSocketFactory;
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{TrustAllCertificatesManager.INSTANCE}, new SecureRandom());
            sslSocketFactory = sslContext.getSocketFactory();
        } catch (GeneralSecurityException e) {
            System.err.println("Could not disable SSL certificate validation. Leaving it enabled");
            e.printStackTrace();
            return;
        }

        builder.sslSocketFactory(sslSocketFactory, TrustAllCertificatesManager.INSTANCE);
        builder.hostnameVerifier((hostName, session) -> true);
    }

    private static void setSensibleTimeouts(OkHttpClient.Builder builder) {
        builder.connectTimeout(60L, TimeUnit.SECONDS);
        builder.readTimeout(60L, TimeUnit.SECONDS);
        builder.writeTimeout(60L, TimeUnit.SECONDS);
    }

    public static String readBodySafe(Response response) {
        try {
            ResponseBody body = response.body();
            if (body == null) {
                return "<no response body>";
            }
            return body.string();
        } catch (IOException e) {
            return "Failed to read response body: " + e.getMessage();
        }
    }

    private static class TrustAllCertificatesManager implements X509TrustManager {
        static final TrustAllCertificatesManager INSTANCE = new TrustAllCertificatesManager();

        public TrustAllCertificatesManager() {
        }

        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }

        public void checkServerTrusted(X509Certificate[] certs, String authType) {
        }

        public void checkClientTrusted(X509Certificate[] certs, String authType) {
        }
    }

}
