import java.nio.file.*;
import java.security.*;
import java.util.Base64;

public class GenerateRsaKeys {

    public static void main(String[] args) throws Exception {

        Path privatePath = Path.of("infra/keys/private.pem");

        Path identityPublic = Path.of(
                "apps/identity-driver-service/src/main/resources/keys/public.pem"
        );

        Path gatewayPublic = Path.of(
                "apps/api-gateway/src/main/resources/keys/public.pem"
        );

        if (Files.exists(privatePath)) {
            throw new IllegalStateException(
                    "Private key already exists. Do not overwrite it."
            );
        }

        KeyPairGenerator generator =
                KeyPairGenerator.getInstance("RSA");

        generator.initialize(3072);

        KeyPair pair = generator.generateKeyPair();

        String privatePem = toPem(
                "PRIVATE KEY",
                pair.getPrivate().getEncoded()
        );

        String publicPem = toPem(
                "PUBLIC KEY",
                pair.getPublic().getEncoded()
        );

        Files.createDirectories(privatePath.getParent());
        Files.createDirectories(identityPublic.getParent());
        Files.createDirectories(gatewayPublic.getParent());

        Files.writeString(privatePath, privatePem);
        Files.writeString(identityPublic, publicPem);
        Files.writeString(gatewayPublic, publicPem);

        System.out.println("RSA keys generated successfully");
    }

    private static String toPem(String type, byte[] bytes) {

        String base64 = Base64.getMimeEncoder(
                64, new byte[]{'\n'}
        ).encodeToString(bytes);

        return "-----BEGIN " + type + "-----\n"
                + base64
                + "\n-----END " + type + "-----\n";
    }
}
