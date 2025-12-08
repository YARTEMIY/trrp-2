package org.example.server;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import org.apache.commons.lang3.SerializationUtils;
import org.example.AppConfig;
import org.example.crypto.CryptoUtils;
import org.example.model.EncryptedMessage;
import org.example.model.Flight;

import javax.crypto.SecretKey;
import java.io.EOFException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyPair;

public class ServerApp {
    private static PostgresService db;

    public static void main(String[] args) {
        try {
            db = new PostgresService();
            AppConfig config = AppConfig.get();
            System.out.println("Сервер запущен. Режим: " + config.transportMode);
            System.out.println("Подключение к БД PostgreSQL успешно.");

            if ("SOCKET".equalsIgnoreCase(config.transportMode)) {
                runSocketServer(config.socketPort);
            } else {
                runRabbitMqServer(config);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // --- ЛОГИКА СОКЕТОВ ---
    private static void runSocketServer(int port) throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            while (true) {
                System.out.println("Ждем клиента...");
                Socket client = serverSocket.accept();
                // Обработка клиента в отдельном потоке
                new Thread(() -> handleClient(client)).start();
            }
        }
    }

    private static void handleClient(Socket socket) {
        try (ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream ois = new ObjectInputStream(socket.getInputStream())) {

            // 1. Handshake: Генерируем RSA, отправляем публичный ключ
            KeyPair rsaPair = CryptoUtils.generateRSAKeys();
            oos.writeObject(rsaPair.getPublic());
            oos.flush();

            // 2. Получаем зашифрованный AES ключ и расшифровываем его
            byte[] wrappedAesKey = (byte[]) ois.readObject();
            SecretKey aesKey = CryptoUtils.unwrapAESKey(wrappedAesKey, rsaPair.getPrivate());
            System.out.println("Клиент подключен, ключ согласован.");

            // 3. Читаем данные в цикле
            while (true) {
                try {
                    EncryptedMessage msg = (EncryptedMessage) ois.readObject();
                    // РАСШИФРОВКА: используем IV из сообщения
                    byte[] decryptedBytes = CryptoUtils.decryptData(msg.encryptedData(), aesKey, msg.iv());

                    Flight dto = SerializationUtils.deserialize(decryptedBytes);
                    db.saveFlight(dto);
                } catch (EOFException e) {
                    System.out.println("Клиент отключился.");
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // --- ЛОГИКА RABBITMQ ---
    private static void runRabbitMqServer(AppConfig config) throws Exception {
        // Даже для RabbitMQ нужен канал обмена ключами.
        // Используем сокет для handshake, а данные пойдут через очередь.
        final SecretKey[] sessionKey = {null};

        // Поток для получения ключа
        new Thread(() -> {
            try (ServerSocket ss = new ServerSocket(config.socketPort)) {
                System.out.println("Ожидание ключа AES через side-channel порт " + config.socketPort);
                Socket s = ss.accept();
                try (ObjectOutputStream oos = new ObjectOutputStream(s.getOutputStream());
                     ObjectInputStream ois = new ObjectInputStream(s.getInputStream())) {

                    KeyPair kp = CryptoUtils.generateRSAKeys();
                    oos.writeObject(kp.getPublic());
                    byte[] wrapped = (byte[]) ois.readObject();
                    sessionKey[0] = CryptoUtils.unwrapAESKey(wrapped, kp.getPrivate());
                    System.out.println("RabbitMQ: Ключ AES получен.");
                }
            } catch (Exception e) { e.printStackTrace(); }
        }).start();

        // Подключение к RabbitMQ
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(config.rmqHost);
        factory.setUsername(config.rmqUser);
        factory.setPassword(config.rmqPass);

        com.rabbitmq.client.Connection connection = factory.newConnection();
        Channel channel = connection.createChannel();
        channel.queueDeclare(config.rmqQueue, true, false, false, null);
        System.out.println("Слушаем очередь: " + config.rmqQueue);

        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            if (sessionKey[0] == null) {
                System.err.println("Ошибка: Сообщение пришло, но ключ шифрования еще не согласован!");
                return;
            }
            try {
                EncryptedMessage msg = SerializationUtils.deserialize(delivery.getBody());
                byte[] data = CryptoUtils.decryptData(msg.encryptedData(), sessionKey[0], msg.iv());
                Flight dto = SerializationUtils.deserialize(data);
                db.saveFlight(dto);
            } catch (Exception e) {
                e.printStackTrace();
            }
        };
        channel.basicConsume(config.rmqQueue, true, deliverCallback, consumerTag -> { });
    }
}