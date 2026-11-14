import argparse
import bz2
from collections import deque
import os
import platform
import shutil
import subprocess
import sys
import threading
import time
import urllib.request
import wave

import cv2
import dlib
import numpy as np
from imutils import face_utils
from scipy.spatial import distance as dist

# Directorio base del script para que funcione sin importar desde qué carpeta se ejecute
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
SHAPE_PREDICTOR_URL = "https://dlib.net/files/shape_predictor_68_face_landmarks.dat.bz2"

# Intentar importar playsound; si falla, recurrir a reproductores nativos de Windows/macOS/Linux
try:
    from playsound import playsound
    AUDIO_AVAILABLE = True
except Exception:
    AUDIO_AVAILABLE = False

ALARM_LOCK = threading.Lock()


def preprocess_gray_clahe(frame, clahe, use_clahe=True, face_box=None, target_lum=110.0):
    """
    Preprocesamiento en el dominio espacial (TDI) enfocado exclusivamente en el rostro:
      1. Convierte el frame BGR a escala de grises.
      2. Mide la iluminación ADENTRO del recuadro del rostro detectado (ignorando el fondo),
         ponderando la media (40%) y el percentil 75 (60%) para detectar reflejos fuertes
         o luz directa sobre un lado de la cara.
      3. Si hay exceso de luz (ref_lum > target_lum), comprime las altas luces quemadas
         y aplica Corrección Gamma (gamma > 1.0, hasta 3.0) para oscurecer y recuperar
         los bordes de párpados, pestañas y labios.
      4. Aplica CLAHE local para uniformizar sombras laterales.
    Retorna: (gray_procesada, gamma_aplicado, luminancia_rostro)
    """
    gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
    h, w = gray.shape

    # Si tenemos las coordenadas del rostro, medimos la luz SOLO en la cara (sin el fondo)
    if face_box is not None:
        fx1, fy1, fx2, fy2 = face_box
        fx1, fy1 = max(0, fx1), max(0, fy1)
        fx2, fy2 = min(w, fx2), min(h, fy2)
        roi = gray[fy1:fy2, fx1:fx2]
    else:
        roi = np.array([])

    if roi.size < 100:
        # Fallback al centro de la imagen solo cuando todavía no hay rostro en cuadro
        roi = gray[int(h * 0.25):int(h * 0.75), int(w * 0.30):int(w * 0.70)]

    mean_lum = float(np.mean(roi))
    p75_lum = float(np.percentile(roi, 75))
    # Ponderar el percentil 75 permite detectar cuando un foco de luz quema parte de la cara
    ref_lum = 0.40 * mean_lum + 0.60 * p75_lum

    if not use_clahe:
        return gray, 1.0, ref_lum

    # Calcular exponente gamma adaptativo para llevar ref_lum hacia target_lum (110)
    norm_ref = np.clip(ref_lum / 255.0, 0.05, 0.96)
    norm_target = np.clip(target_lum / 255.0, 0.05, 0.96)
    gamma = float(np.clip(np.log(norm_target) / np.log(norm_ref), 0.45, 3.00))

    # Construir LUT con compresión suave de altas luces (evita blancos saturados > 210) y Gamma
    x = np.arange(256, dtype=np.float32) / 255.0
    if gamma > 1.15:
        # Si hay mucha luz en la cara, atenuar el tope de blancos antes de aplicar la curva gamma
        x = np.where(x > 0.75, 0.75 + (x - 0.75) * 0.55, x)
    lut = np.clip((x ** gamma) * 255.0, 0, 255).astype(np.uint8)
    gray_gamma = cv2.LUT(gray, lut)

    # Aplicar CLAHE sobre la imagen ya compensada en exposición
    gray_enhanced = clahe.apply(gray_gamma)
    return gray_enhanced, gamma, ref_lum


def eye_aspect_ratio(eye):
    """
    Calcula el Eye Aspect Ratio (EAR) a partir de los 6 puntos característicos del ojo.
    eye: arreglo numpy de forma (6, 2)
    """
    # Distancias euclidianas verticales
    vertical_1 = dist.euclidean(eye[1], eye[5])
    vertical_2 = dist.euclidean(eye[2], eye[4])
    # Distancia euclidiana horizontal
    horizontal = dist.euclidean(eye[0], eye[3])

    if horizontal == 0:
        return 0.0
    return (vertical_1 + vertical_2) / (2.0 * horizontal)


def mouth_aspect_ratio(inner_mouth):
    """
    Calcula el Mouth Aspect Ratio (MAR) usando los 8 puntos del labio interno (landmarks 60-67).
    inner_mouth: arreglo numpy de forma (8, 2)
      - Puntos verticales: (1, 7), (2, 6), (3, 5) -> landmarks (61,67), (62,66), (63,65)
      - Puntos horizontales (comisuras internas): (0, 4) -> landmarks (60, 64)
    """
    vertical_1 = dist.euclidean(inner_mouth[1], inner_mouth[7])
    vertical_2 = dist.euclidean(inner_mouth[2], inner_mouth[6])
    vertical_3 = dist.euclidean(inner_mouth[3], inner_mouth[5])
    horizontal = dist.euclidean(inner_mouth[0], inner_mouth[4])

    if horizontal == 0:
        return 0.0
    return (vertical_1 + vertical_2 + vertical_3) / (3.0 * horizontal)


def compute_head_pose_metrics(landmarks):
    """
    Estima la postura de la cabeza en 3 ejes (Pitch, Roll y Yaw) a partir de landmarks 2D:
      - roll_deg: ángulo de inclinación lateral hacia los hombros (en grados) con la línea interocular.
      - pitch_ratio: razón proyectiva vertical ||p30 - p27|| / ||p8 - p30|| (cabeceo hacia abajo).
      - yaw_ratio: asimetría horizontal (d_L - d_R) / (d_L + d_R) entre la punta de la nariz (p30)
        y los bordes laterales del rostro (p0 derecho, p16 izquierdo).
          * yaw_ratio ~ 0.0 -> mirando de frente
          * |yaw_ratio| > 0.18 -> rostro girado hacia un costado (un ojo queda más expuesto a cámara)
    """
    right_eye_center = landmarks[36:42].mean(axis=0)
    left_eye_center = landmarks[42:48].mean(axis=0)

    dy = left_eye_center[1] - right_eye_center[1]
    dx = left_eye_center[0] - right_eye_center[0]
    roll_deg = float(np.degrees(np.arctan2(dy, max(abs(dx), 1e-6))))

    nose_bridge = landmarks[27]  # Entrecejo
    nose_tip = landmarks[30]     # Punta de la nariz
    chin = landmarks[8]          # Mentón
    jaw_right = landmarks[0]     # Contorno lateral del lado del ojo derecho (36-41)
    jaw_left = landmarks[16]     # Contorno lateral del lado del ojo izquierdo (42-47)

    nose_len = dist.euclidean(nose_tip, nose_bridge)
    nose_to_chin = dist.euclidean(chin, nose_tip)
    pitch_ratio = nose_len / max(nose_to_chin, 1e-6)

    # Distancias horizontales desde la punta de la nariz a cada borde de la cara
    dist_r = max(float(nose_tip[0] - jaw_right[0]), 1.0)
    dist_l = max(float(jaw_left[0] - nose_tip[0]), 1.0)
    yaw_ratio = (dist_l - dist_r) / (dist_l + dist_r)

    return (
        roll_deg,
        pitch_ratio,
        yaw_ratio,
        tuple(nose_bridge.astype(int)),
        tuple(nose_tip.astype(int)),
        tuple(chin.astype(int)),
    )


def compute_perspective_compensated_ear(left_ear, right_ear, yaw_ratio, yaw_thresh=0.18):
    """
    Compensa el EAR frente a giros laterales de la cabeza (Yaw):
      - Cuando el usuario mira de frente (|yaw_ratio| <= yaw_thresh), ambos ojos se ven
        con igual perspectiva y se usa el promedio simétrico (50% / 50%).
      - Cuando gira la cara hacia un costado (|yaw_ratio| > yaw_thresh), el ojo más alejado
        sufre escorzo (se achica por perspectiva dando falsos positivos), mientras que el ojo
        del lado más ancho (expuesto a la cámara) se ve claramente.
      - Se pondera con 85% el ojo expuesto a cámara y 15% el ojo en escorzo: así no da falsa
        alarma al girar la cabeza con ojos abiertos (ej. cobrar un boleto o mirar espejos),
        pero SÍ detecta si la persona cierra los ojos y se duerme con el cuello girado.
    """
    if yaw_ratio > yaw_thresh:
        # El lado de left_eye (42-47) está más expuesto hacia la cámara
        return 0.85 * left_ear + 0.15 * right_ear
    elif yaw_ratio < -yaw_thresh:
        # El lado de right_eye (36-41) está más expuesto hacia la cámara
        return 0.85 * right_ear + 0.15 * left_ear
    else:
        return (left_ear + right_ear) / 2.0


def ema_filter(current_val, prev_val, alpha):
    """
    Filtro pasa-bajos temporal de Media Móvil Exponencial (EMA):
      x_suave(t) = alpha * x(t) + (1 - alpha) * x_suave(t - 1)
    Elimina el temblor (jitter) de alta frecuencia de los landmarks de dlib.
    """
    if prev_val is None:
        return float(current_val)
    return float(alpha * current_val + (1.0 - alpha) * prev_val)


def classify_eye_state(ear, ear_active_thresh=0.25, ear_sleep_thresh=0.21):
    """
    Clasifica la apertura ocular instantánea en 3 niveles (inspirado en driver_drowsiness.py):
      2 -> Activo / Ojos bien abiertos (EAR > 0.25)
      1 -> Fatiga inicial / Entrecerrados (0.21 < EAR <= 0.25)
      0 -> Ojos cerrados (EAR <= 0.21)
    """
    if ear > ear_active_thresh:
        return 2
    elif ear > ear_sleep_thresh:
        return 1
    else:
        return 0


def update_perclos(history, now_ts, is_closed, window_sec=30.0):
    """
    Actualiza la ventana deslizante temporal y calcula PERCLOS (%).
    history: deque de tuplas (timestamp, is_closed) donde is_closed es 1 (cerrado) o 0 (abierto)
    window_sec: duración de la ventana móvil en segundos
    """
    history.append((now_ts, 1 if is_closed else 0))
    while history and (now_ts - history[0][0]) > window_sec:
        history.popleft()
    if not history:
        return 0.0
    closed_frames = sum(val for _, val in history)
    return (closed_frames / len(history)) * 100.0


def ensure_alarm_wav(path="alarma.wav", sample_rate=44100):
    """
    Si no existe el archivo 'alarma.wav', sintetiza automáticamente un patrón
    de alarma sonora (3 pulsos bitonales de 880 Hz / 1320 Hz) en formato WAV PCM 16-bit.
    """
    if os.path.exists(path):
        return path

    pulses = []
    for i in range(3):
        freq = 880.0 if i % 2 == 0 else 1320.0
        duration = 0.18
        t = np.linspace(0, duration, int(sample_rate * duration), endpoint=False)
        # Envolvente suave para evitar chasquidos de audio
        envelope = np.ones_like(t)
        fade = int(0.015 * sample_rate)
        envelope[:fade] = np.linspace(0, 1, fade)
        envelope[-fade:] = np.linspace(1, 0, fade)
        tone = 0.65 * envelope * np.sin(2.0 * np.pi * freq * t)
        silence = np.zeros(int(sample_rate * 0.07))
        pulses.extend([tone, silence])

    audio = np.concatenate(pulses)
    pcm16 = np.int16(np.clip(audio, -1.0, 1.0) * 32767)

    with wave.open(path, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sample_rate)
        wf.writeframes(pcm16.tobytes())

    print(f"[INFO] Archivo de sonido '{path}' generado automáticamente.")
    return path


def ensure_shape_predictor(path):
    """
    Verifica que exista el archivo 'shape_predictor_68_face_landmarks.dat'.
    Si no está presente en la computadora (por ejemplo al clonar de GitHub o pasar por ZIP),
    lo descarga automáticamente desde dlib.net (.bz2) y lo descomprime usando Python puro,
    funcionando en Windows, Linux y macOS sin herramientas externas.
    """
    if os.path.exists(path):
        return path

    bz2_path = path + ".bz2"
    print(f"[INFO] No se encontró '{os.path.basename(path)}'.")
    print(f"[INFO] Descargando modelo oficial de 68 landmarks (~61 MB) desde {SHAPE_PREDICTOR_URL} ...")

    def _report_progress(block_num, block_size, total_size):
        if total_size > 0:
            pct = min(block_num * block_size * 100.0 / total_size, 100.0)
            sys.stdout.write(f"\r[DESCAGANDO] {pct:5.1f}%")
            sys.stdout.flush()

    urllib.request.urlretrieve(SHAPE_PREDICTOR_URL, bz2_path, reporthook=_report_progress)
    print("\n[INFO] Descomprimiendo archivo .dat (esto demora unos segundos)...")
    with bz2.BZ2File(bz2_path, "rb") as src, open(path, "wb") as dst:
        shutil.copyfileobj(src, dst, length=1024 * 1024)

    try:
        os.remove(bz2_path)
    except OSError:
        pass

    print(f"[INFO] Modelo listo en: {path}")
    return path


def sound_alarm(path):
    """
    Reproduce el archivo 'alarma.wav' en un hilo separado sin bloquear el video,
    compatible con Windows (winsound), macOS (afplay) y Linux (pw-play / paplay / aplay).
    """
    if not ALARM_LOCK.acquire(blocking=False):
        return  # Ya hay una alarma sonando actualmente
    try:
        ensure_alarm_wav(path)
        sys_name = platform.system()
        if AUDIO_AVAILABLE and os.path.exists(path):
            playsound(path)
        elif sys_name == "Windows" and os.path.exists(path):
            import winsound
            winsound.PlaySound(path, winsound.SND_FILENAME)
        elif sys_name == "Darwin" and shutil.which("afplay") and os.path.exists(path):
            subprocess.run(
                ["afplay", path],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=False,
            )
        elif shutil.which("pw-play") and os.path.exists(path):
            subprocess.run(
                ["pw-play", path],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=False,
            )
        elif shutil.which("paplay") and os.path.exists(path):
            subprocess.run(
                ["paplay", path],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=False,
            )
        elif shutil.which("aplay") and os.path.exists(path):
            subprocess.run(
                ["aplay", "-q", path],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=False,
            )
        else:
            for _ in range(3):
                print("\a", end="", flush=True)
                time.sleep(0.3)
    except Exception as e:
        print(f"[ERROR] Error reproduciendo audio: {e}")
    finally:
        ALARM_LOCK.release()


def open_camera(preferred_index=0):
    """
    Intenta abrir el índice de cámara indicado; si falla en otra computadora
    (por cámaras virtuales o diferente orden de dispositivos), prueba índices 0, 1 y 2.
    """
    candidates = [preferred_index] + [i for i in (0, 1, 2) if i != preferred_index]
    for idx in candidates:
        if platform.system() == "Windows":
            cap = cv2.VideoCapture(idx, cv2.CAP_DSHOW)
            if not cap.isOpened():
                cap = cv2.VideoCapture(idx)
        else:
            cap = cv2.VideoCapture(idx)

        if cap.isOpened():
            ret, _ = cap.read()
            if ret:
                if idx != preferred_index:
                    print(f"[INFO] Cámara {preferred_index} no disponible; usando cámara {idx}.")
                return cap
            cap.release()
    raise RuntimeError(
        "No se pudo abrir ninguna cámara web (índices probados: 0, 1, 2). "
        "Verificá que la cámara esté conectada y no esté siendo usada por otro programa."
    )


def parse_args():
    parser = argparse.ArgumentParser(
        description="EspecialistaEnDescanso: Sistema de detección de somnolencia en tiempo real"
    )
    parser.add_argument(
        "--shape",
        default=os.path.join(SCRIPT_DIR, "shape_predictor_68_face_landmarks.dat"),
        help="Ruta al modelo dlib shape_predictor_68_face_landmarks.dat",
    )
    parser.add_argument(
        "--alarm",
        default=os.path.join(SCRIPT_DIR, "alarma.wav"),
        help="Ruta al archivo de sonido de alarma (por defecto: alarma.wav)",
    )
    parser.add_argument(
        "--camera",
        type=int,
        default=0,
        help="Índice de la cámara / dispositivo de captura",
    )
    parser.add_argument(
        "--perclos-window",
        type=float,
        default=30.0,
        help="Ventana temporal en segundos para el cálculo de PERCLOS (por defecto: 30s)",
    )
    return parser.parse_args()


def main():
    args = parse_args()
    ensure_alarm_wav(args.alarm)
    ensure_shape_predictor(args.shape)

    print("[INFO] Cargando detector facial y predictor de 68 landmarks...")
    detector = dlib.get_frontal_face_detector()
    predictor = dlib.shape_predictor(args.shape)

    # Inicializar operador CLAHE moderado para no saturar zonas brillantes (TDI)
    clahe = cv2.createCLAHE(clipLimit=1.5, tileGridSize=(8, 8))
    use_clahe = True

    # Índices de landmarks para ojos y boca (de CodigoMedium.py + inner_mouth para MAR)
    (l_start, l_end) = face_utils.FACIAL_LANDMARKS_IDXS["left_eye"]
    (r_start, r_end) = face_utils.FACIAL_LANDMARKS_IDXS["right_eye"]
    (m_start, m_end) = face_utils.FACIAL_LANDMARKS_IDXS["mouth"]
    (im_start, im_end) = face_utils.FACIAL_LANDMARKS_IDXS["inner_mouth"]

    # Umbrales analíticos de EAR y contadores por estado (fusión de ambos códigos)
    ear_active_thresh = 0.25   # Por encima: Activo
    ear_sleep_thresh = 0.21    # Por debajo: Ojos cerrados (entre 0.21 y 0.25: Somnoliento)
    drowsy_consec_frames = 10  # Frames consecutivos para declarar fatiga inicial
    sleep_consec_frames = 15   # Frames consecutivos para declarar sueño / disparar alarma
    active_consec_frames = 6   # Frames consecutivos para confirmar estado activo

    # Umbral analítico de MAR (Mouth Aspect Ratio) y contadores para bostezo
    mar_yawn_thresh = 0.50     # Por encima de 0.50: boca ampliamente abierta
    yawn_consec_frames = 15    # Frames consecutivos para distinguir bostezo de hablar

    # Parámetros y ventana deslizante de PERCLOS (%)
    perclos_window_sec = args.perclos_window
    perclos_drowsy_thresh = 15.0  # >= 15% de cierre en la ventana -> Fatiga acumulada
    perclos_sleep_thresh = 25.0   # >= 25% de cierre en la ventana -> Somnolencia severa
    perclos_history = deque()

    # Umbrales analíticos de postura cefálica (cabeceo hacia abajo e inclinación lateral)
    pitch_nod_thresh = 0.88       # Razón puente_nasal / nariz_mentón indicativa de cabeceo
    roll_tilt_thresh = 16.0       # Inclinación lateral en grados (> 16° hacia un hombro)
    head_consec_frames = 12       # Frames consecutivos para confirmar cabeceo / inclinación

    sleep_counter = 0
    drowsy_counter = 0
    active_counter = 0

    # Contador de parpadeos (transición de ojo cerrado >= 2 frames a ojo abierto)
    blink_min_frames = 2
    blink_frame_counter = 0
    total_blinks = 0

    yawn_counter = 0
    total_yawns = 0
    yawn_counted = False

    head_counter = 0
    total_nods = 0
    nod_counted = False

    # Estado de calibración automática inicial (3 segundos mirando al frente, o tecla 'c')
    calibration_duration = 3.0
    is_calibrating = True
    calib_start_ts = None
    calib_ears = []
    calib_pitches = []
    ear_base = None
    last_face_box = None

    # Filtro pasa-bajos temporal (EMA) para eliminar jitter (alternable con tecla 'f')
    use_ema = True
    alpha_ear = 0.60   # Suavizado ligero para EAR (mantiene baja latencia)
    alpha_slow = 0.35  # Suavizado más firme para movimientos lentos (boca y postura cefálica)
    ema_ear = None
    ema_mar = None
    ema_pitch = None
    ema_roll = None
    ema_yaw = None

    status = "Iniciando..."
    status_color = (255, 255, 255)
    alarm_on = False

    print(
        "[INFO] Iniciando captura de video "
        "(tecla 'c': recalibrar | tecla 'e': CLAHE | tecla 'f': Filtro EMA | 'q'/'ESC': salir)..."
    )
    cap = open_camera(args.camera)
    fps_time = time.time()

    while True:
        ret, frame = cap.read()
        if not ret:
            break

        now_ts = time.time()

        # Redimensionar para mantener costo computacional uniforme (de CodigoMedium.py)
        frame = cv2.resize(frame, (640, 480))

        # Preprocesamiento TDI midiendo la luz exclusivamente dentro del rostro detectado
        gray, current_gamma, mean_lum = preprocess_gray_clahe(
            frame, clahe, use_clahe=use_clahe, face_box=last_face_box
        )

        # Usar la imagen preprocesada en la ventana del detector para visualizar el efecto de Gamma + CLAHE
        face_frame = cv2.cvtColor(gray, cv2.COLOR_GRAY2BGR)

        raw_faces = detector(gray, 0)

        # Filtrar para evaluar ÚNICAMENTE a 1 persona: el rostro principal (mayor área = más cercano)
        if len(raw_faces) > 0:
            main_face = max(
                raw_faces,
                key=lambda r: (r.right() - r.left()) * (r.bottom() - r.top()),
            )
            faces = [main_face]
        else:
            faces = []
            last_face_box = None

        for face in faces:
            # Recuadro del rostro en la vista de detección (de driver_drowsiness.py)
            x1, y1, x2, y2 = face.left(), face.top(), face.right(), face.bottom()
            last_face_box = (x1, y1, x2, y2)
            cv2.rectangle(face_frame, (x1, y1), (x2, y2), (0, 255, 0), 2)

            # Extracción de los 68 landmarks
            shape = predictor(gray, face)
            landmarks = face_utils.shape_to_np(shape)

            # Extraer regiones oculares y calcular EAR individual de cada ojo
            left_eye = landmarks[l_start:l_end]
            right_eye = landmarks[r_start:r_end]
            left_ear = eye_aspect_ratio(left_eye)
            right_ear = eye_aspect_ratio(right_eye)

            # Extraer boca y calcular MAR instantáneo (Mouth Aspect Ratio) con el labio interno
            mouth = landmarks[m_start:m_end]
            inner_mouth = landmarks[im_start:im_end]
            mar_raw = mouth_aspect_ratio(inner_mouth)

            # Calcular postura cefálica instantánea en 3 ejes: Roll, Pitch y Yaw (giro lateral)
            (
                roll_raw,
                pitch_raw,
                yaw_raw,
                pt_bridge,
                pt_nose,
                pt_chin,
            ) = compute_head_pose_metrics(landmarks)

            # Calcular EAR instantáneo compensado por perspectiva ante giros laterales (Yaw)
            ear_raw = compute_perspective_compensated_ear(
                left_ear, right_ear, yaw_raw, yaw_thresh=0.18
            )

            # Actualizar filtro pasa-bajos temporal (EMA) y elegir señales según use_ema (tecla 'f')
            ema_ear = ema_filter(ear_raw, ema_ear, alpha_ear)
            ema_mar = ema_filter(mar_raw, ema_mar, alpha_slow)
            ema_pitch = ema_filter(pitch_raw, ema_pitch, alpha_slow)
            ema_roll = ema_filter(roll_raw, ema_roll, alpha_slow)
            ema_yaw = ema_filter(yaw_raw, ema_yaw, alpha_slow)

            if use_ema:
                ear = ema_ear
                mar = ema_mar
                pitch_ratio = ema_pitch
                roll_deg = ema_roll
                yaw_ratio = ema_yaw
            else:
                ear = ear_raw
                mar = mar_raw
                pitch_ratio = pitch_raw
                roll_deg = roll_raw
                yaw_ratio = yaw_raw

            # Fase de calibración personalizada (3 segundos iniciales o al presionar 'c')
            if is_calibrating:
                if calib_start_ts is None:
                    calib_start_ts = now_ts
                elapsed_calib = now_ts - calib_start_ts
                if ear_raw > 0.16:  # Descartar si justo parpadeó durante la toma de muestras
                    calib_ears.append(ear_raw)
                calib_pitches.append(pitch_raw)

                progress = min(elapsed_calib / calibration_duration, 1.0)
                status = f"CALIBRANDO... {int(progress * 100)}%"
                status_color = (255, 255, 0)  # Cian

                # Barra de progreso de calibración en la parte superior
                cv2.rectangle(frame, (10, 50), (310, 68), (200, 200, 200), 1)
                cv2.rectangle(
                    frame,
                    (11, 51),
                    (10 + int(progress * 299), 67),
                    (255, 255, 0),
                    -1,
                )
                cv2.putText(
                    frame,
                    "Mira al frente normalmente (3s)",
                    (10, 90),
                    cv2.FONT_HERSHEY_SIMPLEX,
                    0.6,
                    (255, 255, 0),
                    2,
                )

                if elapsed_calib >= calibration_duration and len(calib_ears) >= 5:
                    ear_base = float(np.median(calib_ears))
                    pitch_base = float(np.median(calib_pitches))
                    # Ajustar umbrales proporcionales a la fisonomía del usuario
                    ear_active_thresh = round(ear_base * 0.82, 3)
                    ear_sleep_thresh = round(ear_base * 0.70, 3)
                    pitch_nod_thresh = round(pitch_base * 1.25, 3)
                    is_calibrating = False
                    perclos_history.clear()
                    sleep_counter = drowsy_counter = active_counter = 0
                    print(
                        f"[INFO] Calibración completada -> EAR base: {ear_base:.3f} | "
                        f"Umbral Activo: {ear_active_thresh:.3f} | "
                        f"Umbral Cerrado: {ear_sleep_thresh:.3f} | "
                        f"Umbral Pitch: {pitch_nod_thresh:.3f}"
                    )

            head_anomaly = (pitch_ratio > pitch_nod_thresh) or (abs(roll_deg) > roll_tilt_thresh)

            # Clasificar apertura ocular para fatiga/sueño (con EAR suavizado o crudo según 'f')
            eye_state = classify_eye_state(ear, ear_active_thresh, ear_sleep_thresh)
            # Para el contador de parpadeos usamos SIEMPRE ear_raw (instantáneo) para no perder parpadeos rápidos
            eye_closed_raw = classify_eye_state(ear_raw, ear_active_thresh, ear_sleep_thresh) == 0
            eye_closed = (eye_state == 0)

            # Conteo de parpadeos y actualización de PERCLOS (solo una vez terminada la calibración)
            if not is_calibrating:
                if eye_closed_raw:
                    blink_frame_counter += 1
                else:
                    if blink_frame_counter >= blink_min_frames:
                        total_blinks += 1
                    blink_frame_counter = 0

                perclos = update_perclos(perclos_history, now_ts, eye_closed, perclos_window_sec)
            else:
                perclos = 0.0

            # Evaluación temporal combinando cierre continuo (EAR compensado) y fatiga acumulada (PERCLOS)
            if not is_calibrating:
                if eye_closed:
                    sleep_counter += 1
                    drowsy_counter = 0
                    active_counter = 0
                elif eye_state == 1:
                    sleep_counter = 0
                    active_counter = 0
                    drowsy_counter += 1
                else:
                    drowsy_counter = 0
                    sleep_counter = 0
                    active_counter += 1

                if sleep_counter >= sleep_consec_frames or perclos >= perclos_sleep_thresh:
                    status = (
                        "DURMIENDO !!!"
                        if sleep_counter >= sleep_consec_frames
                        else "SOMNOLENCIA (PERCLOS)!"
                    )
                    status_color = (0, 0, 255)  # Rojo en BGR
                    if not alarm_on:
                        alarm_on = True
                        threading.Thread(
                            target=sound_alarm, args=(args.alarm,), daemon=True
                        ).start()

                elif drowsy_counter >= drowsy_consec_frames or perclos >= perclos_drowsy_thresh:
                    status = "Somnoliento (Fatiga) !"
                    status_color = (0, 165, 255)  # Naranja en BGR
                    if mar <= mar_yawn_thresh and not head_anomaly:
                        alarm_on = False

                else:
                    if mar <= mar_yawn_thresh and not head_anomaly:
                        alarm_on = False
                    if active_counter >= active_consec_frames:
                        status = "Activo :)"
                        status_color = (0, 255, 0)  # Verde en BGR

            # Evaluación temporal de bostezo mediante MAR
            mouth_color = (255, 0, 0)  # Azul por defecto
            if mar > mar_yawn_thresh:
                yawn_counter += 1
                mouth_color = (0, 165, 255)  # Naranja mientras abre la boca
                if yawn_counter >= yawn_consec_frames:
                    mouth_color = (0, 0, 255)  # Rojo al confirmar bostezo
                    if not yawn_counted:
                        total_yawns += 1
                        yawn_counted = True
                    cv2.putText(
                        frame,
                        "ALERTA: BOSTEZO DETECTADO!",
                        (10, 70),
                        cv2.FONT_HERSHEY_SIMPLEX,
                        0.75,
                        (0, 0, 255),
                        2,
                    )
                    if not alarm_on:
                        alarm_on = True
                        threading.Thread(
                            target=sound_alarm, args=(args.alarm,), daemon=True
                        ).start()
            else:
                yawn_counter = 0
                yawn_counted = False

            # Evaluación temporal de cabeceo (Pitch) e inclinación lateral (Roll)
            axis_color = (255, 255, 0)  # Cian por defecto para el eje facial
            if head_anomaly:
                head_counter += 1
                axis_color = (0, 165, 255)  # Naranja mientras empieza a inclinar/cabecear
                if head_counter >= head_consec_frames:
                    axis_color = (0, 0, 255)  # Rojo al confirmar postura anómala
                    if not nod_counted:
                        total_nods += 1
                        nod_counted = True
                    head_alert_msg = (
                        "ALERTA: CABECEO DETECTADO!"
                        if pitch_ratio > pitch_nod_thresh
                        else "ALERTA: INCLINACION LATERAL!"
                    )
                    cv2.putText(
                        frame,
                        head_alert_msg,
                        (10, 105),
                        cv2.FONT_HERSHEY_SIMPLEX,
                        0.75,
                        (0, 0, 255),
                        2,
                    )
                    if not alarm_on:
                        alarm_on = True
                        threading.Thread(
                            target=sound_alarm, args=(args.alarm,), daemon=True
                        ).start()
            else:
                head_counter = 0
                nod_counted = False

            # Visualización de contornos de ojos (resaltando el ojo expuesto a cámara si hay giro Yaw)
            if yaw_ratio > 0.18:
                left_eye_color = (0, 255, 0)      # Ojo izquierdo dominante (frente a cámara)
                right_eye_color = (160, 160, 100) # Ojo derecho en escorzo (menor peso)
            elif yaw_ratio < -0.18:
                left_eye_color = (160, 160, 100)  # Ojo izquierdo en escorzo (menor peso)
                right_eye_color = (0, 255, 0)     # Ojo derecho dominante (frente a cámara)
            else:
                left_eye_color = (0, 255, 0)
                right_eye_color = (0, 255, 0)

            cv2.polylines(frame, [left_eye], True, left_eye_color, 1)
            cv2.polylines(frame, [right_eye], True, right_eye_color, 1)

            mouth_hull = cv2.convexHull(mouth)
            cv2.polylines(frame, [mouth_hull], True, mouth_color, 1)
            inner_mouth_hull = cv2.convexHull(inner_mouth)
            cv2.polylines(frame, [inner_mouth_hull], True, mouth_color, 1)

            # Eje vertical del rostro (entrecejo -> punta de nariz -> mentón)
            cv2.line(frame, pt_bridge, pt_nose, axis_color, 2)
            cv2.line(frame, pt_nose, pt_chin, axis_color, 2)
            cv2.circle(frame, pt_nose, 3, (0, 0, 255), -1)

            # Dibujar los 68 puntos en la ventana secundaria del detector (de driver_drowsiness.py)
            for (lx, ly) in landmarks:
                cv2.circle(face_frame, (lx, ly), 2, (0, 255, 255), -1)

            # Mostrar estado y métricas EAR / MAR / PERCLOS / Postura en pantalla
            cv2.putText(
                frame,
                status,
                (10, 35),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.9,
                status_color,
                2,
            )
            cv2.putText(
                frame,
                f"EAR: {ear:.2f}",
                (450, 35),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.65,
                (0, 255, 255),
                2,
            )
            cv2.putText(
                frame,
                f"MAR: {mar:.2f}",
                (450, 65),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.65,
                (255, 255, 0),
                2,
            )
            cv2.putText(
                frame,
                f"Bostezos: {total_yawns}",
                (450, 95),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.65,
                (0, 165, 255),
                2,
            )

            # Color dinámico e indicador visual para PERCLOS
            if perclos >= perclos_sleep_thresh:
                perclos_color = (0, 0, 255)
            elif perclos >= perclos_drowsy_thresh:
                perclos_color = (0, 165, 255)
            else:
                perclos_color = (0, 255, 0)

            cv2.putText(
                frame,
                f"PERCLOS: {perclos:.1f}%",
                (450, 125),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.65,
                perclos_color,
                2,
            )
            # Barra de progreso de PERCLOS (escala 0% a 50% para visualización clara)
            bar_x, bar_y, bar_w, bar_h = 450, 135, 170, 12
            cv2.rectangle(frame, (bar_x, bar_y), (bar_x + bar_w, bar_y + bar_h), (200, 200, 200), 1)
            fill_w = int(min(perclos / 50.0, 1.0) * bar_w)
            if fill_w > 0:
                cv2.rectangle(
                    frame,
                    (bar_x + 1, bar_y + 1),
                    (bar_x + fill_w, bar_y + bar_h - 1),
                    perclos_color,
                    -1,
                )

            # Métricas de Postura Cefálica (Pitch, Roll, Yaw y contador de cabeceos)
            cv2.putText(
                frame,
                f"Pitch: {pitch_ratio:.2f}",
                (450, 170),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.6,
                axis_color,
                2,
            )
            cv2.putText(
                frame,
                f"Roll: {roll_deg:+.1f} deg",
                (450, 195),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.6,
                axis_color,
                2,
            )
            yaw_color = (0, 255, 255) if abs(yaw_ratio) > 0.18 else (200, 200, 200)
            cv2.putText(
                frame,
                f"Yaw (Giro): {yaw_ratio:+.2f}",
                (450, 220),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.6,
                yaw_color,
                2,
            )
            cv2.putText(
                frame,
                f"Cabeceos: {total_nods}",
                (450, 245),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.6,
                (0, 165, 255),
                2,
            )
            cv2.putText(
                frame,
                f"Parpadeos: {total_blinks}",
                (450, 270),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.6,
                (0, 255, 0),
                2,
            )
            cv2.putText(
                frame,
                f"Umb EAR: {ear_active_thresh:.2f}/{ear_sleep_thresh:.2f}",
                (430, 295),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.5,
                (200, 200, 200),
                1,
            )

        # Cálculo y visualización de FPS e indicadores de controles ('c', 'e', 'f')
        now = time.time()
        fps = 1.0 / max(now - fps_time, 1e-6)
        fps_time = now
        cv2.putText(
            frame,
            f"FPS: {fps:.1f} | [c]Calib",
            (10, 460),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.52,
            (0, 255, 0),
            2,
        )

        ema_label = "EMA:ON[f]" if use_ema else "EMA:OFF[f]"
        ema_color = (0, 255, 255) if use_ema else (180, 180, 180)
        cv2.putText(
            frame,
            ema_label,
            (215, 460),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.52,
            ema_color,
            2,
        )

        clahe_label = "Gamma+CLAHE:ON[e]" if use_clahe else "Gamma+CLAHE:OFF[e]"
        clahe_color = (0, 255, 0) if use_clahe else (180, 180, 180)
        cv2.putText(
            frame,
            clahe_label,
            (360, 460),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.52,
            clahe_color,
            2,
        )
        cv2.putText(
            face_frame,
            f"{clahe_label} | Luz:{mean_lum:.0f} | Gamma:{current_gamma:.2f}",
            (10, 25),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.52,
            clahe_color,
            2,
        )

        # Mostrar ambas vistas: monitor principal y detector de landmarks sobre imagen preprocesada
        cv2.imshow("Especialista en Descanso - Monitor", frame)
        cv2.imshow("Result of detector (68 Landmarks)", face_frame)

        key = cv2.waitKey(1) & 0xFF
        if key == ord("q") or key == 27:  # 'q' o tecla ESC
            break
        elif key == ord("e"):
            use_clahe = not use_clahe
        elif key == ord("f"):
            use_ema = not use_ema
            state_str = "ACTIVADO (estabilidad)" if use_ema else "DESACTIVADO (máxima sensibilidad)"
            print(f"[INFO] Filtro EMA {state_str}")
        elif key == ord("c"):
            # Reiniciar calibración personalizada de 3 segundos
            is_calibrating = True
            calib_start_ts = None
            calib_ears.clear()
            calib_pitches.clear()
            print("[INFO] Recalibrando umbrales por 3 segundos...")

    cap.release()
    cv2.destroyAllWindows()


if __name__ == "__main__":
    main()

