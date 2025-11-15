#!/usr/bin/env python3

import os
import sys
import time
import requests
import json

# API key from environment (e.g. export API_KEY=... before running)
API_KEY = os.environ.get("OPENATHENA_API_KEY")

# REST server URL (same as you used with curl)
BASE_URL = "http://127.0.0.1:8000"
PATH = "/api/v1/openathena/locationsimple?apikey=" + API_KEY
STATSPATH = "/api/v1/openathena/admin/stats?apikey=" + API_KEY

# JSON body file: either from env var JSON_FILE or hardcoded fallback
IMAGE_FILES = [
    "image0.json",
    "image1.json",
    "image2.json",
    "image3.json",
    "image4.json",                
]

def load_body_from_file(path: str) -> str:
    with open(path, "r", encoding="utf-8") as f:
        return f.read()

def main():
    if len(sys.argv) != 2:
        print(f"Usage: {sys.argv[0]} NUM_REQUESTS")
        sys.exit(1)

    try:
        num_requests = int(sys.argv[1])
    except ValueError:
        print("NUM_REQUESTS must be an integer")
        sys.exit(1)

    BODIES = [load_body_from_file(p) for p in IMAGE_FILES]
    url = BASE_URL + PATH
    statsurl = BASE_URL + STATSPATH

    session = requests.Session()

    headers = {
        "Content-Type": "application/json",
        "Connection": "keep-alive",
    }
    if API_KEY:
        headers["Authorization"] = f"Bearer {API_KEY}"

    print(f"Sending {num_requests} POST requests to {url}")

    if API_KEY:
        print("Using API_KEY from environment.")
    else:
        print("No API_KEY set in environment (API_KEY).")

    # make first request and show the output; this warms up the
    # cache
    resp = session.get(statsurl, headers=headers)
    stats = resp.json()
    start_counter = stats["numRESTPosts"]
    print("Begining number of posts: ",start_counter)
    print("Heap: Sz: ",stats["coreHeapSize"]," Max: ",stats["coreHeapMaxSize"]," Free: ",stats["coreHeapFreeMemory"])    
            
    total_start = time.perf_counter();

    for i in range(1, num_requests + 1):
        body = BODIES[(i-1) % len(BODIES)]
        try:
            resp = session.post(url, data=body, headers=headers)
            # You can comment this out if you don’t want per-request noise
            # print(f"{i}/{num_requests}: ",resp.json())
        except requests.exceptions.RequestException as e:
            print(f"{i}/{num_requests}: request failed: {e}")
            # Optional: try to recreate the session once if the connection died
            session.close()
            session = requests.Session()

    total_end = time.perf_counter();

    resp = session.get(statsurl, headers=headers)
    stats = resp.json()
    end_counter = stats["numRESTPosts"]    
    print("Ending number of posts: ",stats["numRESTPosts"])
    print("Delta posts is ",end_counter-start_counter)
    print("Heap: Sz: ",stats["coreHeapSize"]," Max: ",stats["coreHeapMaxSize"]," Free: ",stats["coreHeapFreeMemory"])
    
    session.close()

    total_duration_ms = (total_end - total_start) * 1000.0
    avg_ms = total_duration_ms / num_requests if num_requests > 0 else 0.0
    print(f"\nTotal time: {total_duration_ms:.2f} ms for {num_requests} requests")
    print(f"Average per request: {avg_ms:.2f} ms")

if __name__ == "__main__":
    main()
