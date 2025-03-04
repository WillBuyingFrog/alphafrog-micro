package world.willfrog.alphafrogmicro.domestic.stock.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.elasticsearch.client.ClientConfiguration;
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchConfiguration;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

@Configuration
@ConditionalOnProperty(name = "advanced.es-enabled", havingValue = "true")
@Slf4j
public class ElasticSearchConfig extends ReactiveElasticsearchConfiguration {

    @Value("${spring.elasticsearch.uris}")
    private String elasticSearchUri;

    @Value("${spring.elasticsearch.username}")
    private String elasticSearchUsername;

    @Value("${spring.elasticsearch.password}")
    private String elasticSearchPassword;

    @Override
    public ClientConfiguration clientConfiguration() {

        ClassPathResource certResource = new ClassPathResource("http_ca.crt");



        try (InputStream certInputStream = certResource.getInputStream()) {

            CertificateFactory cf = CertificateFactory.getInstance("X.509");

            Certificate ca = cf.generateCertificate(certInputStream);

            String keyStoreType = KeyStore.getDefaultType();
            KeyStore trustStore = KeyStore.getInstance(keyStoreType);
            trustStore.load(null, null);
            trustStore.setCertificateEntry("ca", ca);

            String tmfAlgorithm = TrustManagerFactory.getDefaultAlgorithm();
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(tmfAlgorithm);
            trustManagerFactory.init(trustStore);

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagerFactory.getTrustManagers(), null);

            return ClientConfiguration.builder()
                    .connectedTo(elasticSearchUri)
                    .usingSsl(context, (hostname, session) -> true)
                    .withBasicAuth(elasticSearchUsername, elasticSearchPassword)
                    .build();
        } catch (Exception e) {
            log.error("Failed to load certificate file", e);
            return null;
        }

    }

}
