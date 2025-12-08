package org.example.model;

import java.io.Serializable;

public record EncryptedMessage(
        byte[] iv,
        byte[] encryptedData
) implements Serializable {}