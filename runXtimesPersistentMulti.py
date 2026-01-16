#!/usr/bin/env python3
"""
runXtimesPersistentMulti.py

Simple multi-client REST load test.

Args:
  HOST PORT NUM_CLIENTS REQUESTS_PER_CLIENT

Behavior:
- Spawns NUM_CLIENTS threads (1..16)
- Each client keeps ONE persistent HTTP connection (requests.Session)
- Each client sequentially POSTs imageX.json (X = client number, 1-based)
"""

import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from typing import List, Optional
from datetime import datetime

import requests
import json

RESET_STATS_PATH = "/api/v1/openathena/admin/stats/reset"
API_PATH = "/api/v1/openathena/locationsimple"
STATS_PATH = "/api/v1/openathena/admin/stats"

@dataclass
class ClientResult:
    client_id: int
    start_time_iso: str
    end_time_iso: str
    sent: int
    ok: int
    failed: int
    total_ms: float
    wall_clock_ms: float
    latencies_ms: List[float]
    first_error: Optional[str]


def read_json_bytes(filename: str) -> bytes:
    with open(filename, "rb") as f:
        return f.read()


def reset_stats(session: requests.Session, url: str, api_key: str) -> dict:
    r = session.post(url,
                    params={"apikey": api_key},
                    timeout=30
    )
    r.raise_for_status()
    return r.json()
    
def fetch_stats(session: requests.Session, url: str, api_key: str) -> dict:
    r = session.get(url,
                    params={"apikey": api_key},
                    timeout=30
    )
    r.raise_for_status()
    return r.json()


def client_worker(
    client_id: int,
    post_url: str,
    api_key: str,
    requests_per_client: int,
    json_bytes: bytes,
) -> ClientResult:
    session = requests.Session()
    session.headers.update(
        {
            "Content-Type": "application/json",
            "Connection": "keep-alive",
        }
    )

    ok = 0
    failed = 0
    latencies: List[float] = []
    first_error: Optional[str] = None

    # print(f"Client {client_id} starting with {json_bytes}")
    print(f"Client {client_id} starting")

    client_start_dt = datetime.now().astimezone()

    t0 = time.perf_counter()
    try:
        for _ in range(requests_per_client):
            start = time.perf_counter()
            try:
                r = session.post(
                    post_url,
                    params={"apikey": api_key},
                    data=json_bytes,
                    timeout=35,
                )
                if 200 <= r.status_code < 300:
                    # print(f"Client {client_id} got OK response")
                    ok += 1
                else:
                    failed += 1
                    print(f"Client {client_id} got fail {r.status_code} {r.text[:200]}")
                    if first_error is None:
                        first_error = f"HTTP {r.status_code}: {r.text[:200]}"
            except requests.RequestException as e:
                print(f"Client {client_id} got exception {e}")
                failed += 1
                if first_error is None:
                    first_error = f"{type(e).__name__}: {e}"
            finally:
                end = time.perf_counter()
                latencies.append((end - start) * 1000.0)
    finally:
        session.close()

    client_end_dt = datetime.now().astimezone()
    total_ms = (time.perf_counter() - t0) * 1000.0
    wall_clock_ms = (client_end_dt - client_start_dt).total_seconds() * 1000
    
    return ClientResult(
        client_id=client_id,
        start_time_iso=client_start_dt.isoformat(timespec="seconds"),
        end_time_iso=client_end_dt.isoformat(timespec="seconds"),
        wall_clock_ms=wall_clock_ms,
        sent=requests_per_client,
        ok=ok,
        failed=failed,
        total_ms=total_ms,
        latencies_ms=latencies,
        first_error=first_error,
    )


def percentile(sorted_vals: List[float], p: float) -> float:
    if not sorted_vals:
        return 0.0
    k = (len(sorted_vals) - 1) * (p / 100.0)
    f = int(k)
    c = min(f + 1, len(sorted_vals) - 1)
    if f == c:
        return sorted_vals[f]
    return sorted_vals[f] * (c - k) + sorted_vals[c] * (k - f)


def main() -> int:
    if len(sys.argv) != 5:
        print(f"Usage: {sys.argv[0]} HOST PORT NUM_CLIENTS REQUESTS_PER_CLIENT")
        return 2

    host = sys.argv[1]
    try:
        port = int(sys.argv[2])
        num_clients = int(sys.argv[3])
        requests_per_client = int(sys.argv[4])
    except ValueError:
        print("PORT, NUM_CLIENTS, and REQUESTS_PER_CLIENT must be integers")
        return 2

    if not (1 <= num_clients <= 16):
        print("NUM_CLIENTS must be between 1 and 16")
        return 2
    if requests_per_client < 1:
        print("REQUESTS_PER_CLIENT must be >= 1")
        return 2

    api_key = os.environ.get("OPENATHENA_API_KEY")
    if not api_key:
        print("ERROR: OPENATHENA_API_KEY is not set")
        return 2

    base_url = f"http://{host}:{port}"
    post_url = base_url + API_PATH
    stats_url = base_url + STATS_PATH
    reset_url = base_url + RESET_STATS_PATH

    # Load JSON bodies
    json_blobs = {}
    for cid in range(1, num_clients + 1):
        json_blobs[cid] = read_json_bytes(f"image{cid}.json")

    print(f"Target: {post_url}")
    print(f"Clients: {num_clients}")
    print(f"Requests per client: {requests_per_client}")
    print(f"Total requests: {num_clients * requests_per_client}")

    # Stats before
    try:
        with requests.Session() as s:
            reset_stats(s,reset_url, api_key)
            before = fetch_stats(s, stats_url, api_key)
            print("\n=== Server stats (before) ===")
            print("numRESTPosts:", before.get("numRESTPosts"))
    except Exception as e:
        print(f"WARNING: could not fetch stats before run: {e}")

    start_wall = time.perf_counter()

    results: List[ClientResult] = []
    with ThreadPoolExecutor(max_workers=num_clients) as ex:
        futures = [
            ex.submit(
                client_worker,
                cid,
                post_url,
                api_key,
                requests_per_client,
                json_blobs[cid],
            )
            for cid in range(1, num_clients + 1)
        ]
        for f in as_completed(futures):
            results.append(f.result())

    wall_ms = round( (time.perf_counter() - start_wall) * 1000.0, 2)
    results.sort(key=lambda r: r.client_id)

    all_latencies = sorted(ms for r in results for ms in r.latencies_ms)
    total_ok = sum(r.ok for r in results)
    total_failed = sum(r.failed for r in results)

    print("\n=== Per-client ===")
    for r in results:
        avg = sum(r.latencies_ms) / len(r.latencies_ms)
        print(
            f"client {r.client_id:2d}: ok={r.ok:4d} failed={r.failed:4d} "
            f"total={r.total_ms:8.2f} ms avg={avg:7.2f} ms "
            f"duration={r.wall_clock_ms:.2f} ms "
        )

    rps = total_ok / (wall_ms / 1000.0)

    print("\n=== Overall ===")
    print(f"Wall time: {wall_ms:.2f} ms")
    print(f"OK: {total_ok}  Failed: {total_failed}")
    print(f"Throughput: {rps:.2f} req/s")
    print(
        f"Latency ms: avg={sum(all_latencies)/len(all_latencies):.2f} "
        f"p50={percentile(all_latencies,50):.2f} "
        f"p90={percentile(all_latencies,90):.2f} "
        f"p99={percentile(all_latencies,99):.2f}"
    )

    # Stats after
    try:
        with requests.Session() as s:
            after = fetch_stats(s, stats_url, api_key)
            print("\n=== Server stats (after) ===")
            print("numRESTPosts:", after.get("numRESTPosts"))
            print("peakActiveThreadCount:", after.get("serverPeakActiveThreadCount"))
            print("numDemDownloads:", after.get("numDemDownloads"))
            print("numCotSends:", after.get("numCotSends"))
            print("cotManagerPeakQueueDepth:",after.get("cotManagerPeakQueueDepth"))
            # print(json.dumps(after, indent=1, sort_keys=True))
            # print(after)
    except Exception as e:
        print(f"WARNING: could not fetch stats after run: {e}")

    return 0 if total_failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
