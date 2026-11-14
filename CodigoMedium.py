import argparse
import os
import threading
import time

import cv2
import dlib
import numpy as np
from imutils import face_utils
from scipy.spatial import distance as dist

# Intentar importar playsound; si falla, recurrir a fallback
try:
    from playsound import playsound
    AUDIO_AVAILABLE = True
except Exception:
    AUDIO_AVAILABLE = False


def eye_aspect_ratio(eye):
    """
    Calcula el Eye Aspect Ratio (EAR) a partir de 6 puntos característicos del ojo.
    eye: arreglo numpy de forma (6, 2)
    """
    # Distancias euclidianas verticales
    a = dist.euclidean(eye[1], eye[5])
    b = dist.euclidean(eye[2], eye[4])
    # Distancia euclidiana horizontal
    c = dist.euclidean(eye[0], eye[3])
    
    # Evitar división por cero
    if c == 0:
        return 0.0
    return (a + b) / (2.0 * c)


def sound_alarm(path="alarm.wav"):
    """Reproduce el archivo de audio de alarma en un hilo separado o emite beeps."""
    if AUDIO_AVAILABLE and os.path.exists(path):
        try:
            playsound(path)
        except Exception as e:
            print(f"[ERROR] Error reproduciendo audio: {e}")
    else:
        for _ in range(3):
            print("\a", end="", flush=True)  # Beep de sistema
            time.sleep(0.3)


def parse_args():
    parser = argparse.ArgumentParser(description="Detector de somnolencia en tiempo real")
    parser.add_argument(
        "--shape",
        default="shape_predictor_68_face_landmarks.dat",
        help="Ruta al modelo dlib shape_predictor_68_face_landmarks.dat",
    )
    parser.add_argument(
        "--alarm",
        default="alarm.wav",
        help="Ruta al archivo de sonido de alarma",
    )
    parser.add_argument(
        "--yawn_model",
        default=None,
        help="(Opcional) Ruta al clasificador de bostezos en formato .h5 o SavedModel",
    )
    parser.add_argument(
        "--camera",
        type=int,
        default=0,
        help="Índice de la cámara / dispositivo de captura",
    )
    return parser.parse_args()


def main():
    args = parse_args()

    # Validar que exista el predictor facial
    if not os.path.exists(args.shape):
        raise FileNotFoundError(
            f"No se encontró el archivo de landmarks '{args.shape}'. "
            "Descárgalo del dlib model zoo y descomprímelo."
        )

    print("[INFO] Cargando detector y predictor facial...")
    detector = dlib.get_frontal_face_detector()
    predictor = dlib.shape_predictor(args.shape)

    # Índices de landmarks para ojos y boca
    (l_start, l_end) = face_utils.FACIAL_LANDMARKS_IDXS["left_eye"]
    (r_start, r_end) = face_utils.FACIAL_LANDMARKS_IDXS["right_eye"]
    (m_start, m_end) = face_utils.FACIAL_LANDMARKS_IDXS["mouth"]

    # Modelo de bostezo opcional
    yawn_model = None
    if args.yawn_model:
        if os.path.exists(args.yawn_model):
            from tensorflow.keras.models import load_model
            yawn_model = load_model(args.yawn_model)
            print(f"[INFO] Modelo de bostezos cargado: {args.yawn_model}")
        else:
            print(f"[WARN] No se encontró el modelo {args.yawn_model}, continuando sin detección de bostezo.")

    # Hiperparámetros
    ear_threshold = 0.25
    consec_frames = 20
    counter = 0
    alarm_on = False

    print("[INFO] Iniciando captura de video...")
    cap = cv2.VideoCapture(args.camera)
    fps_time = time.time()

    while True:
        ret, frame = cap.read()
        if not ret:
            break

        # Redimensionar para reducir costo computacional
        frame = cv2.resize(frame, (640, 480))
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)

        rects = detector(gray, 0)

        for rect in rects:
            shape = predictor(gray, rect)
            shape_np = face_utils.shape_to_np(shape)

            # Extraer coordenadas y calcular EAR
            left_eye = shape_np[l_start:l_end]
            right_eye = shape_np[r_start:r_end]
            left_ear = eye_aspect_ratio(left_eye)
            right_ear = eye_aspect_ratio(right_eye)
            ear = (left_ear + right_ear) / 2.0

            # Visualización contornos de ojos
            cv2.polylines(frame, [left_eye], True, (0, 255, 0), 1)
            cv2.polylines(frame, [right_eye], True, (0, 255, 0), 1)

            # Lógica de somnolencia por ojos cerrados
            if ear < ear_threshold:
                counter += 1
                if counter >= consec_frames:
                    if not alarm_on:
                        alarm_on = True
                        t = threading.Thread(target=sound_alarm, args=(args.alarm,), daemon=True)
                        t.start()
                    cv2.putText(
                        frame,
                        "DROWSINESS ALERT!",
                        (10, 30),
                        cv2.FONT_HERSHEY_SIMPLEX,
                        0.9,
                        (0, 0, 255),
                        2,
                    )
            else:
                counter = 0
                alarm_on = False

            # Detección opcional de bostezo con CNN
            mouth = shape_np[m_start:m_end]
            mouth_hull = cv2.convexHull(mouth)
            cv2.polylines(frame, [mouth_hull], True, (255, 0, 0), 1)

            if yawn_model is not None:
                (x, y, w, h) = cv2.boundingRect(mouth_hull)
                if w > 10 and h > 10:
                    # ROI con márgenes seguros
                    y1, y2 = max(0, y - 5), min(frame.shape[0], y + h + 5)
                    x1, x2 = max(0, x - 5), min(frame.shape[1], x + w + 5)
                    mouth_roi = frame[y1:y2, x1:x2]

                    if mouth_roi.size > 0:
                        mouth_resized = cv2.resize(mouth_roi, (224, 224))
                        mouth_norm = mouth_resized.astype("float32") / 255.0
                        mouth_exp = np.expand_dims(mouth_norm, axis=0)

                        pred = yawn_model.predict(mouth_exp, verbose=0)[0]
                        yawn_prob = pred[1] if len(pred) > 1 else pred[0]

                        cv2.putText(
                            frame,
                            f"YawnProb: {yawn_prob:.2f}",
                            (x, max(15, y - 10)),
                            cv2.FONT_HERSHEY_SIMPLEX,
                            0.6,
                            (255, 0, 0),
                            2,
                        )

                        if yawn_prob > 0.6:
                            cv2.putText(
                                frame,
                                "YAWNING!",
                                (10, 60),
                                cv2.FONT_HERSHEY_SIMPLEX,
                                0.8,
                                (0, 0, 255),
                                2,
                            )
                            if not alarm_on:
                                alarm_on = True
                                threading.Thread(
                                    target=sound_alarm, args=(args.alarm,), daemon=True
                                ).start()

            # Métrica EAR en pantalla
            cv2.putText(
                frame,
                f"EAR: {ear:.2f}",
                (480, 30),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.6,
                (0, 255, 255),
                2,
            )

        # Cálculo de FPS
        fps = 1.0 / (time.time() - fps_time)
        fps_time = time.time()
        cv2.putText(
            frame,
            f"FPS: {fps:.1f}",
            (10, 460),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.6,
            (0, 255, 0),
            2,
        )

        cv2.imshow("Drowsiness Monitor", frame)
        if cv2.waitKey(1) & 0xFF == ord("q"):
            break

    cap.release()
    cv2.destroyAllWindows()


if __name__ == "__main__":
    main()