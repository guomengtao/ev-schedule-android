import java.security.Security;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

/**
 * 让 apksigner 能读取「空 DN 证书」的包装器。
 *
 * 背景：EV 快应用 rpk 的签名证书（sign/certificate.pem）由小米在线工生成，
 * issuer/subject DN 为空。JDK 自带的 X.509 解析器会直接拒绝：
 *   CertificateParsingException: Empty issuer DN not allowed in X509Certificates
 * BouncyCastle 不做这个校验，所以把 BC 注册成优先级最高的 provider 即可。
 *
 * 用法与 apksigner 完全一致：
 *   java -cp <classes>:apksigner.jar:bcprov.jar BCSign sign --key k.pk8 --cert c.pem --out o.apk in.apk
 */
public class BCSign {
    public static void main(String[] args) throws Exception {
        Security.insertProviderAt(new BouncyCastleProvider(), 1);
        com.android.apksigner.ApkSignerTool.main(args);
    }
}
