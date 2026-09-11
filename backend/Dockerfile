FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    HF_HOME=/opt/hf \
    TRANSFORMERS_CACHE=/opt/hf \
    AUDIO_DIR=/tmp/audio

WORKDIR /app

RUN apt-get update \
 && apt-get install -y --no-install-recommends git \
 && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .

# torch CPU uniquement (image plus legere que la build CUDA par defaut)
RUN pip install --no-cache-dir --index-url https://download.pytorch.org/whl/cpu torch \
 && pip install --no-cache-dir -r requirements.txt

# Pre-telecharge les modeles dans l'image pour un demarrage rapide.
RUN python -c "from transformers import pipeline; pipeline('automatic-speech-recognition', model='Professor/mms-300m-fongbe')" \
 && python -c "from transformers import AutoTokenizer, VitsModel; AutoTokenizer.from_pretrained('facebook/mms-tts-fon'); VitsModel.from_pretrained('facebook/mms-tts-fon')"

COPY . .

EXPOSE 7860

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "7860"]
