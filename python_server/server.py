import grpc
from concurrent import futures
import time
import psycopg2
import service_pb2
import service_pb2_grpc
from cryptography.hazmat.primitives.asymmetric import rsa, padding
from cryptography.hazmat.primitives import serialization, hashes
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.backends import default_backend

# Конфигурация БД (лучше вынести в конфиг файл, но для примера хардкод)
DB_CONFIG = {
    "dbname": "postgres",
    "user": "postgres",
    "password": "postgres", # Ваш пароль
    "host": "localhost"
}

class FlightService(service_pb2_grpc.FlightServiceServicer):
    def __init__(self):
        # Генерация RSA ключей при запуске
        self.private_key = rsa.generate_private_key(
            public_exponent=65537,
            key_size=2048,
            backend=default_backend()
        )
        self.public_key = self.private_key.public_key()
        self.aes_key = None # Будет установлен клиентом
        self.conn = psycopg2.connect(**DB_CONFIG)
        print("Server initialized. Connected to DB.")

    def GetPublicKey(self, request, context):
        print("Received Public Key Request")
        # Конвертация в формат X.509 (который понимает Java)
        pem = self.public_key.public_bytes(
            encoding=serialization.Encoding.DER, # Java любит DER (бинарный) или PEM. Protobuf bytes ест всё.
            format=serialization.PublicFormat.SubjectPublicKeyInfo
        )
        return service_pb2.PublicKeyResponse(pemKey=pem)

    def SetSessionKey(self, request, context):
        print("Received Session Key...")
        try:
            # Расшифровка AES ключа
            self.aes_key = self.private_key.decrypt(
                request.wrappedKey,
                padding.PKCS1v15() # Java Cipher "RSA" defaults to PKCS1v1.5
            )
            print("AES Key set successfully.")
            return service_pb2.StatusResponse(success=True, message="Key accepted")
        except Exception as e:
            print(f"Error decrypting key: {e}")
            return service_pb2.StatusResponse(success=False, message=str(e))

    def StreamFlights(self, request_iterator, context):
        # request_iterator - это и есть тот самый "массив", который мы читаем
        print("Starting to receive stream...")
        count = 0
        try:
            cursor = self.conn.cursor()
            
            # Читаем "пока есть массив" (пока идет поток)
            for packet in request_iterator:
                if not self.aes_key:
                    return service_pb2.StatusResponse(success=False, message="No Session Key")

                # Дешифровка
                cipher = Cipher(algorithms.AES(self.aes_key), modes.CBC(packet.iv), backend=default_backend())
                decryptor = cipher.decryptor()
                decrypted_padded = decryptor.update(packet.encryptedData) + decryptor.finalize()
                
                # Снятие PKCS5 padding (Java делает padding, Python должен снять)
                pad_len = decrypted_padded[-1]
                decrypted_bytes = decrypted_padded[:-pad_len]

                # Десериализация Protobuf
                flight_data = service_pb2.FlightData()
                flight_data.ParseFromString(decrypted_bytes)

                # Сохранение в БД (Нормализация)
                self.save_flight(cursor, flight_data)
                count += 1
                if count % 10 == 0:
                    print(f"Processed {count}...")
            
            self.conn.commit()
            cursor.close()
            print(f"Stream finished. Total: {count}")
            return service_pb2.StatusResponse(success=True, message=f"Imported {count} flights")
        
        except Exception as e:
            self.conn.rollback()
            print(f"Error processing stream: {e}")
            return service_pb2.StatusResponse(success=False, message=str(e))

    def save_flight(self, cur, dto):
        # Логика нормализации (аналог Java PostgresService)
        # 1. Airlines
        cur.execute("SELECT id FROM airlines WHERE name = %s", (dto.airlineName,))
        res = cur.fetchone()
        if res:
            airline_id = res[0]
        else:
            cur.execute("INSERT INTO airlines (name) VALUES (%s) RETURNING id", (dto.airlineName,))
            airline_id = cur.fetchone()[0]

        # 2. Aircrafts
        cur.execute("SELECT id FROM aircrafts WHERE model = %s", (dto.aircraftModel,))
        res = cur.fetchone()
        if res:
            aircraft_id = res[0]
        else:
            cur.execute("INSERT INTO aircrafts (model) VALUES (%s) RETURNING id", (dto.aircraftModel,))
            aircraft_id = cur.fetchone()[0]

        # 3. Airports (Dep/Arr)
        for code, city in [(dto.depCode, dto.depCity), (dto.arrCode, dto.arrCity)]:
            cur.execute("INSERT INTO airports (code, city) VALUES (%s, %s) ON CONFLICT (code) DO NOTHING", (code, city))
        
        # 4. Passengers
        cur.execute("SELECT id FROM passengers WHERE passport_no = %s", (dto.passportNo,))
        res = cur.fetchone()
        if res:
            pass_id = res[0]
        else:
            cur.execute("INSERT INTO passengers (passport_no, full_name) VALUES (%s, %s) RETURNING id", (dto.passportNo, dto.passengerName))
            pass_id = cur.fetchone()[0]

        # 5. Insert Flight
        # flightDate ожидается как "YYYY-MM-DD", добавляем время
        ts = dto.flightDate + " 00:00:00"
        cur.execute("""
            INSERT INTO flights 
            (flight_no, date, airline_id, aircraft_id, dep_airport_code, arr_airport_code, passenger_id)
            VALUES (%s, %s, %s, %s, %s, %s, %s)
        """, (dto.flightNo, ts, airline_id, aircraft_id, dto.depCode, dto.arrCode, pass_id))


def serve():
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=10))
    service_pb2_grpc.add_FlightServiceServicer_to_server(FlightService(), server)
    
    # Порт из конфига (hardcoded for brevity)
    port = 8899
    server.add_insecure_port(f'[::]:{port}')
    print(f"Python gRPC Server started on port {port}")
    server.start()
    try:
        while True:
            time.sleep(86400)
    except KeyboardInterrupt:
        server.stop(0)

if __name__ == '__main__':
    serve()