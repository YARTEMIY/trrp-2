package org.example.client;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import org.example.AppConfig;
import org.example.crypto.CryptoUtils;
import org.example.grpc.*;
import org.example.model.Flight;

import javax.crypto.SecretKey;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ClientAppGRPC {
    public static void main(String[] args) {
        try {
            AppConfig config = AppConfig.get();
            System.out.println("Клиент gRPC запущен. Чтение SQLite...");

            List<Flight> data = new SqliteReader().readAll();
            if (data.isEmpty()) {
                System.out.println("Данных нет.");
                return;
            }

            ManagedChannel channel = ManagedChannelBuilder.forAddress(config.socketHost, config.socketPort)
                    .usePlaintext()
                    .build();

            FlightServiceGrpc.FlightServiceBlockingStub blockingStub = FlightServiceGrpc.newBlockingStub(channel);
            FlightServiceGrpc.FlightServiceStub asyncStub = FlightServiceGrpc.newStub(channel);

            System.out.println("Запрос публичного ключа...");
            PublicKeyResponse pkResponse = blockingStub.getPublicKey(Empty.newBuilder().build());
            byte[] pkBytes = pkResponse.getPemKey().toByteArray();

            X509EncodedKeySpec spec = new X509EncodedKeySpec(pkBytes);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            PublicKey serverPublic = kf.generatePublic(spec);

            SecretKey aesKey = CryptoUtils.generateAESKey();
            byte[] wrappedKey = CryptoUtils.wrapAESKey(aesKey, serverPublic);

            StatusResponse keyStatus = blockingStub.setSessionKey(SessionKeyRequest.newBuilder()
                    .setWrappedKey(ByteString.copyFrom(wrappedKey))
                    .build());

            if (!keyStatus.getSuccess()) {
                System.err.println("Ошибка согласования ключей: " + keyStatus.getMessage());
                return;
            }
            System.out.println("Ключи согласованы. Начинаем стриминг данных...");

            final CountDownLatch finishLatch = new CountDownLatch(1);

            StreamObserver<EncryptedPacket> requestObserver = asyncStub.streamFlights(new StreamObserver<>() {
                @Override
                public void onNext(StatusResponse value) {
                    System.out.println("Ответ сервера: " + value.getMessage());
                }

                @Override
                public void onError(Throwable t) {
                    System.err.println("Ошибка при отправке: " + t.getMessage());
                    finishLatch.countDown();
                }

                @Override
                public void onCompleted() {
                    System.out.println("Передача завершена.");
                    finishLatch.countDown();
                }
            });

            for (Flight f : data) {
                FlightData protoFlight = FlightData.newBuilder()
                        .setFlightNo(f.flightNo())
                        .setAirlineName(f.airlineName())
                        .setAircraftModel(f.aircraftModel())
                        .setDepCity(f.depCity())
                        .setDepCode(f.depCode())
                        .setArrCity(f.arrCity())
                        .setArrCode(f.arrCode())
                        .setPassengerName(f.passengerName())
                        .setPassportNo(f.passportNo())
                        .setFlightDate(f.flightDate())
                        .build();

                byte[] rawBytes = protoFlight.toByteArray();
                byte[] iv = CryptoUtils.generateIV();
                byte[] encrypted = CryptoUtils.encryptData(rawBytes, aesKey, iv);

                EncryptedPacket packet = EncryptedPacket.newBuilder()
                        .setIv(ByteString.copyFrom(iv))
                        .setEncryptedData(ByteString.copyFrom(encrypted))
                        .build();

                requestObserver.onNext(packet);
                System.out.print(".");
            }

            requestObserver.onCompleted();

            finishLatch.await(1, TimeUnit.MINUTES);
            channel.shutdown();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}