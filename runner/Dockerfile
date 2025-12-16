FROM eclipse-temurin:25-jre

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl unzip bash coreutils file \
    python3 python3-venv \
 && rm -rf /var/lib/apt/lists/*

# Create a venv and install deps there
RUN python3 -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

RUN pip install --no-cache-dir --upgrade pip \
 && pip install --no-cache-dir pyarrow pandas

WORKDIR /work

COPY entrypoint.sh /entrypoint.sh
COPY convert_to_parquet.py /convert_to_parquet.py
RUN chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
