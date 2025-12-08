package org.example.crypto;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import java.security.*;

public class CryptoUtils {

    // 1. Генерация пары RSA (для сервера) - 2048 бит
    public static KeyPair generateRSAKeys() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        return kpg.generateKeyPair();
    }

    // 2. Генерация сеансового ключа AES (для клиента) - 256 бит
    public static SecretKey generateAESKey() throws Exception {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256);
        return keyGen.generateKey();
    }

    // 3. Шифрование AES ключа с помощью RSA (чтобы передать его серверу)
    public static byte[] wrapAESKey(SecretKey aesKey, PublicKey publicRsaKey) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA");
        cipher.init(Cipher.WRAP_MODE, publicRsaKey);
        return cipher.wrap(aesKey);
    }

    // 4. Расшифровка AES ключа (на сервере)
    public static SecretKey unwrapAESKey(byte[] wrappedKey, PrivateKey privateRsaKey) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA");
        cipher.init(Cipher.UNWRAP_MODE, privateRsaKey);
        return (SecretKey) cipher.unwrap(wrappedKey, "AES", Cipher.SECRET_KEY);
    }

    // 5. Шифрование данных (AES + случайный IV)
    public static byte[] encryptData(byte[] data, SecretKey key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, ivSpec);
        return cipher.doFinal(data);
    }

    // 6. Дешифрование данных
    public static byte[] decryptData(byte[] encryptedData, SecretKey key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        cipher.init(Cipher.DECRYPT_MODE, key, ivSpec);
        return cipher.doFinal(encryptedData);
    }

    // 7. Генерация уникального IV для каждого сообщения
    public static byte[] generateIV() {
        byte[] iv = new byte[16]; // AES block size
        new SecureRandom().nextBytes(iv);
        return iv;
    }
}