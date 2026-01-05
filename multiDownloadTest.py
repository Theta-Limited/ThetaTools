#/usr/bin/env python3
# multiDownloadTest.py
# Bobby Krupczak, ChatGPT
# invoke DemDownload API in parallel to test
# multithreading in Core API server

# before running the tests, set the OACore DEM cache dir
# to be something like /tmp/DemTest

import os
import sys
import time
import argparse
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
import requests
import time
import statistics
from dataclasses import dataclass
from typing import List, Tuple

RESET_STATS_PATH = "/api/v1/openathena/admin/stats/reset"
STATS_PATH = "/api/v1/openathena/admin/stats"
api_key = os.environ.get("OPENATHENA_API_KEY")
API_PATH = "/api/v1/openathena/dem"
HTTP_METHOD = "POST"
REQUEST_TIMEOUT_S=30

# up to 16 client profiles
# (lat, lon, len, gtypes[])
CLIENT_PROFILES = [
    # 0 - Smyrna, GA
    (33.88399, -84.51438, 10_000, ("SRTM", "COP30", "3DEP")),

    # 1 - Atlanta, GA
    (33.74900, -84.38800, 10_000, ("SRTM", "COP30", "3DEP")),

    # 2 - Miami, FL
    (25.76168, -80.19179, 10_000, ("SRTM", "COP30", "3DEP")),

    # 3 - Homestead, FL
    (25.46872, -80.47756, 10_000, ("SRTM", "COP30", "3DEP")),

    # 4 - Austell, GA
    (33.81261, -84.63438, 10_000, ("SRTM", "COP30", "3DEP")),

    # 5 - Mableton, GA
    (33.81871, -84.58243, 10_000, ("SRTM", "COP30", "3DEP")),

    # 6 - Kendall, FL
    (25.67927, -80.31727, 10_000, ("SRTM", "COP30", "3DEP")),

    # 7 - Gainesville, FL
    (29.65163, -82.32483, 10_000, ("SRTM", "COP30", "3DEP")),

    # 8 - Tiger, GA
    (34.86871, -83.43182, 10_000, ("SRTM", "COP30", "3DEP")),

    # 9 - Standing Indian (area), NC
    (35.03730, -83.54800, 10_000, ("SRTM", "COP30", "3DEP")),

    # 10 - Uncasville, CT
    (41.43843, -72.11368, 10_000, ("SRTM", "COP30", "3DEP")),

    # 11 - Mystic, CT
    (41.35454, -71.96646, 10_000, ("SRTM", "COP30", "3DEP")),

    # 12 - Times Square, NYC
    (40.75800, -73.98550, 10_000, ("SRTM", "COP30", "3DEP")),

    # 13 - Cimarron, NM
    (36.51003, -104.91531, 10_000, ("SRTM", "COP30", "3DEP")),

    # 14 - Tallahassee, FL
    (30.43826, -84.28073, 10_000, ("SRTM", "COP30", "3DEP")),

    # 15 - Austin, TX
    (30.26715, -97.74306, 10_000, ("SRTM", "COP30", "3DEP")),
]

def get_client_params(client_id: int, request_idx: int):
    lat, lon, length, gtypes = CLIENT_PROFILES[client_id % len(CLIENT_PROFILES)]
    return lat, lon, length, gtypes[request_idx % len(gtypes)]

@dataclass
class ClientStats:
    client_id: int
    request_count: int
    ok_count: int
    fail_count: int
    latencies_ms: List[float]
    client_wall_ms: float
    error_samples: List[str]

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
    
def build_url(host: str, port: int, path: str) -> str:
    return f"http://{host}:{port}{path}"

def build_query_params(client_id: int, request_idx: int) -> dict:
    lat, lon, length, gtype = get_client_params(client_id, request_idx)

    params = {
        "lat": lat,
        "lon": lon,
        "len": length,
        "apikey": api_key,
    }

    # dataset / gtype is optional
    if gtype:
        params["dataset"] = gtype

    return params

def do_one_request(
    session: requests.Session,
    url: str,
    client_id: int,
    request_idx: int,
) -> tuple[bool, float, str]:

    params = build_query_params(client_id, request_idx)

    # print(f"Client {client_id} posting {payload}")

    start = time.perf_counter()
    try:
        latency_ms = 0
        
        resp = session.post(
            url,
            params=params,
            timeout=REQUEST_TIMEOUT_S,
        )

        latency_ms = (time.perf_counter() - start) * 1000.0
        ok = 200 <= resp.status_code < 300

        print(f"client {client_id} response {resp.text}")

        if not ok:
            return False, latency_ms, f"HTTP {resp.status_code}: {resp.text[:200]}"

        return True, latency_ms, ""

    except Exception as e:
        latency_ms = (time.perf_counter() - start) * 1000.0
        return False, latency_ms, f"{type(e).__name__}: {e}"


def run_client(client_id: int, host: str, port: int, requests_per_client: int) -> ClientStats:
    url = build_url(host, port, API_PATH)
    latencies: List[float] = []
    ok_count = 0
    fail_count = 0
    error_samples: List[str] = []

    client_start = time.perf_counter()

    with requests.Session() as session:
        adapter = requests.adapters.HTTPAdapter(pool_connections=32, pool_maxsize=32)
        session.mount("http://", adapter)

        for i in range(requests_per_client):
            ok, latency_ms, err = do_one_request(session, url, client_id, i)
            latencies.append(latency_ms)

            if ok:
                ok_count += 1
            else:
                fail_count += 1
                if len(error_samples) < 5:
                    error_samples.append(err)

    client_wall_ms = (time.perf_counter() - client_start) * 1000.0

    return ClientStats(
        client_id=client_id,
        request_count=requests_per_client,
        ok_count=ok_count,
        fail_count=fail_count,
        latencies_ms=latencies,
        client_wall_ms=client_wall_ms,
        error_samples=error_samples,
    )


def summarize_latencies_ms(samples: List[float]) -> str:
    if not samples:
        return "no samples"

    s = sorted(samples)
    avg = statistics.fmean(s)
    p50 = statistics.median(s)
    p95 = s[int(0.95 * (len(s) - 1))]
    p99 = s[int(0.99 * (len(s) - 1))]
    return (
        f"min={s[0]:.2f}ms avg={avg:.2f}ms "
        f"p50={p50:.2f}ms p95={p95:.2f}ms "
        f"p99={p99:.2f}ms max={s[-1]:.2f}ms"
    )


def main() -> int:
    if len(sys.argv) != 5:
        print(f"Usage: {sys.argv[0]} HOST PORT NUM_CLIENTS REQUESTS_PER_CLIENT")
        return 2

    try:
        host = sys.argv[1]
        port = int(sys.argv[2])
        num_clients = int(sys.argv[3])        
        requests_per_client = int(sys.argv[4])

        if num_clients <= 0 or port <= 0 or requests_per_client <= 0:
            raise ValueError("all numeric arguments must be > 0")
        if not (1 <= num_clients <= 16):
            print("NUM_CLIENTS must be between 1 and 16")
            return 2
        if requests_per_client < 1:
            print("REQUESTS_PER_CLIENT must be >= 1")
            return 2

    except Exception as e:
        print(f"Argument error: {e}", file=sys.stderr)
        usage_exit()

    total_requests = num_clients * requests_per_client

    print("---------------------------------------------------------------------------")    
    print(f"Target: http://{host}:{port}{API_PATH}")
    print(f"Clients: {num_clients}")
    print(f"Requests/client: {requests_per_client}")
    print(f"Total requests: {total_requests}")

    stats_url = build_url(host,port,STATS_PATH)
    reset_stats_url = build_url(host,port,RESET_STATS_PATH)

    # before stats
    try:
        with requests.Session() as s:
            reset_stats(s, reset_stats_url, api_key)
            print("Reset server stats")
    except Exception as e:
        print(f"WARNING: could not fetch stats before run: {e}")

    print()

    total_start = time.perf_counter()            
    results: List[ClientStats] = []
        
    with ThreadPoolExecutor(max_workers=num_clients) as executor:
        futures = [
            executor.submit(run_client, cid, host, port, requests_per_client)
            for cid in range(num_clients)
        ]
        for fut in as_completed(futures):
            results.append(fut.result())

    total_wall_s = time.perf_counter() - total_start

    results.sort(key=lambda r: r.client_id)

    all_latencies: List[float] = []
    client_avg_latencies: list[float] = []
    total_ok = 0
    total_fail = 0

    for r in results:
        total_ok += r.ok_count
        total_fail += r.fail_count
        all_latencies.extend(r.latencies_ms)
        if r.latencies_ms:
            client_avg = statistics.fmean(r.latencies_ms)
            client_avg_latencies.append(client_avg)
        else:
            client_avg = 0.0
        
        print(
            f"[client {r.client_id:02d}] "
            f"ok={r.ok_count}/{r.request_count} "
            f"fail={r.fail_count} "
            f"client_wall={r.client_wall_ms:.1f}ms "
            f"avg_latency={client_avg:.2f}ms "
            f"lat({summarize_latencies_ms(r.latencies_ms)})"
        )

        for err in r.error_samples:
            print(f"    sample_error: {err}")

    # after stats
    try:
        with requests.Session() as s:
            after = fetch_stats(s, stats_url, api_key)
            print("\n=== Server stats (after) ===")
            print("numRESTPosts:", after.get("numRESTPosts"),
                  " numRESTErrors:",after.get("numRESTErrors"),
                  " peakActiveThreadCount:",after.get("peakActiveThreadCount"),
                  " numDemDownloads:", after.get("numDemDownloads"),
                  " numDemDownloadErrors:", after.get("numDemDownloadErrors"),
                  " downloadQPeakActive: ", after.get("downloadQPeakActive"),
                  " minDownloadTimeSecs: ", after.get("minDownloadTimeSecs"),
                  " maxDownloadTimeSecs: ", after.get("maxDownloadTimeSecs")
                  )
    except Exception as e:
        print(f"WARNING: could not fetch stats after run: {e}")

    if client_avg_latencies:
        sum_client_latency = sum(client_avg_latencies)
        avg_client_latency = statistics.fmean(client_avg_latencies)
    
    print("\n--- overall ---")
    print(f"total_wall={total_wall_s:.3f}s")
    print(f"ok={total_ok} fail={total_fail} total={total_requests}")
    print(f"latency: ({summarize_latencies_ms(all_latencies)})")
    # print(f"avg_client_latency={avg_client_latency:.2f}ms")

    if total_wall_s > 0:
        print(f"throughput={(total_requests / total_wall_s):.2f} req/s")

    print("---------------------------------------------------------------------------")

    return 0 if total_fail == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
