FROM eclipse-temurin:8-jre

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl unzip bash coreutils \
    python3 python3-pip \
 && rm -rf /var/lib/apt/lists/*

# PyArrow wheels exist for manylinux; this is the easiest path.
RUN pip3 install --no-cache-dir pyarrow pandas

WORKDIR /work

COPY entrypoint.sh /entrypoint.sh
COPY convert_to_parquet.py /convert_to_parquet.py
RUN chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
