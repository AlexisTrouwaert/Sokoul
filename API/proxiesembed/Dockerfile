# proxiesembed (aiohttp) for the local Sokoul backend: embed extraction and the signed media proxies.
FROM python:3.12-slim
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends build-essential libxml2-dev libxslt1-dev ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt
COPY . .
EXPOSE 25569
CMD ["python", "-u", "server.py"]
