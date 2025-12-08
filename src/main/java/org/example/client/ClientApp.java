package org.example.client;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConnectionFactory;
import org.apache.commons.lang3.SerializationUtils;
import org.example.AppConfig;
import org.example.crypto.CryptoUtils;
import org.example.model.EncryptedMessage;
import org.example.model.Flight;

import javax.crypto.SecretKey;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.Socket;
import java.security.PublicKey;
import java.util.List;

public class ClientApp {
    public static void main(String[] args) {
        try {
            AppConfig config = AppConfig.get();
            System.out.println("Клиент запущен. Чтение SQLite...");

            // 1. Читаем данные
            List<Flight> data = new SqliteReader().readAll();
            if (data.isEmpty()) {
                System.out.println("SQLite пуст или не найден. Проверьте путь в application.conf");
                return;
            }
            System.out.println("Загружено записей: " + data.size());

            // 2. Генерируем ключ сессии
            SecretKey aesKey = CryptoUtils.generateAESKey();

            if ("SOCKET".equalsIgnoreCase(config.transportMode)) {
                sendViaSocket(data, aesKey, config);
            } else {
                sendViaRabbitMq(data, aesKey, config);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void sendViaSocket(List<Flight> data, SecretKey aesKey, AppConfig config) {
        try (Socket socket = new Socket(config.socketHost, config.socketPort);
             ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
             ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream())) {

            // Handshake: Получаем Public Key
            PublicKey serverPublic = (PublicKey) ois.readObject();

            // Отправляем AES ключ
            byte[] wrapped = CryptoUtils.wrapAESKey(aesKey, serverPublic);
            oos.writeObject(wrapped);
            oos.flush();

            // Отправка данных
            for (Flight dto : data) {
                sendEncrypted(dto, aesKey, oos);
                System.out.print(".");
            }
            System.out.println("\nВсе данные отправлены через Сокет.");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void sendViaRabbitMq(List<Flight> data, SecretKey aesKey, AppConfig config) throws Exception {
        // 1. Handshake (через сокет, даже если транспорт - очередь)
        System.out.println("Согласование ключей...");
        try (Socket socket = new Socket(config.socketHost, config.socketPort);
             ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
             ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream())) {

            PublicKey serverPublic = (PublicKey) ois.readObject();
            byte[] wrapped = CryptoUtils.wrapAESKey(aesKey, serverPublic);
            oos.writeObject(wrapped);
            oos.flush();
        }

        // 2. Отправка в очередь
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(config.rmqHost);
        factory.setUsername(config.rmqUser);
        factory.setPassword(config.rmqPass);

        try (com.rabbitmq.client.Connection connection = factory.newConnection();
             Channel channel = connection.createChannel()) {

            channel.queueDeclare(config.rmqQueue, true, false, false, null);

            for (Flight dto : data) {
                // Шифруем
                byte[] iv = CryptoUtils.generateIV();
                byte[] payload = SerializationUtils.serialize(dto);
                byte[] encrypted = CryptoUtils.encryptData(payload, aesKey, iv);

                EncryptedMessage msg = new EncryptedMessage(iv, encrypted);

                channel.basicPublish("", config.rmqQueue, null, SerializationUtils.serialize(msg));
                System.out.print(".");
            }
            System.out.println("\nВсе данные отправлены через RabbitMQ.");
        }
    }

    private static void sendEncrypted(Serializable obj, SecretKey key, ObjectOutputStream oos) throws Exception {
        byte[] iv = CryptoUtils.generateIV();
        byte[] payload = SerializationUtils.serialize(obj);
        byte[] encrypted = CryptoUtils.encryptData(payload, key, iv);

        oos.writeObject(new EncryptedMessage(iv, encrypted));
        oos.flush();
    }
}

