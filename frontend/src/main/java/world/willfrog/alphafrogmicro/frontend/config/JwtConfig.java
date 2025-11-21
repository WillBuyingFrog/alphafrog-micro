package world.willfrog.alphafrogmicro.frontend.config;

import io.jsonwebtoken.security.Keys;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

@Configuration
@Data
public class JwtConfig {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private Long expiration;

    @Value("${jwt.header}")
    private String header;

    @Value("${jwt.token-prefix}")
    private String tokenPrefix;

    @Bean
    public SecretKey secretKey() {
        // 确保密钥长度至少为256位（32字节）
        String validSecret = secret != null && !secret.trim().isEmpty() ? secret : generateDefaultSecret();
        if (validSecret.length() < 32) {
            validSecret = String.format("%032d", Long.parseLong(validSecret.replaceAll("\\D", "")));
        }
        return Keys.hmacShaKeyFor(validSecret.substring(0, 32).getBytes(StandardCharsets.UTF_8));
    }
    
    private String generateDefaultSecret() {
        // 生成一个基于时间的默认密钥，确保每次启动都不同
        return "DefaultSecretKey01234567890123456789" + System.currentTimeMillis();
    }

    public long getExpirationByMinutes(){
        return expiration / 1000 / 60;
    }

}
